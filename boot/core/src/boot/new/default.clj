(ns boot.new.default
  "Generate a library project."
  (:require [boot.new.templates :refer [renderer year date project-name
                                        ->files sanitize-ns name-to-path
                                        multi-segment]]))

(defn default
  "A general project template for libraries.

Accepts a group id in the project name: `boot new -n foo.bar/baz`"
  [name]
  (let [render (renderer "default")
        main-ns (multi-segment (sanitize-ns name))
        data {:raw-name name
              :name (project-name name)
              :namespace main-ns
              :nested-dirs (name-to-path main-ns)
              :year (year)
              :date (date)}]
    (println "Generating a project called" name "based on the 'default' template.")
    (println "The default template is intended for library projects, not applications.")
    (->files data
             ["build.boot" (render "build.boot" data)]
             ["README.md" (render "README.md" data)]
             ["doc/intro.md" (render "intro.md" data)]
             [".gitignore" (render "gitignore" data)]
             [".hgignore" (render "hgignore" data)]
             ["src/{{nested-dirs}}.clj" (render "core.clj" data)]
             ["test/{{nested-dirs}}_test.clj" (render "test.clj" data)]
             ["LICENSE" (render "LICENSE" data)]
             ["CHANGELOG.md" (render "CHANGELOG.md" data)]
             "resources")))
