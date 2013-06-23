(require '[tailrecursion.boot :as boot :refer [install]])
(println "args: " boot/*args*)
(install '[alandipert/interpol8 "0.0.3"])
(require '[alandipert.interpol8 :refer [interpolating]])
(interpolating
 (let [x (+ 1 1)]
   "1+1=#{x}"))
