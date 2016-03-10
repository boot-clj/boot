(ns {{namespace}}
  "Example tasks showing various approaches."
  {:boot/export-tasks true}
  (:require [boot.core :as boot :refer [deftask]]
            [boot.util :as util]))

(deftask {{name}}-simple
  "I'm a simple task with only setup."
  [A arg ARG str "the task argument"]
  ;; merge environment etc
  (println "Simple task setup:" arg)
  identity)

(deftask {{name}}-pre
  "I'm a pre-wrap task."
  []
  ;; merge environment etc
  (println "Pre-wrap task setup.")
  (boot/with-pre-wrap fs
    (println "Pre-wrap: Run functions on fs. Next task will run with our result.")
    ;; return updated filesystem (boot/commit! updated-fs)
    fs))

(deftask {{name}}-post
  "I'm a post-wrap task."
  []
  ;; merge environment etc
  (println "Post-wrap task setup.")
  (boot/with-post-wrap fs
    (println "Post-wrap: Next task will run. Then we will run functions on its result (fs).")))

(deftask {{name}}-pass-thru
  "I'm a pass-thru task."
  []
  ;; merge environment etc
  (println "Pass-thru task setup.")
  (boot/with-pass-thru fs
    (println "Pass-thru: Run functions on filesystem (fs). Next task will run with the same fs.")))
