(ns boot.from.io.aviso.columns
  {:boot/from :AvisoNovate/pretty
   :doc "Assistance for formatting data into columns. Each column has a width, and data within the column
  may be left or right justified. Generally, columns are sized to the largest item in the column.
  When a value is provided in a column, it may be associated with an explicit width which is helpful
  when the value contains non-printing characters (such as those defined in the `io.aviso.ansi` namespace)."}
  (:require
    [clojure.string :as str]
    [boot.from.io.aviso
     [ansi :as ansi]
     [writer :as w]]))

(defn- indent
  "Indents sufficient to pad the column value to the column width."
  [writer indent-amount]
  (w/write writer (apply str (repeat indent-amount \space))))

(defn- truncate
  [justification ^String string amount]
  (cond
    (nil? amount) string
    (zero? amount) string
    (= :left justification) (.substring string 0 (- (.length string) amount))
    (= :right justification) (.substring string amount)
    :else string))

(defn- write-none-column [writer current-indent column-value]
  (loop [first-line true
         lines (-> column-value str str/split-lines)]
    (when-not (empty? lines)
      (when-not first-line
        (w/writeln writer)
        (indent writer current-indent))
      (w/write writer (first lines))
      (recur false (rest lines))))
  ;; :none columns don't have an explicit width, so just return the current indent.
  ;; it shouldn't matter because :none should be the last consuming column.
  current-indent)

(defn- make-column-writer
  [justification width]
  (if (= :none justification)
    write-none-column
    (fn column-writer [writer current-indent column-value]
      (let [value-string (str column-value)
            value-width (ansi/visual-length value-string)
            indent-amount (max 0 (- width value-width))
            truncate-amount (max 0 (- value-width width))
            ;; This isn't aware of ANSI escape codes and will do the wrong thing when truncating a string with
            ;; such codes.
            truncated (truncate justification value-string truncate-amount)]
        (if (= justification :right)
          (indent writer indent-amount))
        (w/write writer truncated)
        (if (= justification :left)
          (indent writer indent-amount)))
      ;; Return the updated indent amount; a :none column doesn't compute
      (+ current-indent width))))

(defn- fixed-column
  [fixed-value]
  (let [value-length (ansi/visual-length fixed-value)]
    (fn [writer indent column-data]
      (w/write writer fixed-value)
      [(+ indent value-length) column-data])))

(defn- dynamic-column
  "Returns a function that consumes the next column data value and delegates to a column writer function
  to actually write the output for the column."
  [column-writer]
  (fn [writer indent [column-value & remaining-column-values]]
    [(column-writer writer indent column-value) remaining-column-values]))

(defn- nil-column
  "Does nothing and returns the indent and column data unchanged."
  [writer indent column-values]
  [indent column-values])

(defn- column-def-to-fn [column-def]
  (cond
    (string? column-def) (fixed-column column-def)
    (number? column-def) (-> (make-column-writer :left column-def) dynamic-column)
    (nil? column-def) nil-column
    (= :none column-def) (-> (make-column-writer :none nil) dynamic-column)
    :else (-> (apply make-column-writer column-def) dynamic-column)))

(defn format-columns
  "Converts a number of column definitions into a formatting function. Each column definition may be:

  - a string, to indicate a non-consuming column that outputs a fixed value. This is often just a space
  character or two, to seperate columns.
  - a number, to indicate a consuming column that outputs a left justified value of the given width.
  - a vector containing a keyword and a number; the number is the width, the keyword is the justification.
  - `:none`, to indicate a consuming column with no explicit width
  - nil, which is treated like an empty string

  With explicit justification, the keyword may be `:left`, `:right`, or `:none`.

  `:left`
  : Pads the column with spaces after the value. Truncates long values from the right, displaying
    initial character and discarding trailing characters.

  `:right`
  : Pads the column with spaces before the value. Truncates long values from the left.

  `:none`
  : Does not pad with spaces at all, and should only be used in the final column.

  Generally speaking, truncation does not occur because columns are sized to fit their contents.

  A column width is required for `:left` or `:right`. Column width is optional and ignored for `:none`.

  Values are normally string, but any type is accepted and will be converted to a string.
  This code is aware of ANSI codes and ignores them to calculate the length of a value for formatting and
  identation purposes.

  There will likely be problems if a long string with ANSI codes is truncated, however.

  The returned function accepts a [[StringWriter]] and the column values and writes each column value, with appropriate
  padding, to the [[StringWriter]]."
  [& column-defs]
  (let [column-fns (map column-def-to-fn column-defs)]
    (fn [writer & column-values]
      (loop [current-indent 0
             column-fns column-fns
             values column-values]
        (if (empty? column-fns)
          (w/writeln writer)
          (let [cf (first column-fns)
                [new-indent remaining-values] (cf writer current-indent values)]
            (recur new-indent (rest column-fns) remaining-values)))))))

(defn write-rows
  "A convienience for writing rows of columns using a prepared column formatter.

  - writer - [[StringWriter]] target of output
  - column-formatter - formatter function created by format-columns
  - extractors - seq of functions that extract a column value from a row; typically a keyword when the row is a map
  - row-data - a seq of row data"
  [writer column-formatter extractors row-data]
  (let [column-data-extractor (apply juxt extractors)]
    (doseq [row row-data]
           (apply column-formatter writer (column-data-extractor row)))))

(defn max-length
  "Find the maximum length of the strings in the collection, based on their visual length (that is,
  omitting ANSI escape codes)."
  [coll]
  (if (empty? coll)
    0
    (apply max (map ansi/visual-length coll))))

(defn max-value-length
  "A convinience for computing the maximum length of one string property from a collection of values.

  - coll - collection of values
  - key - key that is passed one value and returns the property, typically a keyword when the values are maps"
  [coll key]
  (max-length (map key coll)))

