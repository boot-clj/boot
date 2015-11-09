(ns boot.task-helpers
  (:require
    [clojure.java.io         :as io]
    [clojure.set             :as set]
    [clojure.string          :as string]
    [clojure.stacktrace      :as trace]
    [boot.from.io.aviso.ansi :as ansi]
    [boot.from.digest        :as digest]
    [boot.pod                :as pod]
    [boot.core               :as core]
    [boot.file               :as file]
    [boot.util               :as util]
    [boot.tmpdir             :as tmpd]))

(defn- first-line [s] (when s (first (string/split s #"\n"))))

(defn- tasks-table [tasks]
  (let [get-task #(-> % :name str)
        get-desc #(-> % :doc first-line)
        built-in {nil (get tasks 'boot.task.built-in)}]
    (->> (dissoc tasks 'boot.task.built-in)
      (concat built-in) (interpose nil)
      (mapcat (fn [[_ xs]] (or xs [{:name "" :doc ""}])))
      (mapv (fn [x] ["" (get-task x) (get-desc x)])))))

(defn- set-title [[[_ & xs] & zs] title] (into [(into [title] xs)] zs))

(defn- available-tasks [sym]
  (let [base  {nil (the-ns sym)}
        task? #(:boot.core/task %)
        nssym #(->> % meta :ns ns-name)
        addrf #(if-not (seq %1) %2 (symbol %1 (str %2)))
        proc  (fn [a [k v]] (assoc (meta v) :ns* (nssym v) :name (addrf a k) :var v))
        pubs  (fn [[k v]] (map proc (repeat (str k)) (ns-publics v)))]
    (->>
      (concat
        (->> sym ns-refers (map proc (repeat nil)))
        (->> sym ns-aliases (into base) (mapcat pubs)))
      (filter task?) (sort-by :name) (group-by :ns*) (into (sorted-map)))))

(defn read-pass
  [prompt]
  (String/valueOf (.readPassword (System/console) prompt nil)))

(defn print-fileset
  [fileset]
  (letfn [(tree [xs]
            (when-let [xs (seq (remove nil? xs))]
              (->> (group-by first xs)
                   (reduce-kv #(let [t (->> %3 (map (comp seq rest)) tree)]
                                 (assoc %1 (if (map? t) (ansi/bold-blue %2) %2) t))
                              (sorted-map-by #(->> %& (map ansi/strip-ansi) (apply compare)))))))]
    (->> fileset core/ls (map (comp file/split-path core/tmp-path)) tree util/print-tree)))

;; sift helpers ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- sift-match
  [invert? regexes]
  (->> (map #(partial re-find %) regexes)
       (apply some-fn)
       (comp (if invert? not identity))))

(defn- sift-meta
  [invert? kws]
  (->> (map #(fn [x] (contains? (meta x) %)) kws)
       (apply some-fn)
       (comp (if invert? not identity))))

(defn- sift-mv
  [rolekey invert? optargs]
  (fn [fileset]
    (let [match?  (sift-match invert? optargs)
          dir     (#'core/get-add-dir fileset #{rolekey})
          reducer (fn [xs k v]
                    (->> (if-not (match? k) v (assoc v :dir dir))
                         (assoc xs k)))]
      (->> (partial reduce-kv reducer {})
           (update-in fileset [:tree])))))

(defn- sift-add
  [rolekey optargs]
  (fn [fileset]
    (let [dir (#'core/get-add-dir fileset #{rolekey})]
      (->> (map io/file optargs)
           (reduce #(tmpd/add %1 dir %2 nil) fileset)))))

(defn- sift-filter
  [match?]
  (let [reducer (fn [xs k v] (if-not (match? k) xs (assoc xs k v)))]
    (fn [fileset] (->> (partial reduce-kv reducer {})
                       (update-in fileset [:tree])))))

(defn- jar-path
  [sym]
  (let [env (core/get-env)]
    (->> env
         :dependencies
         (filter #(= sym (first %)))
         first
         (pod/resolve-dependency-jar env))))

(defmulti  sift-action (fn [invert? opkey optargs] opkey))
(defmethod sift-action :to-asset     [v? _ args] (sift-mv  :asset    v? args))
(defmethod sift-action :to-resource  [v? _ args] (sift-mv  :resource v? args))
(defmethod sift-action :to-source    [v? _ args] (sift-mv  :source   v? args))
(defmethod sift-action :add-asset    [_  _ args] (sift-add :asset       args))
(defmethod sift-action :add-resource [_  _ args] (sift-add :resource    args))
(defmethod sift-action :add-source   [_  _ args] (sift-add :source      args))
(defmethod sift-action :include      [v? _ args] (sift-filter (sift-match v? args)))
(defmethod sift-action :with-meta    [v? _ args] (sift-filter (sift-meta  v? args)))

(defmethod sift-action :move
  [_ _ args]
  (let [proc    #(reduce-kv string/replace % args)
        reducer (fn [xs k v]
                  (let [k (proc k)]
                    (assoc xs k (assoc v :path k))))]
    (fn [fileset]
      (->> (partial reduce-kv reducer {})
           (update-in fileset [:tree])))))

(defmethod sift-action :add-jar
  [v? _ args]
  (fn [fileset]
    (-> (fn [fs [sym regex]]
          (let [incl (when-not v? [regex])
                excl (when v? [regex])
                jar  (jar-path sym)]
            (core/add-cached-resource
              fs (digest/md5 jar) (partial pod/unpack-jar jar)
              :include incl :exclude excl :mergers pod/standard-jar-mergers)))
        (reduce fileset args))))
