;;;
;;; Taken from here: https://github.com/cldwalker/table
;;;
;;; The MIT LICENSE
;;; 
;;; Copyright (c) 2012 Gabriel Horner
;;; 
;;; Permission is hereby granted, free of charge, to any person obtaining
;;; a copy of this software and associated documentation files (the
;;; "Software"), to deal in the Software without restriction, including
;;; without limitation the rights to use, copy, modify, merge, publish,
;;; distribute, sublicense, and/or sell copies of the Software, and to
;;; permit persons to whom the Software is furnished to do so, subject to
;;; the following conditions:
;;; 
;;; The above copyright notice and this permission notice shall be
;;; included in all copies or substantial portions of the Software.
;;; 
;;; THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
;;; EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
;;; MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
;;; NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
;;; LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
;;; OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
;;; WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
;;; 
(ns tailrecursion.boot.table.core
  (:require [tailrecursion.boot.table.width :as w])
  (:use [clojure.string :only [join]] ))

(declare style-for format-cell render-rows-with-fields escape-newline render-rows table-str)

(def ^:dynamic *style* :plain)
(def ^:private walls ["  " "   " "  "])
(def ^:private styles
  {
   :none  {:top ["  " "   " "  "], :middle ["  " "   " "  "] :bottom ["  " "   " "  "]
           :dash " " :header-walls walls :body-walls walls }
   :plain {:top ["+-" "-+-" "-+"], :middle ["+-" "-+-" "-+"] :bottom ["+-" "-+-" "-+"]
           :dash "-" :header-walls walls :body-walls walls }
   :org {:top ["|-" "-+-" "-|"], :middle ["|-" "-+-" "-|"] :bottom ["|-" "-+-" "-|"]
         :dash "-" :header-walls walls :body-walls walls }
   :unicode {:top ["┌─" "─┬─" "─┐"] :middle ["├─" "─┼─" "─┤"] :bottom ["└─" "─┴─" "─┘"]
             :dash "─" :header-walls ["│ " " │ " " │"] :body-walls ["│ " " ╎ " " │"] }
   :github-markdown {:top ["" "" ""] :middle ["|-" " | " "-|"] :bottom ["" "" ""]
                     :top-dash "" :dash "-" :bottom-dash "" :header-walls walls :body-walls walls }
   })

(defn table
   "Generates an ascii table for almost any input that fits in your terminal.
   Multiple table styles are supported.

   Options:

   * :sort   When set with field name, sorts by field name. When set to true
             sorts by first column. Default is false.
   * :fields An optional vector of fields used to control ordering of fields.
             Only works with rows that are maps.
   * :desc   When set to true, displays row count after table. Default is nil.
   * :style  Sets table style. Available styles are :plain, :org, :unicode and
             :github-markdown. Default is :plain."
  [& args]
  (println (apply table-str args)))

(defn table-str
  "Same options as table but returns table as a string"
  [ args & {:keys [style] :or {style :plain} :as options}]
  (binding [*style* style]
    (apply str (join "\n" (render-rows args (if (map? options) options {}))))))

(defn- generate-rows-and-fields
  "Returns rows and fields. Rows are a vector of vectors containing string cell values."
  [table options]
  (let [
       top-level-vec (not (coll? (first table)))
       fields (cond
               top-level-vec [:value]
               (map? (first table)) (or (:fields options)
                                        (distinct (vec (flatten (map keys table)))))
               (map? table) [:key :value]
               :else (first table))
       rows (cond
             top-level-vec (map #(vector %) table)
             (map? (first table)) (map #(map (fn [k] (get % k)) fields) table)
             (map? table) table
             :else (rest table))
       rows (map (fn [row] (map #(if (nil? %) "" (str %)) row)) rows)
       sort-opt (options :sort)
       rows (if (and sort-opt (some #{sort-opt} (conj fields true)))
              (sort-by
               #(nth % (if (true? sort-opt) 0 (.indexOf fields sort-opt)))
               rows) rows)
        rows (->> rows (map vec) (map (fn [row] (map escape-newline row))))]
    [rows fields]))

(defn- render-rows
  "Generates a list of formatted string rows given almost any input"
  [table options]
  (let [[rows fields] (generate-rows-and-fields table options)
        rendered-rows (render-rows-with-fields rows fields options)]
    (if (:desc options)
      (concat rendered-rows [(format "%s rows in set" (count rows))])
      rendered-rows)))

(defn- render-rows-with-fields [rows fields options]
  (let [
    headers (map #(if (keyword? %) (name %) (str %)) fields)
    widths (w/get-widths (cons headers rows))
    fmt-row (fn [row]
              (map-indexed
                (fn [idx string] (format-cell string (nth widths idx)))
                row))
    wrap-row (fn [row strings] (let [[beg mid end] strings] (join mid row)))
    headers (fmt-row headers)
    border-for (fn [section dash]
                 (let [dash-key (if (style-for dash) dash :dash)]
                 (wrap-row
                   (map #(apply str (repeat
                                      (.length (str %))(style-for dash-key))) headers)
                   (style-for section))))
    header (wrap-row headers (style-for :header-walls))] 
    (map #(wrap-row (fmt-row %) (style-for :body-walls)) rows)))

(defn- escape-newline [string]
  (clojure.string/replace string (str \newline) (char-escape-string \newline)))

(defn- style-for [k] (k (styles *style*)))

(defn format-cell [string width]
  (if (zero? width)
    ""
    (format
      (str "%-" width "." width "s")
      (if (> (count string) width)
        (str (.substring string 0 (- width 3)) "...")
        string))))
