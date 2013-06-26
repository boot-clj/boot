#boot/configuration
{:boot {:coordinates #{[reply "0.2.0"]}
        :directories #{"test"}}
 :pom {:project tailrecursion/boot-test
       :version "1.0.0-SNAPSHOT"}}

(require 'tailrecursion.boot.middleware.pom)

(boot/dispatch-cli)

;;; boot pom -> "Wrote /home/alan/projects/boot/pom.xml"
;;; boot pom out.xml -> "Wrote /home/alan/projects/boot/out.xml"
