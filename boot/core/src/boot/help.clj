(ns boot.help
  (:require [boot.core :as core]
            [boot.main :as main]
            [boot.task-helpers :as helpers]
            [boot.from.table.core :as table]
            [clojure.string :as string]))


(core/deftask help
  "Print usage info and list available tasks."
  []
  (core/with-pass-thru [_]
    (let [tasks (#'helpers/available-tasks 'boot.user)
          opts  (->> main/cli-opts (mapv (fn [[x y z]] ["" (str x " " y) z])))
          envs  [["" "BOOT_AS_ROOT"              "Set to 'yes' to allow boot to run as root."]
                 ["" "BOOT_CERTIFICATES"         "Specify certificate file paths."]
                 ["" "BOOT_CLOJARS_REPO"         "Specify the url for the 'clojars' Maven repo."]
                 ["" "BOOT_CLOJARS_MIRROR"       "Specify the mirror url for the 'clojars' Maven repo."]
                 ["" "BOOT_CLOJURE_VERSION"      "The version of Clojure boot will provide (1.8.0)."]
                 ["" "BOOT_CLOJURE_NAME"         "The artifact name of Clojure boot will provide (org.clojure/clojure)."]
                 ["" "BOOT_COLOR"                "Set to 'no' to turn colorized output off."]
                 ["" "BOOT_FILE"                 "Build script name (build.boot)."]
                 ["" "BOOT_GPG_COMMAND"          "System gpg command (gpg)."]
                 ["" "BOOT_HOME"                 "Directory where boot stores global state (~/.boot)."]
                 ["" "BOOT_WATCHERS_DISABLE"      "Set to 'yes' to turn off inotify/FSEvents watches."]
                 ["" "BOOT_JAVA_COMMAND"         "Specify the Java executable (java)."]
                 ["" "BOOT_JVM_OPTIONS"          "Specify JVM options (Unix/Linux/OSX only)."]
                 ["" "BOOT_LOCAL_REPO"           "The local Maven repo path (~/.m2/repository)."]
                 ["" "BOOT_MAVEN_CENTRAL_REPO"   "Specify the url for the 'maven-central' Maven repo."]
                 ["" "BOOT_MAVEN_CENTRAL_MIRROR" "Specify the mirror url for the 'maven-central' Maven repo."]
                 ["" "BOOT_VERSION"              "Specify the version of boot core to use."]
                 ["" "BOOT_WARN_DEPRECATED"      "Set to 'no' to suppress deprecation warnings."]]
          files [["" "./boot.properties"         "Specify boot options for this project."]
                 ["" "./profile.boot"            "A script to run after the global profile.boot but before the build script."]
                 ["" "BOOT_HOME/boot.properties" "Specify global boot options."]
                 ["" "BOOT_HOME/profile.boot"    "A script to run before running the build script."]]
          br    #(conj % ["" "" ""])]
      (printf "\n%s\n"
              (-> [["" ""] ["Usage:" "boot OPTS <task> TASK_OPTS <task> TASK_OPTS ..."]]
                  (table/table :style :none)
                  with-out-str))
      (printf "%s\n\nDo `boot <task> -h` to see usage info and TASK_OPTS for <task>.\n"
              (->> (-> [["" "" ""]]
                       (into (#'helpers/set-title opts "OPTS:")) (br)
                       (into (#'helpers/set-title (#'helpers/tasks-table tasks) "Tasks:")) (br)
                       (into (#'helpers/set-title envs "Env:")) (br)
                       (into (#'helpers/set-title files "Files:"))
                       (table/table :style :none)
                       with-out-str
                       (string/split #"\n"))
                   (map string/trimr)
                   (string/join "\n"))))))
