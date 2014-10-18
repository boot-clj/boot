(ns boot.aether
  (:require
   [clojure.java.io             :as io]
   [clojure.string              :as string]
   [cemerick.pomegranate.aether :as aether]
   [boot.util                   :as util]
   [boot.pod                    :as pod]
   [boot.from.io.aviso.ansi     :as ansi]
   [boot.kahnsort               :as ksort])
  (:import
   [java.io File]
   [java.util.jar JarFile]
   [org.sonatype.aether.resolution DependencyResolutionException]))

(def offline?   (atom false))
(def update?    (atom :daily))
(def local-repo (atom nil))

(defn set-offline!    [x] (reset! offline? x))
(defn set-update!     [x] (reset! update? x))
(defn set-local-repo! [x] (reset! local-repo x))

(defn default-repositories
  []
  [["clojars"       "http://clojars.org/repo/"]
   ["maven-central" "http://repo1.maven.org/maven2/"]])

(defn transfer-listener
  [{type :type meth :method {name :name repo :repository} :resource err :error}]
  (when (and (.endsWith name ".jar") (= type :started))
    (util/info "Retrieving %s from %s\n" (.getName (io/file name)) repo)))

(defn ^{:boot/from :technomancy/leiningen} build-url
  "Creates java.net.URL from string"
  [url]
  (try (java.net.URL. url)
       (catch java.net.MalformedURLException _
         (java.net.URL. (str "http://" url)))))

