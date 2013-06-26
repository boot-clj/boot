#boot/configuration
{:boot {:coordinates #{[reply "0.2.0"]}
        :directories #{"src"}}
 :pom {:project tailrecursion/boot
       :version "1.0.0-SNAPSHOT"
       :description "Boot rules"}}

(defn show []
  (prn @boot/project))
