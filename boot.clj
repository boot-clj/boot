#boot/version "1.5.1-SNAPSHOT"

(boot/install
 '{:coordinates #{[alandipert/interpol8 "0.0.3"]}})

(boot/add #{"test"})

(println "arguments: " *command-line-args*)

(require '[alandipert.interpol8 :refer [interpolating]])
(require 'foo)

(println (interpolating (let [x (+ 1 1)] "1+1=#{x}")))
(println "foo/x = " foo/x)

(prn (boot/make-request))
