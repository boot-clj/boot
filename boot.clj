#dependencies
{:coordinates
 #{[alandipert/interpol8 "0.0.3"]}
 :repositories
 #{"http://repo1.maven.org/maven2/"
   "http://clojars.org/repo"}}

(require '[alandipert.interpol8 :refer [interpolating]])

(interpolating
 (let [x (+ 1 1)]
   "1+1=#{x}"))
