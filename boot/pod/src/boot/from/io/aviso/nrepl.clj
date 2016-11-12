(ns boot.from.io.aviso.nrepl
  "nREPL middleware to enable pretty exception reportinging in the REPL."
  {:boot/from :AvisoNovate/pretty:0.1.11}
  (:use [boot.from.io.aviso.repl]))

(defn pretty-middleware
  "nREPL middleware that simply ensures that pretty exception reporting is installed."
  [handler]
  (install-pretty-exceptions)
  handler)
