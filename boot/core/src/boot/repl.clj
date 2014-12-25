(ns boot.repl)

(def ^:dynamic *default-dependencies*
  (atom '[[org.clojure/tools.nrepl "0.2.6" :exclusions [[org.clojure/clojure]]]]))

(def ^:dynamic *default-middleware*
  (atom ['boot.from.io.aviso.nrepl/pretty-middleware]))

