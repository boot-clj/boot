(defproject boot/core "2.0.0-SNAPSHOT"
  :aot [#"^(?!boot\.repl-server).*$"]
  :dependencies [[org.clojure/clojure "1.6.0"          :scope "provided"]
                 [boot/base           "2.0.0-SNAPSHOT" :scope "provided"]
                 [boot/pod            "2.0.0-SNAPSHOT" :scope "compile"]])

