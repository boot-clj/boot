(ns boot.util
  (:require
    [clojure.java.io              :as io]
    [clojure.set                  :as set]
    [clojure.pprint               :as pprint]
    [clojure.string               :as string]
    [boot.file                    :as file]
    [boot.from.io.aviso.ansi      :as ansi]
    [boot.from.io.aviso.repl      :as repl]
    [boot.from.io.aviso.exception :as pretty]
    [boot.from.me.raynes.conch    :as conch])
  (:import
    [java.io       File]
    [java.nio      ByteBuffer]
    [java.util     UUID]
    [java.util.zip ZipFile]
    [java.util.jar JarEntry JarOutputStream]))

(declare print-ex)

(defn colorize?-system-default
  "return whether we should colorize output on this system. This is
  true, unless we're on Windows, where this is false. The default
  console on Windows does not interprete ansi escape codes. The
  default can be overriden by setting the environment variable
  BOOT_COLOR=1 or BOOT_COLOR=yes to turn it on or any other value to
  turn it off."
  []
  (cond
    (System/getenv "BOOT_COLOR")
      (contains? #{"1" "yes"} (System/getenv "BOOT_COLOR"))
    (.startsWith (System/getProperty "os.name") "Windows")
      false
    :else
      true))

(def ^:dynamic *verbosity* (atom 1))
(def ^:dynamic *colorize?* (atom (colorize?-system-default)))

(defn- print*
  [verbosity color args]
  (when (>= @*verbosity* verbosity)
    (binding [*out* *err*]
      (print ((or color identity) (apply format args)))
      (flush))))

(defn dbug [& more] (print* 2 ansi/bold-cyan   more))
(defn info [& more] (print* 1 ansi/bold        more))
(defn warn [& more] (print* 1 ansi/bold-yellow more))
(defn fail [& more] (print* 1 ansi/bold-red    more))

(defmacro with-semaphore
  [sem & body]
  `(let [sem# ~sem]
     (.acquire sem#)
     (dbug "Acquired %s...\n" sem#)
     (try ~@body
          (finally (.release sem#)
                   (dbug "Released %s...\n" sem#)))))

(defmacro with-semaphore-noblock
  [sem & body]
  `(let [sem# ~sem]
     (when (.tryAcquire sem#)
       (dbug "Acquired %s...\n" sem#)
       (try ~@body
            (finally (.release sem#)
                     (dbug "Released %s...\n" sem#))))))

(defmacro with-let
  "Binds resource to binding and evaluates body.  Then, returns
  resource.  It's a cross between doto and with-open."
  [[binding resource] & body]
  `(let [ret# ~resource ~binding ret#] ~@body ret#))

(defmacro while-let
  "Repeatedly executes body while test expression is true. Test
  expression is bound to binding."
  [[binding test] & body]
  `(loop [~binding ~test]
     (when ~binding ~@body (recur ~test))))

(defmacro do-while-let
  "Like while-let, except that the body is executed at least once."
  [[binding test] & body]
  `(loop [~binding ~test]
     ~@body
     (when ~binding (recur ~test))))

(defmacro dotoseq
  [obj seq-exprs & body]
  `(let [o# ~obj] (doseq ~seq-exprs (doto o# ~@body)) o#))

(defmacro with-resolve
  [bindings & body]
  (let [res (fn [[x y]] [x `(do (require ~(symbol (namespace y))) (resolve '~y))])]
    `(let [~@(->> bindings (partition 2) (mapcat res))] ~@body)))

(defmacro let-assert-keys
  "Let expression that throws an exception when any of the expected bindings
  is missing."
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
     (throw (boot.App$Exit. (str 1)))))

(defmacro exit-ok
  [& body]
  `(try
     ~@body
     (throw (boot.App$Exit. (str 0)))
     (catch Throwable e#
       (if (instance? boot.App$Exit e#)
         (throw e#)
         (exit-error (print-ex e#))))))

(defmacro with-err-str
  "Evaluates exprs in a context in which *err* is bound to a fresh
  StringWriter.  Returns the string created by any nested printing
  calls.
  http://stackoverflow.com/questions/17314128/get-stacktrace-as-string"
  [& body]
  `(let [s# (new java.io.StringWriter)]
     (binding [*err* s#]
       ~@body
       (str s#))))

(defn print-ex
  [ex]
  (case @*verbosity*
    0 nil
    1 (pretty/write-exception *err* ex
        {:properties true :filter repl/standard-frame-filter})
    2 (pretty/write-exception *err* ex {:properties true})
    (binding [*out* *err*] (.printStackTrace ex))))

(defn print-tree
  [tree & [prefixes]]
  (loop [[[node branch] & more] (seq tree)]
    (when node
      (let [pfx      (cond (not prefixes) "" (seq more) "├── " :else "└── ")
            pfx      (ansi/blue (str (apply str prefixes) pfx))]
        (println (str pfx node)))
      (when branch
        (let [pfx (cond (not prefixes) "" (seq more) "│   " :else "    ")]
          (print-tree branch (concat prefixes (list pfx)))))
      (recur more))))

(defn path->ns
  [path]
  (-> path file/split-path (#(string/join "." %))
    (.replace \_ \-) (.replaceAll "\\.clj$" "") symbol))

(defn auto-flush
  [writer]
  (let [strip? #(and (not @*colorize?*) (string? %))
        strip  #(fn [s] (if (strip? s) (ansi/strip-ansi s) s))]
    (proxy [java.io.PrintWriter] [writer]
      (write
        ([s]
         (.write writer ((strip) s))
         (.flush writer))
        ([s ^Integer off ^Integer len]
         (.write writer s off len)
         (.flush writer))))))

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

(def ^:dynamic *sh-dir* nil)

(defn sh [& args]
  (let [args (remove nil? args)]
    (assert (every? string? args))
    (let [opts (into [:redirect-err true] (when *sh-dir* [:dir *sh-dir*]))
          proc (apply conch/proc (concat args opts))]
      (future (conch/stream-to-out proc :out))
      #(.waitFor (:process proc)))))

(defn dosh [& args]
  (let [args (remove nil? args)]
    (assert (every? string? args))
    (let [status ((apply sh args))]
      (when-not (= 0 status)
        (throw (Exception. (-> "%s: non-zero exit status (%d)"
                               (format (first args) status))))))))

(defmacro without-exiting
  "Evaluates body in a context where System/exit doesn't work.
  Returns result of evaluating body, or nil if code in body attempted to exit."
  [& body]
  `(let [old-sm# (System/getSecurityManager)
         new-sm# (proxy [SecurityManager] []
                   (checkPermission [p#])
                   (checkExit [s#] (throw (SecurityException.))))]
     (System/setSecurityManager ^SecurityManager new-sm#)
     (try ~@body
          (catch SecurityException e#)
          (finally (System/setSecurityManager old-sm#)))))
