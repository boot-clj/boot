(ns boot.aether
  (:require
   [clojure.java.io             :as io]
   [clojure.string              :as string]
   [clojure.pprint              :as pprint]
   [cemerick.pomegranate.aether :as aether]
   [boot.util                   :as util]
   [boot.pod                    :as pod]
   [boot.gpg                    :as gpg]
   [boot.from.io.aviso.ansi     :as ansi]
   [boot.kahnsort               :as ksort])
  (:import
   [boot App]
   [java.io File]
   [java.util.jar JarFile]
   [java.util.regex Pattern]
   [org.sonatype.aether.resolution DependencyResolutionException]))

(def offline?             (atom false))
(def update?              (atom :daily))
(def local-repo           (atom nil))
(def default-repositories (atom [["clojars"       "https://clojars.org/repo/"]
                                 ["maven-central" "https://repo1.maven.org/maven2/"]]))

(defn set-offline!    [x] (reset! offline? x))
(defn set-update!     [x] (reset! update? x))
(defn update-always!  []  (set-update! :always))
(defn set-local-repo! [x] (reset! local-repo x))

(defn transfer-listener
  [{type :type meth :method {name :name repo :repository} :resource err :error :as info}]
  (util/dbug "Aether: %s\n" (with-out-str (pprint/pprint info)))
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

(defn ^{:boot/from :technomancy/leiningen} credentials-fn
  "Decrypt map from credentials.clj.gpg in Boot home if present."
  ([] (let [cred-file (io/file (App/bootdir) "credentials.clj.gpg")]
        (if (.exists cred-file)
          (credentials-fn cred-file))))
  ([file]
   (let [{:keys [out err exit]} (gpg/gpg "--quiet" "--batch"
                                         "--decrypt" "--" (str file))]
     (if (pos? exit)
       (binding [*out* *err*]
         (util/warn (format (str "Could not decrypt credentials from %s\n"
                                 "%s\n"
                                 "See `boot gpg --help` for how to install gpg.")
                            (str file) err)))
       (read-string out)))))

(def credentials (memoize credentials-fn))

(defn- ^{:boot/from :technomancy/leiningen} match-credentials [settings auth-map]
  (get auth-map (:url settings)
       (first (for [[re? cred] auth-map
                    :when (and (instance? Pattern re?)
                               (re-find re? (:url settings)))]
                cred))))

(defn- ^{:boot/from :technomancy/leiningen} resolve-credential
  "Resolve key-value pair from result into a credential, updating result."
  [id source-settings result [k v]]
  (let [key-name (string/upper-case (name k))
        env-name (str "BOOT_" (string/upper-case id) "_" key-name)
        from-env #(or (System/getProperty %) (System/getenv %))]
    (letfn [(resolve [v]
              (cond (= :env v)
                    (from-env env-name)

                    (and (keyword? v) (= "env" (namespace v)))
                    (from-env key-name)

                    (= :gpg v)
                    (get (match-credentials source-settings (credentials)) k)

                    (coll? v) ;; collection of places to look
                    (->> (map resolve v) (remove nil?) first)

                    :else v))]
      (if (#{:username :password :passphrase :private-key-file} k)
        (assoc result k (resolve v))
        (assoc result k v)))))

(defn ^{:boot/from :technomancy/leiningen} resolve-credentials
  "Applies credentials from the environment or ~/.boot/credentials.clj.gpg
  as they are specified and available."
  [id settings]
  (let [gpg-creds (if (= :gpg (:creds settings))
                    (match-credentials settings (credentials)))
        resolved (reduce (partial resolve-credential id settings)
                         (empty settings)
                         settings)]
    (if gpg-creds
      (dissoc (merge gpg-creds resolved) :creds)
      resolved)))

(defn resolve-dependencies*
  [env]
  (try
    (aether/resolve-dependencies
      :coordinates       (:dependencies env)
      :repositories      (->> (or (:repositories env) @default-repositories)
                           (map (juxt first (fn [[x y]] (if (map? y) y {:url y}))))
                           (map (juxt first (fn [[x y]] (resolve-credentials x y))))
                           (map (juxt first (fn [[x y]] (update-in y [:update] #(or % @update?))))))
      :local-repo        (or (:local-repo env) @local-repo nil)
      :offline?          (or @offline? (:offline? env))
      :mirrors           (:mirrors env)
      :proxy             (or (:proxy env) (get-proxy-settings))
      :transfer-listener transfer-listener
      :repository-session-fn (if (= @update? :always)
                               #(doto (aether/repository-session %)
                                  (.setUpdatePolicy (aether/update-policies :always)))
                               aether/repository-session))
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
  ([sym-str version cljversion] (resolve-dependency-jars sym-str version nil cljversion))
  ([sym-str version cljname cljversion]
     (let [cljname (or cljname "org.clojure/clojure")]
       (->> {:dependencies [[(symbol cljname) cljversion] [(symbol sym-str) version]]}
            resolve-dependencies (map (comp io/file :jar)) (into-array java.io.File)))))

(defn resolve-nontransitive-dependencies
  "Given an env map and a single dependency coordinates vector, resolves the
  dependency and returns its direct, non-transitive dependencies. Dependencies
  are returned as a list of maps of the same form as resolve-dependencies above."
  [env dep]
  (->> (assoc env :dependencies [dep])
       resolve-dependencies-memoized*
       (#(get % dep))
       (map (fn [x] {:dep x :jar (dep->path x)}))))

(def jars-dep-graph
  "Given an env map, returns a dependency graph for all jar files on on the
  classpath."
  (memoize
    (fn [env]
      (let [resol  resolve-dependencies-memoized*
            resol' #(->> % (assoc env :dependencies) resol)
            deps   (->> env resol keys)
            jars   (-> (fn [xs [k & _ :as x]]
                         (assoc xs k (dep->path x)))
                       (reduce {} deps))]
        (->> deps
          (pmap #(vector % (get (resol' (vector %)) %)))
          (map (fn [[[k & _] v]] [(jars k) (set (map (comp jars first) v))]))
          (into {}))))))

(defn jars-in-dep-order
  "Given an env map, returns a list of all jar files on on the classpath in
  dependency order. Dependency order means, eg. if jar B depends on jar A then
  jar A will appear before jar B in the returned list."
  [env]
  (->> env jars-dep-graph ksort/topo-sort reverse))

(defn dep-tree
  "Returns the printed dependency graph as a string."
  [env]
  (->> env
    resolve-dependencies-memoized*
    (aether/dependency-hierarchy (:dependencies env))
    util/print-tree
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
  [env [repo-id repo-settings] jarfile & [artifact-map]]
  (let [{:keys [project version]}
        (-> jarfile pod/pom-properties pod/pom-properties-map)
        pomfile (doto (File/createTempFile "pom" ".xml")
                  .deleteOnExit (spit (pod/pom-xml jarfile)))
        repo-settings (resolve-credentials repo-id repo-settings)]
    (aether/deploy
      :coordinates  [project version]
      :jar-file     (io/file jarfile)
      :pom-file     (io/file pomfile)
      :artifact-map artifact-map
      :repository   {repo-id repo-settings}
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
