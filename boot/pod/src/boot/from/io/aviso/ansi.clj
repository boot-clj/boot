(ns boot.from.io.aviso.ansi
  "Help with generating textual output that includes ANSI escape codes for formatting."
  {:boot/from :AvisoNovate/pretty:0.1.33}
 (:import
    [java.util.regex Pattern])
  (:require
    [clojure.string :as str]))

(def ^:const csi
  "The control sequence initiator: `ESC [`"
  "\u001b[")

;; select graphic rendition
(def ^:const sgr
  "The Select Graphic Rendition suffix: m"
  "m")

(def ^:const
  reset-font
  "Resets the font, clearing bold, italic, color, and background color."
  (str csi sgr))

(defmacro ^:private def-sgr-const
  "Utility for defining a font-modifying constant."
  [symbol-name color-name & codes]
  `(def ~(vary-meta (symbol symbol-name) assoc :const true)
     ~(format "Constant for ANSI code to enable %s text." color-name)
     (str csi ~(str/join ";" codes) sgr)))

(defmacro ^:private def-sgr-fn
  "Utility for creating a function that enables some combination of SGR codes around some text, but resets
  the font after the text."
  [fn-name color-name & codes]
  (let [arg 'text]
    `(defn ~(symbol fn-name)
       ~(format "Wraps the provided text with ANSI codes to render as %s text." color-name)
       [~arg]
       (str csi ~(str/join ";" codes) sgr ~arg reset-font))))

;;; Define functions and constants for each color. The functions accept a string
;;; and wrap it with the ANSI codes to set up a rendition before the text,
;;; and reset the rendition fully back to normal after.
;;; The constants enable the rendition, and require the reset-font value to
;;; return to normal.
;;; For each color C:
;;; - functions:
;;;   - C: change text to that color (e.g., "green")
;;;   - C-bg: change background to that color (e.g., "green-bg")
;;;   - bold-C: change text to bold variation of color (e.g., "bold-green")
;;;   - bold-C-bg: change background to bold variation of color (e.g., "bold-green-bg")
;;; - constants
;;;   - C-font: enable text in that color (e.g., "green-font")
;;;   - C-bg-font: enable background in that color (e.g., "green-bg-font")
;;;   - bold-C-font; enable bold text in that color (e.g., "bold-green-font")
;;;   - bold-C-bg-font; enable background in that bold color (e.g., "bold-green-bg-font")

(defmacro ^:private define-colors
  []
  `(do
     ~@(map-indexed
         (fn [index color-name]
           `(do
              (def-sgr-fn ~color-name ~color-name ~(+ 30 index))
              (def-sgr-fn ~(str color-name "-bg") ~(str color-name " background") ~(+ 40 index))
              (def-sgr-fn ~(str "bold-" color-name) ~(str "bold " color-name) 1 ~(+ 30 index))
              (def-sgr-fn ~(str "bold-" color-name "-bg") ~(str "bold " color-name " background") 1 ~(+ 40 index))
              (def-sgr-const ~(str color-name "-font") ~color-name ~(+ 30 index))
              (def-sgr-const ~(str color-name "-bg-font") ~(str color-name " background") ~(+ 40 index))
              (def-sgr-const ~(str "bold-" color-name "-font") ~(str "bold " color-name) 1 ~(+ 30 index))
              (def-sgr-const ~(str "bold-" color-name "-bg-font") ~(str "bold " color-name " background") 1 ~(+ 40 index))))
         ["black" "red" "green" "yellow" "blue" "magenta" "cyan" "white"])))

(define-colors)

;; ANSI defines quite a few more, but we're limiting to those that display properly in the
;; Cursive REPL.

(defmacro ^:private define-fonts
  []
  `(do
     ~@(for [[font-name code] [['bold 1]
                               ['italic 3]
                               ['inverse 7]]]
         `(do
            (def-sgr-fn ~font-name ~font-name ~code)
            (def-sgr-const ~(str font-name "-font") ~font-name ~code)))))

(define-fonts)

(def ^:const ^:private ansi-pattern (Pattern/compile "\\e\\[.*?m"))

(defn ^String strip-ansi
  "Removes ANSI codes from a string, returning just the raw text."
  [string]
  (str/replace string ansi-pattern ""))

(defn visual-length
  "Returns the length of the string, with ANSI codes stripped out."
  [string]
  (-> string strip-ansi .length))