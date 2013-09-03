{:dependencies [[tailrecursion/javelin "1.0.0-SNAPSHOT"]]
 :directories #{"test"}
 :main tailrecursion.boot-test/main
 :tasks
 {:foo
  {:dependencies [[alandipert/enduro "1.1.2"]]
   :main
   (do
     (require '[clojure.pprint :refer [pprint]])
     (fn [env] (println "foo!") (pprint env)))}
  :bar
  {:main
   (do
     (require '[clojure.pprint :refer [pprint]])
     (fn [env] (println "bar!") (pprint env)))}
  :baz
  {:main tailrecursion.boot-test/main}}}
