(boot/install
 '{:coordinates #{[alandipert/interpol8 "0.0.3"]}})

(println "arguments: " *command-line-args*)

(require '[alandipert.interpol8 :refer [interpolating]])

(println
 (interpolating
  (let [x (+ 1 1)]
    "1+1=#{x}")))

(prn (boot/make-request))
