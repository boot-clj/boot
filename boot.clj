#boot/configuration
{:boot {:coordinates #{[reply "0.2.0"]}
        :directories #{"test"}}
 :pom {:project tailrecursion/boot
       :version "1.0.0-SNAPSHOT"
       :description "Boot rules"}}

(def one 1)

(def two 2)

(defn add [x y] (+ x y))
