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
(ns tailrecursion.boot.table.width
  (:require clojure.java.shell clojure.java.io clojure.string))

(declare get-initial-widths max-width-per-field actual-width auto-resize-widths
         detect-terminal-width command-exists?)

(def ^:dynamic *width* (delay (or (detect-terminal-width) 200)))
; TODO: calculate border lengths from styles
; " | " and "-+-" are inner borders
(def inner-border-length 3)
; "+-" and "-+" are outer borders
(def outer-border-length 2)

(defn get-widths [all-rows]
  (-> all-rows get-initial-widths vec auto-resize-widths))

(defn auto-resize-widths [widths]
  (loop [new-widths [] widths widths field-count (count widths) max-width @*width*]
    (if (empty? widths)
      new-widths
      (let [width (first widths)
            width-per-field (max-width-per-field max-width field-count)
            new-width (if (< width width-per-field) width width-per-field)]
        (recur
          (conj new-widths new-width)
          (rest widths)
          (- field-count 1)
          (- max-width (+ new-width inner-border-length)))))))

(defn get-initial-widths [all-rows]
  (map
    (fn [idx]
      (apply max (map #(count (str (nth % idx))) all-rows)))
    (range (count (first all-rows)))))

(defn- max-width-per-field [current-width field-count]
  (quot (actual-width current-width field-count) field-count))

; think of inner-borders as interposed between fields to understand why
; it's one less than the number of fields
(defn- actual-width [current-width field-count]
  (- current-width (+ (* 2 outer-border-length) (* (dec field-count) inner-border-length))))

(defn ensure-valid-width [arg]
  (if (integer? arg)
    (if (> arg 0) arg 100)
    arg))

(defn- stty-detect []
  (->> (clojure.java.shell/sh "/bin/sh" "-c" "stty -a < /dev/tty")
       :out
       (re-find #" (\d+) columns")
       vec
       second
       ((fn  [_ two] (if two (Integer. two))) :not-used)))

; since Java doesn't recognize COLUMNS by default you need to `export COLUMNS` for it
; be recognized
(defn- detect-terminal-width []
  (ensure-valid-width
   (cond
    (System/getenv "COLUMNS") (Integer. (System/getenv "COLUMNS"))
    (command-exists? "stty") (stty-detect))))

(defn- command-exists?
  "Determines if command exists in $PATH"
  [cmd]
  (some
    #(-> (str % "/" cmd) clojure.java.io/file .isFile)
    (-> (System/getenv "PATH") (clojure.string/split #":"))))
