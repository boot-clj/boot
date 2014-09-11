(ns boot.util
  (:require
   [clojure.java.io              :as io]
   [clojure.set                  :as set]
   [clojure.pprint               :as pprint]
   [boot.from.io.aviso.repl      :as repl]
   [boot.from.io.aviso.exception :as pretty])
  (:import
   [java.io       File]
   [java.nio      ByteBuffer]
   [java.util     UUID]
   [java.util.zip ZipFile]
   [java.util.jar JarEntry JarOutputStream]))

(declare print-ex)

(def verbose-exceptions (atom 1))

(defn info
  [& more]
  (binding [*out* *err*] (apply printf more) (flush)))

(defn warn
  [& more]
  (binding [*out* *err*] (apply printf more) (flush)))

(defn fail
  [& more]
  (binding [*out* *err*] (apply printf more) (flush)))

(defmacro with-let
  "Binds resource to binding and evaluates body.  Then, returns
  resource.  It's a cross between doto and with-open."
  [[binding resource] & body]
  `(let [~binding ~resource] ~@body ~binding))

(defmacro dotoseq
  [obj seq-exprs & body]
  `(let [o# ~obj] (doseq ~seq-exprs (doto o# ~@body)) o#))

(defmacro with-resolve
  [bindings & body]
  (let [res (fn [[x y]] [x `(do (require ~(symbol (namespace y))) (resolve '~y))])]
    `(let [~@(->> bindings (partition 2) (mapcat res))] ~@body)))

(defmacro let-assert-keys
  "Let expression that throws an exception when any of the expected bindings is missing."
  [binding & body]
  (let [[ks m] [(butlast binding) (last binding)]
        req-ks (set (map keyword ks)) ]
    `(if-let [dif-ks# (not-empty (set/difference ~req-ks (set (keys ~m))))]
       (throw (new AssertionError (apply format "missing key(s): %s" dif-ks#)))
       (let [{:keys ~ks} ~m] ~@body))))

(defmacro guard
  "Returns nil instead of throwing exceptions."
  [expr & [default]]
  `(try ~expr (catch Throwable _# ~default)))

(defmacro with-rethrow
  "Evaluates expr and rethrows any thrown exceptions with the given message."
  [expr message]
  `(try ~expr (catch Throwable e# (throw (Exception. ~message e#)))))

(defmacro exit-error
  [& body]
  `(binding [*out* *err*]
     ~@body
     (System/exit 1)))

(defmacro exit-ok
  [& body]
  `(try
     ~@body
     (System/exit 0)
     (catch Throwable e#
       (exit-error (print-ex e#)))))

(defn print-ex
  [ex]
  (case @verbose-exceptions
    0 (binding [*out* *err*] (println (.getMessage ex)))
    1 (pretty/write-exception *err* ex
        {:properties true :filter repl/standard-frame-filter})
    (pretty/write-exception *err* ex {:properties true})))

(defn auto-flush
  [writer]
  (proxy [java.io.PrintWriter] [writer]
    (write [s] (.write writer s) (flush))))

(defn extract-ids
  [sym]
  (let [[group artifact] ((juxt namespace name) sym)]
    [(or group artifact) artifact]))

(defn dep-as-map
  [[project version & kvs]]
  (let [d {:project project :version version}]
    (merge {:scope "compile"}
      (if-not (seq kvs) d (apply assoc d kvs)))))

(defn jarname
  [project version]
  (str (second (extract-ids project)) "-" version ".jar"))

(defn index-of
  [v val]
  (ffirst (filter (comp #{val} second) (map vector (range) v))))

(defn bind-syms
  [form]
  (let [sym? #(and (symbol? %) (not= '& %))]
    (->> form (tree-seq coll? seq) (filter sym?) distinct)))

(defn pp*             [expr] (pprint/write expr :dispatch pprint/code-dispatch))
(defn pp-str          [expr] (with-out-str (pp* expr)))
(defn read-string-all [s]    (read-string (str "(" s "\n)")))
