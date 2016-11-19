(ns boot.from.io.aviso.repl
  "Utilities to assist with REPL-oriented development"
  {:boot/from :AvisoNovate/pretty:0.1.32}
  (:require
      [boot.from.io.aviso.exception :as e]
      [clojure.pprint :refer [pprint write]]
      [clojure.main :as main]
      [clojure.repl :as repl]
      [clojure.stacktrace :as st]
      [clojure.edn :as edn]
      [boot.from.io.aviso.writer :as writer])
    (:import
      (clojure.lang RT)
      (java.awt Toolkit)
      (java.awt.datatransfer Clipboard DataFlavor StringSelection)))

(defn- reset-var!
  [v override]
  (alter-var-root v (constantly override)))

(defn- print-exception
  [e options]
  (print (e/format-exception e options))
  (flush))

(defn pretty-repl-caught
  "A replacement for `clojure.main/repl-caught` that prints the exception to `*err*`, without a stack trace or properties."
  [e]
  (print-exception e {:frame-limit 0 :properties false}))

(defn uncaught-exception-handler
  "Creates a reified UncaughtExceptionHandler that prints the formatted exception to `*err*`."
  {:added "0.1.18"}
  []
  (reify Thread$UncaughtExceptionHandler
    (uncaughtException [_ _ t]
      (binding [*out* *err*]
        (printf "Uncaught exception in thread %s:%n" (-> (Thread/currentThread) .getName))
        (e/write-exception t)))))


(defn pretty-pst
  "Used as an override of `clojure.repl/pst` but uses pretty formatting."
  ([] (pretty-pst *e))
  ([e-or-depth]
   (if (instance? Throwable e-or-depth)
     (print-exception e-or-depth nil)
     (pretty-pst *e e-or-depth)))
  ([e depth] (print-exception e {:frame-limit depth})))

(defn pretty-print-stack-trace
  "Replacement for `clojure.stacktrace/print-stack-trace` and `print-cause-trace`. These functions are used by `clojure.test`."
  ([tr] (pretty-print-stack-trace tr nil))
  ([tr n]
   (println)
   (print-exception tr {:frame-limit n})))

(defn install-pretty-exceptions
  "Installs an override that outputs pretty exceptions when caught by the main REPL loop. Also, overrides
  `clojure.repl/pst`, `clojure.stacktrace/print-stack-trace`, `clojure.stacktrace/print-cause-trace`.

  In addition, installs an [[uncaught-exception-handler]] so that uncaught exceptions in non-REPL threads
  will be printed reasonably. See [[io.aviso.logging]] for a better handler, used when clojure.tools.logging
  is available.

  Caught exceptions do not print the stack trace; the pst replacement does."
  []
  ;; TODO: Not exactly sure why this works, because clojure.main/repl should be resolving the var to its contained
  ;; function, so the override should not be visible. I'm missing something.
  (reset-var! #'main/repl-caught pretty-repl-caught)
  (reset-var! #'repl/pst pretty-pst)
  (reset-var! #'st/print-stack-trace pretty-print-stack-trace)
  (reset-var! #'st/print-cause-trace pretty-print-stack-trace)

  ;; This is necessary for Clojure 1.8 and above, due to direct linking
  ;; (from clojure.test to clojure.stacktrace).
  (RT/loadResourceScript "clojure/test.clj")

  (Thread/setDefaultUncaughtExceptionHandler (uncaught-exception-handler))
  nil)

;; This could possibly be modified to work headless (at least on Mac OS X)
;; by using processes, pipes, and the pbcopy and pbpaste commands.

(defn ^:private ^Clipboard clipboard
  "Returns the current clipboard."
  []
  (.getSystemClipboard (Toolkit/getDefaultToolkit)))

(defn ^String copy
  "Copies the current contents of the Clipboard, returning its contents as a string.

  This makes use of AWT; it will throw java.awt.HeadlessException when AWT is not
  available, for example, when the JVM is launched with `-Djava.awt.headless=true`."
  {:added "0.1.32"}
  []
  (-> (clipboard)
      (.getContents nil)
      (.getTransferData DataFlavor/stringFlavor)))

(defn pretty-print
  "Pretty-prints the supplied object to a returned string.

  With no arguments, copies from the clipboard, parses as EDN, and prints the EDN data to `*out*`,
  returning nil."
  {:added "0.1.32"}
  ([]
   (-> (copy) edn/read-string pprint))
  ([object]
   (write object
          :stream nil
          :pretty true)))

(defn paste
  "Pastes a string in as the new content of the Clipboard.

  This can be helpful when, for example, pretty printing some EDN content from a log file
  before pasting it into some other editor."
  {:added "0.1.32"}
  [^String s]
  (.setContents (clipboard) (StringSelection. s) nil))

(defn format-exception
  "Passed the standard exception text and formats it using [[parse-exception]] and
  [[write-exception]], returning the formatted exception text.

  With no arguments, parses the clipboard text and prints the formatted exception
  to `*out*` (returning nil)."
  {:added "0.1.32"}
  ([]
   (e/write-exception* *out*
                       (-> (copy)
                           (e/parse-exception nil))
                       nil))
  ([text]
   (writer/into-string
     e/write-exception* (e/parse-exception text nil) nil)))