(ns boot.new.{{name}}
  (:require [boot.new.templates :refer [renderer name-to-path ->files]]))

(def render (renderer "{{name}}"))

(defn {{name}}
  "FIXME: write documentation"
  [name]
  (let [data {:name name
              :sanitized (name-to-path name)}]
    (println "Generating fresh 'boot new' {{name}} project.")
    (->files data
             ["src/{{placeholder}}/foo.clj" (render "foo.clj" data)])))
