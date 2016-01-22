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
  "Return whether we should colorize output on this system. This is
  true, unless we're on Windows, where this is false. The default
  console on Windows does not interprete ansi escape codes. The
  default can be overriden by setting the environment variable
  BOOT_COLOR=1 or BOOT_COLOR=yes to turn it on or any other value to
  turn it off."
  []
  (or (#{"1" "yes"} (boot.App/config "BOOT_COLOR")) (not (boot.App/isWindows))))

(def ^:dynamic *verbosity*
  "Atom containing the verbosity level, 1 is lowest, 3 highest. Level 2
  corresponds to the -v boot option, level 3 to -vv, etc.
  
  Levels:

    1.  Print INFO level messages or higher, colorize and prune stack traces
        for maximum readability.
    2.  Print DEBUG level messages or higher, don't colorize stack traces and
        prune some trace elements for improved readablility.
    3.  Print DEBUG level messages or higher, don't colorize stack traces and
        include full traces with no pruning."
  (atom 1))

(def ^:dynamic *colorize?*
  "Atom containing the value that determines whether ANSI colors escape codes
  will be printed with boot output."
  (atom false))

(defn- print*
  [verbosity color args]
  (when (>= @*verbosity* verbosity)
    (binding [*out* *err*]
      (print ((or color identity) (apply format args)))
      (flush))))

(defn dbug
  "Print DEBUG level message. Arguments of the form fmt & args suitable for
  passing to clojure.core/format.

  Note that boot.util/*verbosity* in a pod needs to be altered AFTER pod
  creation or log level won't be affected."
  [& more]
  (print* 2 ansi/bold-cyan   more))

(defn info
  "Print INFO level message. Arguments of the form fmt & args suitable for
  passing to clojure.core/format.

  Note that boot.util/*verbosity* in a pod needs to be altered AFTER pod
  creation or log level won't be affected."
  [& more]
  (print* 1 ansi/bold        more))

(defn warn
  "Print WARNING level message. Arguments of the form fmt & args suitable for
  passing to clojure.core/format.

  Note that boot.util/*verbosity* in a pod needs to be altered AFTER pod
  creation or log level won't be affected."
  [& more]
  (print* 1 ansi/bold-yellow more))

(defn fail
  "Print ERROR level message. Arguments of the form fmt & args suitable for
  passing to clojure.core/format.

  Note that boot.util/*verbosity* in a pod needs to be altered AFTER pod
  creation or log level won't be affected."
  [& more]
  (print* 1 ansi/bold-red    more))

(defn warn-deprecated
  "Print WARNING level message. Arguments of the form fmt & args suitable for
  passing to clojure.core/format. Respects the BOOT_WARN_DEPRECATED environment
  variable, which if set to no suppresses these messages.

  Note that boot.util/*verbosity* in a pod needs to be altered AFTER pod
  creation or log level won't be affected."
  [& args]
  (when-not (= "no" (boot.App/config "BOOT_WARN_DEPRECATED"))
    (apply warn args)))

(defmacro with-semaphore
  "Acquires a permit from the Semaphore sem, blocking if necessary, and then
  evaluates the body expressions, returning the result. In all cases the permit
  will be released before returning."
  [sem & body]
  `(let [sem# ~sem]
     (.acquire sem#)
     (dbug "Acquired %s...\n" sem#)
     (try ~@body
          (finally (.release sem#)
                   (dbug "Released %s...\n" sem#)))))

(defmacro with-semaphore-noblock
  "Attempts to acquire a permit from the Semaphore sem. If successful the body
  expressions are evaluated and the result returned. In all cases the permit
  will be released before returning."
  [sem & body]
  `(let [sem# ~sem]
     (when (.tryAcquire sem#)
       (dbug "Acquired %s...\n" sem#)
       (try ~@body
            (finally (.release sem#)
                     (dbug "Released %s...\n" sem#))))))

(defmacro with-let
  "Binds resource to binding and evaluates body. Then, returns resource. It's
  a cross between doto and with-open."
  [[binding resource] & body]
  `(let [ret# ~resource ~binding ret#] ~@body ret#))

(defmacro while-let
  "Repeatedly executes body while test expression is true. Test expression is
  bound to binding."
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
  "A cross between doto and doseq. For example:
  
      (-> (System/-err)
          (dotoseq [i (range 0 100)]
            (.printf \"i = %d\\n\" i))
          (.checkError))"
  [obj seq-exprs & body]
  `(let [o# ~obj] (doseq ~seq-exprs (doto o# ~@body)) o#))

(defmacro with-resolve
  "Given a set of binding pairs bindings, resolves the righthand sides requiring
  namespaces as necessary, binds them, and evaluates the body."
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
  "Evaluates expr within a try/catch and returns default (or nil if default is
  not given) if an exception is thrown, otherwise returns the result."
  [expr & [default]]
  `(try ~expr (catch Throwable _# ~default)))

(defmacro with-rethrow
  "Evaluates expr. If an exception is thrown it is wrapped in an exception with
  the given message and the original exception as the cause, and the wrapped
  exception is rethrown."
  [expr message]
  `(try ~expr (catch Throwable e# (throw (Exception. ~message e#)))))

(defmacro exit-error
  "Binds *out* to *err*, evaluates the body, and exits with non-zero status.

  Notes:
  * This is the preferred method for returning an exit code != 0, this
  method returns 1.
  * This macro does not call System.exit(), because this instance of boot
  may be nested in another boot instance. Instead a special method on boot.App
  is called which handles the exit behavior (calling shutdown hooks etc.)."
  [& body]
  `(binding [*out* *err*]
     ~@body
     (throw (boot.App$Exit. (str 1)))))

(defmacro exit-ok
  "Evaluates the body, and exits with non-zero status.

  Notes:
  * Boot's main explicitly wraps user tasks in exit-ok so that in general
  it is not necessary to call it for exiting with 0.
  * This macro does not call System.exit(), because this instance of boot
  may be nested in another boot instance. Instead a special method on boot.App
  is called which handles the exit behavior (calling shutdown hooks etc.)."
  [& body]
  `(try
     ~@body
     (throw (boot.App$Exit. (str 0)))
     (catch Throwable e#
       (if (instance? boot.App$Exit e#)
         (throw e#)
         (exit-error (print-ex e#))))))

(defmacro with-err-str
  "Evaluates exprs in a context in which *err* is bound to a fresh StringWriter.
  Returns the string created by any nested printing calls.

  [1]: http://stackoverflow.com/questions/17314128/get-stacktrace-as-string"
  [& body]
  `(let [s# (new java.io.StringWriter)]
     (binding [*err* s#] ~@body (str s#))))

(defn print-ex
  "Print exception to *err* as appropriate for the current *verbosity* level."
  [ex]
  (case @*verbosity*
    0 nil
    1 (pretty/write-exception *err* ex
        {:properties true :filter repl/standard-frame-filter})
    2 (pretty/write-exception *err* ex {:properties true})
    (binding [*out* *err*] (.printStackTrace ex))))

(defn print-tree
  "Pretty prints tree, with the optional prefixes prepended to each line. The
  output is similar to the tree(1) unix program.

  A tree consists of a graph of nodes of the format [<name> <nodes>], where
  <name> is a string and <nodes> is a set of nodes (the children of this node).
  
  Example:
  
      (util/print-tree [[\"foo\" #{[\"bar\" #{[\"baz\"]}]}]] [\"--\" \"XX\"])

  prints:

      --XX└── foo
      --XX    └── bar
      --XX        └── baz

  You can also pass a function to generate the prefix instead of a
  collection of prefixes that will be passed the node. Passing a
  function to generate the string representation of the node itself is
  also an option."
  [tree & [prefixes node-fn]]
  (let [prefix-fn (if (fn? prefixes)
                    prefixes
                    (constantly (ansi/blue (apply str prefixes))))
        node-fn (if node-fn node-fn str)]
    (loop [[[node branch] & more] (seq tree)]
      (when node
        (let [pfx (cond (not prefixes) "" (seq more) "├── " :else "└── ")
              pfx (str (prefix-fn node) (ansi/blue pfx))]
          (println (str pfx (node-fn node))))
        (when branch
          (let [pfx (cond (not prefixes) "" (seq more) "│   " :else "    ")]
            (print-tree branch #(str (prefix-fn %) (ansi/blue pfx)) node-fn)))
        (recur more)))))

(defn path->ns
  "Returns the namespace symbol corresponding to the source file path."
  [path]
  (-> path file/split-path (#(string/join "." %))
    (.replace \_ \-) (.replaceAll "\\.clj$" "") symbol))

(defn auto-flush
  "Returns a PrintWriter suitable for binding to *out* or *err*. This writer
  will call .flush() on every write, ensuring that all output is flushed before
  boot exits, even if output was written from a background thread."
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
  "Extracts the group-id and artifact-id from sym, using the convention that
  non-namespaced symbols have group-id the same as artifact-id."
  [sym]
  (let [[group artifact] ((juxt namespace name) sym)]
    [(or group artifact) artifact]))

(defn dep-as-map
  "Returns the given dependency vector as a map with :project and :version
  keys plus any modifiers (eg. :scope, :exclusions, etc)."
  [[project version & kvs]]
  (let [d {:project project :version version}]
    (merge {:scope "compile"}
      (if-not (seq kvs) d (apply assoc d kvs)))))

(defn jarname
  "Generates a friendly name for the jar file associated with the given project
  symbol and version."
  [project version]
  (str (second (extract-ids project)) "-" version ".jar"))

(defn index-of
  "Find the index of val in the sequential collection v, or nil if not found."
  [v val]
  (ffirst (filter (comp #{val} second) (map vector (range) v))))

(defn bind-syms
  "Returns the names bound in the given destructuring form."
  [form]
  (let [sym? #(and (symbol? %) (not= '& %))]
    (->> form (tree-seq coll? seq) (filter sym?) distinct)))

(defn pp*
  "Pretty-print expr using the code dispatch."
  [expr]
  (pprint/write expr :dispatch pprint/code-dispatch))

(defn pp-str
  "Pretty-print expr to a string using the code dispatch."
  [expr]
  (with-out-str (pp* expr)))

(defn read-string-all
  "Reads all forms from the string s, by wrapping in parens before reading."
  [s]
  (read-string (str "(" s "\n)")))

(def ^:dynamic *sh-dir*
  "The directory to use as CWD for shell commands."
  nil)

(defn sh
  "Evaluate args as a shell command, asynchronously, and return a thunk which
  may be called to block on the exit status. Output from the shell is streamed
  to stdout and stderr as it is produced."
  [& args]
  (let [args (remove nil? args)]
    (assert (every? string? args))
    (let [opts (into [:redirect-err true] (when *sh-dir* [:dir *sh-dir*]))
          proc (apply conch/proc (concat args opts))]
      (future (conch/stream-to-out proc :out))
      #(.waitFor (:process proc)))))

(defn dosh
  "Evaluates args as a shell command, blocking on completion and throwing an
  exception on non-zero exit status. Output from the shell is streamed to
  stdout and stderr as it is produced."
  [& args]
  (let [args (remove nil? args)]
    (assert (every? string? args))
    (let [status ((apply sh args))]
      (when-not (= 0 status)
        (throw (Exception. (-> "%s: non-zero exit status (%d)"
                               (format (first args) status))))))))

(defmacro without-exiting
  "Evaluates body in a context where System/exit doesn't work. Returns result
  of evaluating body, or nil if code in body attempted to exit."
  [& body]
  `(let [old-sm# (System/getSecurityManager)
         new-sm# (proxy [SecurityManager] []
                   (checkPermission [p#])
                   (checkExit [s#] (throw (SecurityException.))))]
     (System/setSecurityManager ^SecurityManager new-sm#)
     (try ~@body
          (catch SecurityException e#)
          (finally (System/setSecurityManager old-sm#)))))