(defn ^{:boot/from :technomancy/leiningen} get-non-proxy-hosts
  []
  (let [system-no-proxy (System/getenv "no_proxy")]
    (if (not-empty system-no-proxy)
      (->> (string/split system-no-proxy #",")
           (map #(str "*" %))
           (string/join "|")))))

(defn ^{:boot/from :technomancy/leiningen} get-proxy-settings
  "Returns a map of the JVM proxy settings"
  ([] (get-proxy-settings "http_proxy"))
  ([key]
     (if-let [proxy (System/getenv key)]
       (let [url (build-url proxy)
             user-info (.getUserInfo url)
             [username password] (and user-info (.split user-info ":"))]
         {:host            (.getHost url)
          :port            (.getPort url)
          :username        username
          :password        password
          :non-proxy-hosts (get-non-proxy-hosts)}))))

(defn resolve-dependencies*
  [env]
  (try
    (aether/resolve-dependencies
      :coordinates       (:dependencies env)
      :repositories      (->> (or (:repositories env) (default-repositories))
                           (map (juxt first (fn [[x y]] (if (map? y) y {:url y}))))
                           (map (juxt first (fn [[x y]] (update-in y [:update] #(or % @update?))))))
      :local-repo        (or (:local-repo env) @local-repo nil)
      :offline?          (or @offline? (:offline? env))
      :mirrors           (:mirrors env)
      :proxy             (or (:proxy env) (get-proxy-settings))
      :transfer-listener transfer-listener)
    (catch Exception e
      (let [root-cause (last (take-while identity (iterate (memfn getCause) e)))]
        (if-not (and (not @offline?) (instance? java.net.UnknownHostException root-cause))
          (throw e)
          (do (reset! offline? true)
              (resolve-dependencies* env)))))))

(def resolve-dependencies-memoized* (memoize resolve-dependencies*))

(defn- dep->path [dep] (->> dep meta :file .getPath))

(defn resolve-dependencies
  "Given an env map, returns a list of maps of the form
     {:dep [foo/bar \"1.2.3\"], :jar \"file:...\"}
   corresponding to the resolved dependencies (including transitive deps)."
  [env]
  (->> [:dependencies :repositories :local-repo :offline? :mirrors :proxy]
    (select-keys env)
    resolve-dependencies-memoized*
    ksort/topo-sort
    (map (fn [x] {:dep x :jar (dep->path x)}))))

(defn resolve-dependency-jars
  "Given an env map, resolves dependencies and returns a list of dependency jar 
  file paths.

  The 3-arity is used by boot.App to resolve dependencies for the core pods.
  It returns a Java array of java.io.File objects corresponding to the resolved
  dependencies."
  ([env] (->> env resolve-dependencies (map :jar)))
  ([sym-str version cljversion]
     (->> {:dependencies [['org.clojure/clojure cljversion] [(symbol sym-str) version]]}
       resolve-dependencies (map (comp io/file :jar)) (into-array java.io.File))))

(def jars-in-dep-order
  "Given an env map, returns a list of all jar files on on the classpath in
  dependency order. Dependency order means, eg. if jar B depends on jar A then
  jar A will appear before jar B in the returned list."
  (memoize
    (fn [env & regexes]
      (let [resol  resolve-dependencies-memoized*
            resol' #(->> % (assoc env :dependencies) resol)
            deps   (->> env resol keys)
            jars   (-> (fn [xs [k & _ :as x]]
                         (assoc xs k (dep->path x)))
                     (reduce {} deps))]
        (->> deps
          (pmap #(vector % (get (resol' (vector %)) %)))
          (map (fn [[[k & _] v]] [k (set (map first v))]))
          ((comp reverse ksort/topo-sort (partial into {})))
          (map jars))))))

(defn- print-tree
  [tree & [prefixes]]
  (loop [[[coord branch] & more] (seq tree)]
    (when coord
      (let [pfx      (cond (not prefixes) "" (seq more) "├── " :else "└── ")
            pfx      (ansi/blue (str (apply str prefixes) pfx))]
        (println (str pfx (->> coord pr-str rest butlast (apply str)))))
      (when branch
        (let [pfx (cond (not prefixes) "" (seq more) "│   " :else "    ")]
          (print-tree branch (concat prefixes (list pfx)))))
      (recur more))))

(defn dep-tree
  "Returns the printed dependency graph as a string."
  [env]
  (->> env
    resolve-dependencies-memoized*
    (aether/dependency-hierarchy (:dependencies env))
    print-tree
    with-out-str))

(defn install
  [env jarfile]
  (let [{:keys [project version]}
        (-> jarfile pod/pom-properties pod/pom-properties-map)
        pomfile (doto (File/createTempFile "pom" ".xml")
                  .deleteOnExit (spit (pod/pom-xml jarfile)))]
    (aether/install
      :coordinates [project version]
      :jar-file    (io/file jarfile)
      :pom-file    (io/file pomfile)
      :local-repo  (or (:local-repo env) @local-repo nil))))

(defn deploy
  [env repo jarfile & [artifact-map]]
  (let [{:keys [project version]}
        (-> jarfile pod/pom-properties pod/pom-properties-map)
        pomfile (doto (File/createTempFile "pom" ".xml")
                  .deleteOnExit (spit (pod/pom-xml jarfile)))]
    (aether/deploy
      :coordinates  [project version]
      :jar-file     (io/file jarfile)
      :pom-file     (io/file pomfile)
      :artifact-map artifact-map
      :repository   [repo]
      :local-repo   (or (:local-repo env) @local-repo nil))))

(def ^:private wagon-files (atom #{}))

(defn load-wagon-mappings
  [& [mapping]]
  (locking wagon-files
    (->> (pod/resources "leiningen/wagons.clj")
      (remove (partial contains? @wagon-files))
      (map #(do (swap! wagon-files conj %)
                (->> % io/input-stream slurp read-string)))
      (reduce into {})
      (mapv (fn [[k v]] (aether/register-wagon-factory! k (eval v))))))
  (doseq [[scheme factory] mapping]
    (aether/register-wagon-factory! scheme (eval factory))))

(defn add-wagon
  [env coord & [mapping]]
  (pod/add-dependencies (assoc env :dependencies [coord]))
  (load-wagon-mappings mapping))
