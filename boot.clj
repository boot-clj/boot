{:dependencies [[tailrecursion/javelin "1.0.0-SNAPSHOT"]]
 :directories #{"test"}
 :repositories #{"http://repo8.maven.org/maven2/"}
 :tasks
 {:test
  {:dependencies [[alandipert/enduro "1.1.2"]]
   :main tailrecursion.boot-test/main}}}
