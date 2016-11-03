(ns boot.from.io.aviso.writer
  "The StringWriter protocol is used as the target of any written output."
  {:boot/from :AvisoNovate/pretty:0.1.30}
  (:import
    [java.io Writer]))

(defprotocol StringWriter
  "May receive strings, which are printed, or stored.

  `StringWriter` is extended onto `java.lang.Appendable`, a common interface implemented by both `PrintWriter` and `StringBuilder` (among
  many others)."

  (write-string [this string] "Writes the string to the `StringWriter`.")
  (flush-writer [this] "Flushes output to the `StringWriter`, where supported."))

(extend-protocol StringWriter
  StringBuilder
  (write-string [this ^CharSequence string] (.append this string))
  (flush-writer [this] nil)

  Writer
  (write-string [this ^CharSequence string] (.append this string))
  (flush-writer [this] (.flush this)))

(def eol
  "End-of-line terminator, platform specific."
  (System/getProperty "line.separator"))

(defn write
  "Constructs a string from the values (with no seperator) and writes the string to the StringWriter.

  This is used to get around the fact that protocols do not support varadic parameters."
  ([writer value]
   (write-string writer (str value)))
  ([writer value & values]
   (write writer value)
   (doseq [value values]
          (write writer value))))

(defn writeln
  "Constructs a string from the values (with no seperator) and writes the string to the `StringWriter`,
  followed by an end-of-line terminator, then flushes the writer."
  ([writer]
   (write-string writer eol)
   (flush-writer writer))
  ([writer & values]
   (apply write writer values)
   (writeln writer)))

(defn writef
  "Writes formatted data."
  [writer fmt & values]
  (write-string writer (apply format fmt values)))

(defn into-string
  "Creates a `StringBuilder` and passes that as the first parameter to the function, along with the other parameters.

  Returns the value of the `StringBuilder` after invoking the function."
  [f & params]
  (let [sb (StringBuilder. 2000)]
    (apply f sb params)
    (.toString sb)))

