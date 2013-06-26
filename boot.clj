#boot/configuration
{:boot {:coordinates #{[reply "0.2.0"]}
        :directories #{"test"}}
 :pom {:project tailrecursion/boot
       :version "1.0.0-SNAPSHOT"
       :description "Boot rules"}}

(def one 1)

(defn add [x y] (+ x y))

;;; boot pom -> "POM!"
;;; boot add one 1 -> 2
