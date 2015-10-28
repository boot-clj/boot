(comment "
Install Steps
1. base-jar
   cd boot/base && cat pom.in.xml |sed 's/__VERSION__/$(version)/' > pom.xml && mvn -q install
2. all jars
   cd boot/pod && lein install
   cd boot/worker && lein install
   cd boot/core && lein install
   cd boot/boot && lein install
   cd boot/aether && lein install && lein uberjar && mkdir -p ../base/src/main/resources
      && cp target/aether-$(version)-standalone.jar ../base/src/main/resources/$(aetheruber)
   cd boot/base && mvn -q assembly:assembly -DdescriptorId=jar-with-dependencies
3. bootbin
4. bootexe")
(set-env! :dependencies '[[adzerk/bootlaces "0.1.13" :scope "test"]])

(import [java.util Properties])
(require '[clojure.java.io :as io]
         '[clojure.string :as string]
         '[adzerk.bootlaces :as b :refer [push-release]]
         '[boot.util :as util]
         '[boot.pod :as pod])

(def propsfile "version.properties")
(def version (-> (doto (Properties.) (.load (io/input-stream propsfile)))
               (.getProperty "version")))

(when (= (boot.App/getVersion) version)
  (util/warn "Installing Boot using the same version of Boot is not supported.\n")
  (util/warn "All other operations not copying jars to ~/.m2 will work though.\n"))

(def settings
  {:base {:dir {:src #{"boot/base/src/"}
                :rsc #{"boot/base/resources/"}}
          :pom {:project     'boot/base
                :description "Boot Java application loader and class."}
          :deps [['org.projectodd.shimdandy/shimdandy-api "1.2.0"]
                 ['junit/junit "3.8.1" :scope "test"]]}
   :pod {:dir  {:src #{"boot/pod/src/"}}
         :pom  {:project     'boot/pod
                :description "Boot pod module - this is included with all pods."}
         :deps [['boot/base                               version :scope "provided"]
                ['org.clojure/clojure                     "1.6.0" :scope "provided"]
                ['org.tcrawley/dynapath                   "0.2.3" :scope "compile"]
                ['org.projectodd.shimdandy/shimdandy-impl "1.2.0" :scope "compile"]]}
   :core {:dir {:src #{"boot/core/src/"}}
          :pom {:project     'boot/core
                :description "Core boot module - boot scripts run in this pod."}
          :deps [['org.clojure/clojure "1.6.0" :scope "provided"]
                 ['boot/base           version :scope "provided"]
                 ['boot/pod            version :scope "compile"]]}
   :aether {:dir  {:src #{"boot/aether/src/"}}
            :pom  {:project     'boot/aether
                   :description "Boot aether module - performs maven dependency resolution."}
            :deps [['org.clojure/clojure      "1.6.0" :scope "compile"]
                   ['boot/pod                 version :scope "compile"]
                   ['com.cemerick/pomegranate "0.3.0" :scope "compile"]]}
   :worker {:dir  {:src #{"boot/worker/src/" "boot/worker/third_party/barbarywatchservice/src/"}}
            :pom  {:project      'boot/worker
                   :description  "Boot worker module - this is the worker pod for built-in tasks."}
            :deps [['org.clojure/clojure         "1.6.0" :scope "provided"]
                   ['boot/base                   version :scope "provided"]
                   ['boot/aether                 version]
                   ;; see https://github.com/boot-clj/boot/issues/82
                   ['net.cgrand/parsley          "0.9.3" :exclusions ['org.clojure/clojure]]
                   ['reply                       "0.3.5"]
                   ['cheshire                    "5.3.1"]
                   ['clj-jgit                    "0.8.0"]
                   ['clj-yaml                    "0.4.0"]
                   ['javazoom/jlayer             "1.0.1"]
                   ['mvxcvi/clj-pgp              "0.5.4"]
                   ['net.java.dev.jna/jna        "4.1.0"]
                   ['alandipert/desiderata       "1.0.2"]
                   ['org.clojure/data.xml        "0.0.8"]
                   ['org.clojure/data.zip        "0.1.1"]
                   ['org.clojure/tools.namespace "0.2.11"]]}})

(def pom-base {:version version
               :url     "http://github.com/boot-clj/boot"
               :license {"EPL" "http://www.eclipse.org/legal/epl-v10.html"}
               :scm     {:url "https://github.com/boot-clj/boot.git"}})

;; Helper utilities

(defn set-env-for!
  ([subproject]
   (set-env-for! subproject false))
  ([subproject uber?]
   (merge-env! :dependencies (-> settings subproject :deps))
   (set-env! :source-paths   (or (-> settings subproject :dir :src) #{})
             :resource-paths (or (clojure.set/union
                                  (-> settings subproject :dir :rsc)
                                  (-> settings subproject :dir :src))
                                 #{})
             :target-path (str "target/" (name subproject) (if uber? "-uber")))
   (b/bootlaces! version :dont-modify-paths? true)
   (task-options! pom (merge pom-base (-> settings subproject :pom)))))

(defn jarname*
  ([lib]
   (jarname* lib false))
  ([lib uber?]
   (str "boot-" (name lib) "-" version (when uber? "-uber") ".jar")))

(deftask pick
  "Take files from fileset and copy them to directory"
  [f files PATH #{str} "The files to pick."
   d dir   PATH   str  "The directory to put the files."]
  (with-pre-wrap [fs]
    (with-let [fs fs]
      (let [files (->> (output-files fs)
                       (map (juxt tmp-path tmp-file))
                       (filter #((or files #{}) (first %))))]
        (doseq [[p f] files]
          (io/make-parents (io/file dir p))
          (util/info "Copying %s to %s\n" (.getName f) dir)
          (io/copy f (io/file dir p)))))))

(deftask filter-jars
  [r pattern REGEX #{regex} "Pattern jars must match"]
  (with-pre-wrap fs
    (let [jars (set (by-re [#".jar$"] (ls fs)))
          keep  (set (by-re pattern (ls fs)))
          rm*  (clojure.set/difference jars keep)]
      (-> fs (rm rm*) commit!))))

(defmacro task-when
  [pred & body]
  `(if-not ~pred identity (do ~@body)))

;; Actual project specific tasks

(deftask transaction-jar []
  (set-env-for! :transaction-jar)
  (comp (pom :project      'boot/boot
             :description  "Placeholder to synchronize other boot module versions.")
        (jar)))

(deftask pod
  [r launch-repl bool "repl"]
  (set-env-for! :pod)
  (if launch-repl
    (repl)
    (comp (pom)
          (aot :all true)
          (jar :file (jarname* :pod)))))

(deftask worker []
  (set-env-for! :worker)
  (comp (pom)
        (javac)
        (aot :all true)
        (jar :file (jarname* :worker))))

(deftask aether
  [u uberjar bool "build uberjar?"]
  (set-env-for! :aether uberjar)
  (comp (pom)
        (aot :all true)
        (task-when uberjar (uber))
        (jar :file (jarname* :aether uberjar))
        (task-when uberjar (pick :dir "boot/base/resources" :files #{(jarname* :aether true)}))))

(deftask core []
  ;; :jar-exclusions [#"^clojure/core/"]
  (set-env-for! :core)
  (comp (pom)
        (aot :namespace #{'boot.cli 'boot.core 'boot.git 'boot.main 'boot.repl
                          'boot.task.built-in 'boot.task-helpers 'boot.tmregistry})
        (jar :file (jarname* :core))))

(deftask base
  [u uberjar bool "build uberjar?"]
  (set-env-for! :base uberjar)
  (comp
   ;; Write version.properties
   (with-pre-wrap fs
     (let [t (tmp-dir!)
           f (io/file t "boot/base/version.properties")]
       (io/make-parents f)
       (spit f (str "version=" version))
       (-> fs (add-resource t) commit!)))
   ;; Move aether uberjar
   (filter-jars :pattern #{(re-pattern (jarname* :aether true))})
   (task-when uberjar
              (with-pre-wrap fs
                (commit! (mv fs (jarname* :aether true) "aether.uber.jar"))))
   (pom)
   (javac)
   (task-when uberjar (uber))
   (jar :file (jarname* :base uberjar))
   ;; remove all jars not built by above jar task
   (filter-jars :pattern #{(re-pattern (jarname* :base uberjar))})))

(deftask install-boot-jar []
  (comp (sift :move {(re-pattern (jarname* :base true)) "boot.jar"})
        (pick :dir  (str (io/file (boot.App/bootdir) "cache" "bin" version))
              :files #{"boot.jar"})))

;; Development helpers
;; After running `make install` once `boot build --watch` can be used
;; to continously build all boot libraries including the binary.

(defn runboot
  [& boot-args]
  (future
    (boot.App/runBoot
      (boot.App/newCore)
      (future @pod/worker-pod)
      (into-array String (remove nil? boot-args)))))

(deftask build
  "After initial make install or `boot aether --uberjar`
   this task can be used to rebuild and install changed libs"
  [i install bool "Install libs into local .m2"
   w watch   bool "Rebuild libs when filesystem changes"]
  (let [i (if install "install")
        w (if watch "watch")]
    (info "Building base...\n" i)
    (runboot w "base" i)
    (info "Building pod...\n")
    (runboot w "pod" i)
    (info "Building core...\n")
    (runboot w "core" i)
    (info "Building worker...\n")
    (runboot w "worker" i)
    (info "Building aether...\n")
    (runboot w "aether" i)
    (info "Building loader...\n")
    (runboot w "loader" i)
    (info "Building aether uberjar...\n")
    (runboot w "aether" "--uberjar")
    (info "Building base uberjar...\n")
    (runboot w "base" "--uberjar" (if install "install-boot-jar"))
    (wait)))
