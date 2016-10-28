(ns boot.from.io.aviso.repl
  "Utilities to assist with REPL-oriented development"
  {:boot/from :AvisoNovate/pretty:0.1.30}
  (:use [boot.from.io.aviso.exception])
  (:require [clojure
             [main :as main]
             [repl :as repl]
             [stacktrace :as st]])
  (:import [clojure.lang RT]))

(defn- reset-var!
  [v override]
  (alter-var-root v (constantly override)))

(defn- write
  [e options]
  (print (format-exception e options))
  (flush))

(defn pretty-repl-caught
  "A replacement for `clojure.main/repl-caught` that prints the exception to `*err*`, without a stack trace or properties."
  [e]
  (write e {:frame-limit 0 :properties false}))

(defn uncaught-exception-handler
  "Creates a reified UncaughtExceptionHandler that prints the formatted exception to `*err*`."
  {:added "0.1.18"}
  []
  (reify Thread$UncaughtExceptionHandler
    (uncaughtException [_ _ t]
      (binding [*out* *err*]
        (printf "Uncaught exception in thread %s:%n" (-> (Thread/currentThread) .getName))
        (write-exception t)))))


(defn pretty-pst
  "Used as an override of `clojure.repl/pst` but uses pretty formatting. The optional parameter must be an exception
  (it can not be a depth, as with the standard implementation of `pst`)."
  ([] (pretty-pst *e))
  ([e] (write e nil)))

(defn pretty-print-stack-trace
  "Replacement for `clojure.stracktrace/print-stack-trace` and `print-cause-trace`. These functions are used by `clojure.test`."
  ([tr] (pretty-print-stack-trace tr nil))
  ([tr n]
   (println)
   (write tr {:frame-limit n})))

(defn install-pretty-exceptions
  "Installs an override that outputs pretty exceptions when caught by the main REPL loop. Also, overrides
  `clojure.repl/pst`, `clojure.stacktrace/print-stack-trace`, `clojure.stacktrace/print-cause-trace`.

  In addition, installs a [[uncaught-exception-handler]] so that uncaught exceptions in non-REPL threads
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
