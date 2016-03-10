(ns boot.new.template
  (:require [boot.new.templates :refer [renderer sanitize year date ->files]]))

(defn template
  "A meta-template for 'boot new' templates."
  [name]
  (let [render (renderer "template")
        data {:name name
              :sanitized (sanitize name)
              :placeholder "{{sanitized}}"
              :year (year)
              :date (date)}]
    (println "Generating fresh 'boot new' template project.")
    (->files data
             ["README.md" (render "README.md" data)]
             ["build.boot" (render "build.boot" data)]
             [".gitignore" (render "gitignore" data)]
             [".hgignore" (render "hgignore" data)]
             ["src/boot/new/{{sanitized}}.clj" (render "temp.clj" data)]
             ["resources/boot/new/{{sanitized}}/foo.clj" (render "foo.clj")]
             ["LICENSE" (render "LICENSE" data)]
             ["CHANGELOG.md" (render "CHANGELOG.md" data)])))
