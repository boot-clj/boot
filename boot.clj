{:project         [foo/bar "1.1.0-SNAPSHOT"]
 :dependencies  #{[tailrecursion/jhoplon "1.1.0-SNAPSHOT"]
                  [alandipert/enduro "1.1.2"]}}

(use 'clojure.pprint)

(pprint @boot/env)
(println "hello world")
