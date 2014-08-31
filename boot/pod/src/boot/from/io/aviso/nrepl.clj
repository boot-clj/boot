(ns boot.from.io.aviso.nrepl
  {:boot/from :AvisoNovate/pretty
   :doc "nREPL middleware to enable pretty exception reportinging in the REPL."}
  (:use [boot.from.io.aviso.repl]))

(defn pretty-middleware
  "nREPL middleware that simply ensures that pretty exception reporting is installed."
  [handler]
  (install-pretty-exceptions)
  handler)
