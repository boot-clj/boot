(ns boot.repl
  (:require [boot.util :as util]))

(def ^:dynamic *default-dependencies*
  (atom '[[org.clojure/tools.nrepl "0.2.7" :exclusions [[org.clojure/clojure]]]]))

(def ^:dynamic *default-middleware*
  (atom (if-not @util/*colorize?* [] ['boot.from.io.aviso.nrepl/pretty-middleware])))

