{:dependencies [[tailrecursion/javelin "1.0.0-SNAPSHOT"]]
 :directories #{"test"}
 :tasks
 {:foo
  {:dependencies [[alandipert/enduro "1.1.2"]]
   :main
   (do
     (require '[clojure.pprint :refer [pprint]])
     (fn [env & args]
       (println "foo!")
       (printf "args: %s\n" (pr-str args))
       (pprint env)))}
  :bar
  {:main
   (do
     (require '[clojure.pprint :refer [pprint]])
     (fn [env]
       (println "bar!")
       (pprint env)))}
  :baz
  {:main tailrecursion.boot-test/main}}}
