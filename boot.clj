{:dependencies [[tailrecursion/boot.task "0.1.0-SNAPSHOT"]]
 :require-tasks #{[tailrecursion.boot.task :as t]} 
 :directories #{"test"}
 :tasks
 {:test
  {:dependencies [[alandipert/enduro "1.1.2"]]
   :main [tailrecursion.boot-test/main]}}}
