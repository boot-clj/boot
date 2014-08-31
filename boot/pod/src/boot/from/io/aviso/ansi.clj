(ns boot.from.io.aviso.ansi
  {:boot/from :AvisoNovate/pretty
   :doc "Help with generating textual output that includes ANSI escape codes for formatting."}
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

(defn ^:private def-sgr-const
  "Utility for defining a font-modifying constant."
  [symbol-name & codes]
  (eval
    `(def ^:const ~(symbol symbol-name) ~(str csi (str/join ";" codes) sgr))))

(defn ^:private def-sgr-fn
  "Utility for creating a function that enables some combination of SGR codes around some text, but resets
  the font after the text."
  [fn-name & codes]
  (eval
    `(defn ~(symbol fn-name)
       [text#]
       (str ~(str csi (str/join ";" codes) sgr) text# reset-font))))

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

(doall
  (map-indexed (fn [index color-name]
                 (def-sgr-fn color-name (+ 30 index))
                 (def-sgr-fn (str color-name "-bg") (+ 40 index))
                 (def-sgr-fn (str "bold-" color-name) 1 (+ 30 index))
                 (def-sgr-fn (str "bold-" color-name "-bg") 1 (+ 40 index))
                 (def-sgr-const (str color-name "-font") (+ 30 index))
                 (def-sgr-const (str color-name "-bg-font") (+ 40 index))
                 (def-sgr-const (str "bold-" color-name "-font") 1 (+ 30 index))
                 (def-sgr-const (str "bold-" color-name "-bg-font") 1 (+ 40 index)))
               ["black" "red" "green" "yellow" "blue" "magenta" "cyan" "white"]))

;; ANSI defines quite a few more, but we're limiting to those that display properly in the
;; Cursive REPL.

(def-sgr-fn "bold" 1)
(def-sgr-fn "italic" 3)
(def-sgr-fn "inverse" 7)

(def ^:const bold-font (str csi 1 sgr))
(def ^:const italic-font (str csi 3 sgr))
(def ^:const inverse-font (str csi 7 sgr))

(def ^:const ^:private ansi-pattern (Pattern/compile "\\e\\[.*?m"))

(defn ^String strip-ansi
  "Removes ANSI codes from a string, returning just the raw text."
  [string]
  (str/replace string ansi-pattern ""))

(defn visual-length
  "Returns the length of the string, with ANSI codes stripped out."
  [string]
  (-> string strip-ansi .length))
