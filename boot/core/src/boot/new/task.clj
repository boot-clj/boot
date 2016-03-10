(ns boot.new.task
  "Generate a Boot task project."
  (:require [boot.new.templates :refer [renderer year date project-name
                                        ->files sanitize-ns name-to-path
                                        multi-segment]]))

(defn task
  "A Boot task template."
  [name]
  (let [render (renderer "task")
        main-ns (multi-segment (sanitize-ns name))
        data {:raw-name name
              :name (project-name name)
              :namespace main-ns
              :nested-dirs (name-to-path main-ns)
              :year (year)
              :date (date)}]
    (println (str "Generating a fresh Boot task called " name "."))
    (->files data
             ["build.boot" (render "build.boot" data)]
             ["README.md" (render "README.md" data)]
             [".gitignore" (render "gitignore" data)]
             [".hgignore" (render "hgignore" data)]
             ["src/{{nested-dirs}}.clj" (render "name.clj" data)]
             ["LICENSE" (render "LICENSE" data)]
             ["CHANGELOG.md" (render "CHANGELOG.md" data)])))
