(ns boot.task.built-in
  (:require
   [clojure.java.io           :as io]
   [clojure.set               :as set]
   [clojure.pprint            :as pprint]
   [boot.pod                  :as pod]
   [boot.core                 :as core]
   [boot.main                 :as main]
   [boot.util                 :as util]
   [boot.task-helpers         :as helpers]
   [boot.from.table.core      :as table]))

;; Tasks ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(core/deftask help
  "Print usage info and list available tasks.

  Here is some other stuff."
  []
  (core/with-pre-wrap
    (let [tasks (helpers/available-tasks 'boot.user)
          opts  (->> main/cli-opts (mapv (fn [[x y z]] ["" (str x " " y) z])))]
      (printf "%s\n\n" (helpers/version-str))
      (printf "%s\n"
        (-> [[""       ""]
             ["Usage:" "boot OPTS <task> TASK_OPTS <task> TASK_OPTS ..."]]
          (table/table :style :none)
          with-out-str))
      (printf "%s\nDo `boot <task> -h` to see usage info and TASK_OPTS for <task>.\n"
        (-> [["" "" ""]]
          (into (helpers/set-title (conj opts ["" "" ""]) "OPTS:"))
          (into (helpers/set-title (helpers/tasks-table tasks) "Tasks:"))
          (table/table :style :none)
          with-out-str)))))

(core/deftask speak
  "Audible notifications during build.

  Default themes: system (the default), ordinance, and woodblock. New themes
  can be included via jar dependency with the sound files as resources:

    boot
    └── notify
        ├── <theme-name>_failure.mp3
        ├── <theme-name>_success.mp3
        └── <theme-name>_warning.mp3

   Sound files specified individually take precedence over theme sounds."

  [t theme NAME   str "The notification sound theme."
   s success FILE str "The sound file to play when the build is successful."
   w warning FILE str "The sound file to play when there are warnings reported."
   f failure FILE str "The sound file to play when the build fails."]

  (let [tmp        (core/mktmpdir! ::hear-tmp)
        resource   #(vector %2 (format "boot/notify/%s_%s.mp3" %1 %2))
        resources  #(map resource (repeat %) ["success" "warning" "failure"])
        themefiles (into {}
                     (let [rs (when theme (resources theme))]
                       (when (and (seq rs) (every? (comp io/resource second) rs))
                         (for [[x r] rs]
                           (let [f (io/file tmp (.getName (io/file r)))]
                             (pod/copy-resource r f)
                             [(keyword x) (.getPath f)])))))
        success    (or success (:success themefiles))
        warning    (or warning (:warning themefiles))
        failure    (or failure (:failure themefiles))]
    (fn [continue]
      (fn [event]
        (try
          (util/with-let [ret (continue event)]
            (pod/call-worker
              (if (zero? @core/*warnings*)
                `(boot.notify/success! ~theme ~success)
                `(boot.notify/warning! ~theme ~(deref core/*warnings*) ~warning))))
          (catch Throwable t
            (pod/call-worker
              `(boot.notify/failure! ~theme ~failure))
            (throw t)))))))

(core/deftask env
  "Print the boot environment map."
  []
  (core/with-pre-wrap
    (prn (core/get-env))))

(core/deftask wait
  "Wait before calling the next handler.

  Waits forever if the --time option is not specified."

  [t time MSEC int "The interval in milliseconds."]

  (core/with-pre-wrap
    (if (zero? (or time 0)) @(promise) (Thread/sleep time))))

(core/deftask watch
  "Call the next handler whenever source files change.

  Default polling interval is 200ms."

  [f fancy     bool "Print updating timer and ANSI color output."
   t time MSEC int  "The polling interval in milliseconds."]

  (comp (helpers/auto (or time 200)) (helpers/files-changed? :time fancy)))

(core/deftask syncdir
  "Copy/sync files between directories.

  The `in-dir` directories will be overlayed on the `out-dir` directory. Empty
  directories are ignored. Similar to `rsync --delete` in a Unix system."

  [i in-dirs DIR #{str} "The set of source directories."
   o out-dir DIR str    "The destination directory."]

  (core/add-sync! out-dir in-dirs)
  identity)

(core/deftask repl
  "Start a REPL session for the current project.

  If no bind/host is specified the REPL server will listen on 0.0.0.0 and the
  client will connect to 127.0.0.1.

  If no port is specified the server will choose a random one and the client
  will read the .nrepl-port file and use that.

  The #'boot.repl-server/*default-middleware* dynamic var holds a vector of the
  default REPL middleware to be included. You may modify this in your build.boot
  file by calling set! or rebinding the var."

  [s server         bool   "Start REPL server only."
   c client         bool   "Start REPL client only."
   C no-color       bool   "Disable ANSI color output in client."
   b bind ADDR      str    "The address server listens on."
   H host HOST      str    "The host client connects to."
   p port PORT      int    "The port to listen on and/or connect to."
   n init-ns NS     str    "The initial REPL namespace."
   m middleware SYM [code] "The REPL middleware vector."]

  (let [srv-opts (select-keys *opts* [:bind :port :init-ns :middleware])
        cli-opts (-> *opts*
                   (select-keys [:host :port :history])
                   (assoc :color (not no-color)))]
    (core/with-pre-wrap
      (when (or server (not client))
        (future
          (try (require 'clojure.tools.nrepl.server)
               (catch Throwable _
                 (pod/add-dependencies
                   (assoc (core/get-env)
                     :dependencies '[[org.clojure/tools.nrepl "0.2.4"]]))))
          (require 'boot.repl-server)
          ((resolve 'boot.repl-server/start-server) srv-opts)))
      (when (or client (not server))
        (pod/call-worker
          `(boot.repl-client/client ~cli-opts))))))

(core/deftask dep-tree
  "Print the project's dependency graph."
  []
  (core/with-pre-wrap
    (pod/call-worker
      `(boot.aether/print-dep-tree ~(core/get-env)))))

(core/deftask pom
  "Write the project's pom.xml file."

  [p project SYM      sym      "The project id (eg. foo/bar)."
   v version VER      str      "The project version."
   d description DESC str      "The project description."
   u url URL          str      "The project homepage url."
   l license KEY=VAL  {kw str} "The project license map (KEY in name, url)."
   s scm KEY=VAL      {kw str} "The project scm map (KEY in url, tag)."]

  (defonce pom-created? (atom false))
  (let [opts (assoc *opts* :dependencies (:dependencies (core/get-env)))]
    (core/with-pre-wrap
      (when-not @pom-created?
        (when-not (and project version)
          (throw (Exception. "need project and version to create pom.xml")))
        (let [tgt (core/mktgtdir! ::pom-tgt)]
          (pod/call-worker
            `(boot.pom/spit-pom! ~(.getPath tgt) ~opts))
          (reset! pom-created? true))))))

(core/deftask jar
  "Build a jar file for the project."

  [S no-source        bool      "Exclude source files from jar."
   M manifest KEY=VAL {str str} "The jar manifest map."
   m main MAIN        sym       "The namespace containing the -main function."
   s filter-src FUNC  code      "The function to pass source file paths through for filtering."
   j filter-jar FUNC  code      "The function to pass jar paths through for filtering."]

  (let [tgt (core/mktgtdir! ::jar-tgt)]
    (comp
      (pom)
      (core/with-pre-wrap
        (doseq [pomprop (->> (core/tgt-files) (core/by-name ["pom.properties"]))]
          (let [{:keys [project version]} (pod/pom-properties-map pomprop)
                pomxml  (io/file (.replaceAll (.getPath pomprop) "[.]properties$" ".xml"))
                jarname (util/jarname project version)
                jarfile (io/file tgt jarname)]
            (when-not (.exists jarfile)
              (let [index   (->> (if no-source (core/tgt-files) (core/src-files))
                              (map (juxt core/relative-path (memfn getPath))) (into {}))
                    ks      (set ((or filter-jar identity) (keys index)))
                    vs      (set ((or filter-src identity) (vals index)))
                    keep?   #(and (contains? ks %1) (contains? vs %2))
                    paths   (->> index (reduce-kv #(if-not (keep? %2 %3) %1 (assoc %1 %2 %3)) {}))]
                (pod/call-worker
                  `(boot.jar/spit-jar! ~(.getPath jarfile) ~paths ~manifest ~main))
                (core/consume-file! pomxml pomprop)))))))))

(core/deftask uberjar
  "Build project jar with dependencies included.

  By default, dependencies with the following scopes will be included in the
  uber jar file: compile, runtime, and provided."

  [x exclude-scope SCOPE #{str} "The set of excluded scopes."]

  (let [tgt        (core/mktgtdir! ::uberjar-tgt)
        dfl-scopes #{"compile" "runtime" "provided"}
        scopes     (set/difference dfl-scopes exclude-scope)]
    (comp
      (jar)
      (core/with-pre-wrap
        (doseq [jarfile (->> (core/tgt-files)
                          (core/by-ext [".jar"])
                          (core/not-by-ext [".uber.jar"]))]
          (let [ubername (str (.replaceAll (.getName jarfile) "[.]jar$" "") ".uber.jar")
                uberfile (io/file tgt ubername)]
            (when-not (.exists uberfile)
              (let [scope? #(contains? scopes (:scope (util/dep-as-map %)))
                    env    (-> (core/get-env)
                             (update-in [:dependencies] (partial filter scope?)))]
                (pod/call-worker
                  `(boot.jar/uber-jar!
                     ~(.getPath jarfile)
                     ~(.getPath uberfile)
                     ~(pod/call-worker
                        `(boot.aether/jar-entries-in-dep-order ~env))))
                (core/consume-file! jarfile)))))))))

(core/deftask web
  "Create project web.xml file.

  The --serve option is required. The others are optional."

  [s serve SYM        sym "The 'serve' callback function."
   c create SYM       sym "The 'create' callback function."
   d destroy SYM      sym "The 'destroy' callback function."]

  (defonce web-created? (atom false))
  (core/with-pre-wrap
    (when-not @web-created?
      (-> (and (symbol? serve) (namespace serve))
        (assert "no serve function specified"))
      (let [tgt (core/mktgtdir! ::web-tgt)]
        (pod/call-worker
          `(boot.web/spit-web! ~(.getPath tgt) ~serve ~create ~destroy))
        (reset! web-created? true)))))

(core/deftask war
  "Create war file for web deployment."
  []
  (let [tgt (core/mktgtdir! ::war-tgt)]
    (comp
      (jar)
      (web)
      (core/with-pre-wrap
        (doseq [jarfile (->> (core/tgt-files) (core/by-ext [".jar"]))]
          (let [warname (.replaceAll (.getName (io/file jarfile)) "\\.jar$" ".war")
                warfile (io/file tgt warname)]
            (when-not (.exists warfile)
              (let [index   (pod/call-worker
                              `(boot.aether/jar-entries ~(.getPath jarfile)))
                    paths   (->> index
                              (remove #(.startsWith (first %) "META-INF/"))
                              (map (fn [[x y]] [(str "WEB-INF/classes/" x) y])))]
                (pod/call-worker
                  `(boot.jar/spit-jar! ~(.getPath warfile) ~(vec paths) nil nil))
                (core/consume-file! jarfile)))))))))

(core/deftask install
  "Install project jar to local Maven repository."

  [f file PATH str "The jar file to install."]

  (comp
    (if file identity (jar))
    (core/with-pre-wrap
      (let [jarfiles (or (and file [(io/file file)])
                      (->> (core/tgt-files) (core/by-ext [".jar"])))]
        (when-not (seq jarfiles) (throw (Exception. "can't find jar file")))
        (doseq [jarfile jarfiles]
          (util/info "Installing %s...\n" (.getName jarfile))
          (pod/call-worker
            `(boot.aether/install ~(.getPath jarfile))))))))

(core/deftask push
  "Push project jar to Clojars."

  [f file PATH str "The jar file to push to Clojars."]

  (comp
    (if file identity (jar))
    (core/with-pre-wrap
      (let [jarfiles (or (and file [(io/file file)])
                      (->> (core/tgt-files) (core/by-ext [".jar"])))]
        (when-not (seq jarfiles) (throw (Exception. "can't find jar file")))
        (doseq [jarfile jarfiles]
          (let [tmp (core/mktmpdir! ::push-tmp)
                pom (doto (io/file tmp "pom.xml") (spit (pod/pom-xml jarfile)))]
            ((helpers/sh "scp" (.getPath jarfile) (.getPath pom) "clojars@clojars.org:"))))))))
