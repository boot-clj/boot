(boot/install
 '{:coordinates
   #{[alandipert/interpol8 "0.0.3"]}
   :repositories
   #{"http://repo1.maven.org/maven2/"
     "http://clojars.org/repo"}})

(println "arguments: " *command-line-args*)

(require '[alandipert.interpol8 :refer [interpolating]])

(println
 (interpolating
  (let [x (+ 1 1)]
    "1+1=#{x}")))

(println (boot/make-request))
