(ns boot.task.built-in
  (:require
   [clojure.pprint       :as pp]
   [clojure.java.io      :as io]
   [clojure.set          :as set]
   [clojure.string       :as string]
   [boot.filesystem      :as fs]
   [boot.gpg             :as gpg]
   [boot.pod             :as pod]
   [boot.jar             :as jar]
   [boot.git             :as git]
   [boot.file            :as file]
   [boot.repl            :as repl]
   [boot.core            :as core]
   [boot.main            :as main]
   [boot.util            :as util]
   [boot.tmpdir          :as tmpd]
   [boot.from.table.core :as table]
   [boot.from.digest     :as digest]
   [boot.task-helpers    :as helpers]
   [boot.task-helpers.notify :as notify]
   [boot.pedantic        :as pedantic])
  (:import
   [java.io File]
   [java.nio.file.attribute PosixFilePermissions]
   [java.util Arrays]
   [java.util.concurrent LinkedBlockingQueue TimeUnit]
   [javax.tools ToolProvider DiagnosticCollector Diagnostic$Kind]))

;; Tasks ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

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

(core/deftask ^{:deprecated "2.6.0"} checkout
  "Checkout dependencies task. DEPRECATED. (Use -c, --checkouts Boot option.)

  This task facilitates working on a project and its dependencies at the same
  time, by extracting the dependency jar contents into the fileset. Transitive
  dependencies will be added to the class path automatically.

  You'll need at least two boot instances---one to build the dependency jar and
  the other to build the project. For example:

      $ boot watch pom -p foo/bar -v 1.2.3-SNAPSHOT jar install

  to build the dependency jar, and

      $ boot repl -s watch checkout -d foo/bar:1.2.3-SNAPSHOT cljs serve

  to build the project with the checkout dependency [foo/bar \"1.2.3\"]."

  [d dependencies ID:VER [[sym str]] "The vector of checkout dependencies."]

  (let [env   (core/get-env)
        prev  (atom nil)
        jars  (->> dependencies
                   (map (comp io/file #(pod/resolve-dependency-jar env %))))
        deps  (->> dependencies
                   (mapcat #(pod/resolve-nontransitive-dependencies env %))
                   (map :dep)
                   (remove pod/dependency-loaded?))
        names (map (memfn getName) jars)
        dirs  (map (memfn getParent) jars)
        tmps  (reduce #(assoc %1 %2 (core/tmp-dir!)) {} names)
        warn  (delay
                (util/warn-deprecated
                  "The checkout task is deprecated. Please use the --checkouts boot option instead.\n"))
        adder #(core/add-source %1 %2 :exclude pod/standard-jar-exclusions)]
    (when (seq deps)
      @warn
      (util/info "Adding checkout dependencies:\n")
      (doseq [dep deps]
        (util/info "\u2022 %s\n" (pr-str dep))))
    (core/merge-env! :dependencies (vec deps)
                     :source-paths (set dirs))
    (core/cleanup (core/set-env! :source-paths #(apply disj % dirs)))
    (core/with-pre-wrap [fs]
      (let [diff (->> (core/fileset-diff @prev fs)
                      core/input-files
                      (filter (comp (set names) core/tmp-path))
                      (map (juxt core/tmp-path core/tmp-file)))]
        (reset! prev fs)
        (doseq [[path file] diff]
          (let [tmp (tmps path)]
            (core/empty-dir! tmp)
            (util/info "Checking out %s...\n" path)
            (pod/unpack-jar (.getPath file) tmp)))
        (->> tmps vals (reduce adder fs) core/commit!)))))

(core/deftask ^{:deprecated "2.7.0"} speak
  "Audible notifications during build. DEPRECATED. Use the notify task.

  Default themes: system (the default), ordinance, pillsbury, and
  woodblock. New themes can be included via jar dependency with the
  sound files as resources:

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

  (let [tmp        (core/tmp-dir!)
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
    (fn [next-task]
      (fn [fileset]
        (try
          (util/with-let [_ (next-task fileset)]
            (if (zero? @core/*warnings*)
              (pod/with-call-worker (boot.notify/success! ~theme ~success))
              (pod/with-call-worker (boot.notify/warning! ~theme ~(deref core/*warnings*) ~warning))))
          (catch Throwable t
            (pod/with-call-worker (boot.notify/failure! ~theme ~failure))
            (throw t)))))))

(core/deftask ^{:boot/from :jeluard/boot-notify} notify
  "Aural and visual notifications during build.

  Default audio themes: system (the default), ordinance, pillsbury,
  and woodblock. New themes can be included via jar dependency with
  the sound files as resources:

      boot
      └── notify
          ├── <theme-name>_failure.mp3
          ├── <theme-name>_success.mp3
          └── <theme-name>_warning.mp3

  Sound files specified individually take precedence over theme sounds.

  For visual notifications, there is a default implementation that
  tries to use the `terminal-notifier' program on OS X systems, and
  the `notify-send' program on Linux systems.

  You can also supply custom notification functions via the *-notify-fn
  options. Both are functions that take one argument which is a map of
  options.

  The audible notification function will receive a map with three keys
  - :type, :file, and :theme.

  The visual notification function will receive a map with four keys
  - :title, :uid, :icon, and :message."

  [a audible                    bool      "Play an audible notification"
   v visual                     bool      "Display a visual notification"
   A audible-notify-fn FN       sym       "A function to be used for audible notifications in place of the default method."
   V visual-notify-fn  FN       sym       "A function to be used for visual notifications in place of the default method"
   T theme             NAME     str       "The name of the audible notification sound theme"
   s soundfiles        KEY=VAL  {kw str}  "Sound files overriding theme sounds. Keys can be :success, :warning or :failure"
   m messages          KEY=VAL  {kw str}  "Templates overriding default messages. Keys can be :success, :warning or :failure"
   t title             TITLE    str       "Title of the notification"
   i icon              ICON     str       "Full path of the file used as notification icon"
   u uid               UID      str       "Unique ID identifying this boot process"]

  (let [themefiles (notify/get-themefiles theme (core/tmp-dir!))
        sounds (merge themefiles soundfiles)
        base-visual-opts {:title (or title "Boot")
                          :uid   (or uid title)
                          :icon  (or icon (notify/boot-logo))}
        messages (merge {:success "Success!" :warning "%s warning/s" :failure "%s"}
                        messages)
        audible-notify! (or audible-notify-fn notify/audible-notify!)
        visual-notify!  (or visual-notify-fn  notify/visual-notify!)
        notify! (fn [type message]
                  (when audible
                    (audible-notify! {:type type
                                      :file (get sounds type)
                                      :theme theme}))
                  (when visual
                    (visual-notify! (assoc base-visual-opts
                                           :message message))))]
    (fn [next-task]
      (fn [fileset]
        (try
          (util/with-let [_ (next-task fileset)]
            (if (zero? @core/*warnings*)
              (notify! :success (:success messages))
              (notify! :warning (format (:warning messages)
                                        (deref core/*warnings*)))))
          (catch Throwable t
            (notify! :failure (format (:failure messages)
                                      (.getMessage t)))
            (throw t)))))))

(core/deftask show
  "Print project/build info (e.g. dependency graph, etc)."

  [C fake-classpath   bool  "Print the project's fake classpath."
   c classpath        bool  "Print the project's full classpath."
   d deps             bool  "Print project dependency graph."
   e env              bool  "Print the boot env map."
   f fileset          bool  "Print the build fileset object."
   l list-pods        bool  "Print the names of all active pods."
   p pedantic         bool  "Print graph of dependency conflicts."
   P pods REGEX       regex "The name filter used to select which pods to inspect."
   U update-snapshots bool  "Include snapshot versions in updates searches."
   u updates          bool  "Print newer releases of outdated dependencies."
   v verify-deps      bool  "Include signature status of each dependency in graph."]

  (let [usage      (delay (*usage*))
        pretty-str #(with-out-str (pp/pprint %))
        updates    (or updates update-snapshots)
        sort-pods  #(sort-by (memfn getName) %)]
    (core/with-pass-thru [fs]
      (cond fileset        (helpers/print-fileset fs)
            classpath      (println (or (core/get-env :boot-class-path) ""))
            fake-classpath (println (or (core/get-env :fake-class-path) ""))
            list-pods      (doseq [p (->> pod/pods (map key) sort-pods)]
                             (println (.getName p)))
            :else
            (let [pattern (or pods #"^core$")]
              (doseq [p (->> pattern pod/get-pods sort-pods)
                      :let  [pod-name (.getName p)]
                      :when (not (#{"worker" "aether"} pod-name))]
                (let [pod-env (if (= pod-name "core")
                                (core/get-env)
                                (pod/with-eval-in p boot.pod/env))]
                  (when pods (util/info "\nPod: %s\n\n" pod-name))
                  (cond (or deps verify-deps) (print (pod/with-call-worker (boot.aether/dep-tree ~pod-env ~verify-deps)))
                        env                   (println (pretty-str (assoc pod-env :config (boot.App/config))))
                        updates               (mapv prn (pod/outdated pod-env :snapshots update-snapshots))
                        pedantic              (pedantic/prn-conflicts pod-env)
                        :else                 @usage))))))))

(defn- relativize
  [local-repo path]
  (.getPath
    (if-not local-repo
      (io/file path)
      (let [canonical-local-repo (.getCanonicalFile (io/file local-repo))]
        (io/file local-repo (file/relative-to canonical-local-repo path))))))

(defn- dep-conflicts
  [{:keys [dependencies] :as env}]
  (let [non-transitive? (set (map first dependencies))]
    (into {} (->> (pedantic/dep-conflicts env)
                  (remove (comp non-transitive? first))))))

(core/deftask with-cp
  "Specify Boot's classpath in a file instead of as Maven coordinates.

  The --file option is required -- this specifies the PATH of the file that
  will contain the JAR paths as a string suitable for passing to java -cp. Use
  the minus sign, like --file -, to indicate stdin, stdout.

  The default behavior if the --write flag is not specified is to read the
  file specified with --file and load all the JARs onto the classpath. If the
  --write flag is given the --dependencies (or the default depenedncies from
  the boot env, e.g. (get-env :dependencies), if --dependencies isn't provided)
  are resolved and the resulting list of JAR paths are written to the file in
  a format suitable for passing to java -cp.

  The --safe flag configures the task to throw an exception when writing the
  classpath file if there are any unresolved dependency conflicts. These
  conflicts can be resolved by adding :exclusions and by overriding transitive
  dependencies with direct dependencies.

  The --local-repo option specifies the PATH where the dependency JARs are
  stashed. The default if this option is not given is to use the Maven local
  repository setting from the boot environment. This option only applies in
  combination with the --write option.

  The --scopes option can be used to specify which dependency scopes to include
  in the classpath file. The default scopes are compile, runtime, and provided."

  [s safe               bool    "Throw an exception if there are unresolved dependency conflicts."
   w write              bool    "Resolve dependencies and write the classpath file."
   d dependencies EDN   edn     "The (optional) Maven dependencies to resolve and write to the classpath file."
   e exclusions SYM     [sym]   "The vector of Maven group/artifact ids to globally exclude."
   f file PATH          str     "The file containing JARs in java -cp format."
   l local-repo PATH    str     "The (optional) project directory in which to stash resolved JARs."
   S scopes SCOPE       #{str}  "The set of dependency scopes to include (default compile, runtime, provided)."]

  (core/with-pass-thru [_]
    (let [scopes    (or scopes #{"compile" "runtime" "provided"})
          file-in   (if (= file "-") (System/in) file)
          file-out  (if (= file "-") (System/out) file)
          include?  #(scopes (:scope (util/dep-as-map %)))
          env-opts  (select-keys *opts* [:local-repo :exclusions :dependencies])
          exclude   (partial pod/apply-global-exclusions exclusions)]
      (assert file "Expected --file option.")
      (if-not write
        (doseq [path (string/split (slurp file-in) #":")]
          (pod/add-classpath path))
        (let [env  (-> (merge-with #(or %2 %1) pod/env env-opts)
                       (update-in [:dependencies] #(exclude (filter include? %))))]
          (if-let [conflicts (and safe (not-empty (dep-conflicts env)))]
            (throw (ex-info "Unresolved dependency conflicts." {:conflicts conflicts}))
            (let [source-path     (into [] :source-paths)           
                  resolved        (pod/resolve-dependency-jars env)
                  relative-paths  (map (partial relativize local-repo) resolved)]
              (spit file-out (apply str (interpose ":" relative-paths) (interpose ":" source-path))))))))))

(core/deftask wait
  "Wait before calling the next handler.

  Waits forever if the --time option is not specified."

  [t time MSEC int "The interval in milliseconds."]

  (if (zero? (or time 0))
    (core/with-post-wrap [_] @(promise))
    (core/with-pass-thru [fs] (Thread/sleep time))))

(defn- mk-posix-file-permissions [posix-string]
  (try
    (when posix-string
      (if (boot.App/isWindows)
        (util/warn "Filemode not supported on Windows\n")
        (PosixFilePermissions/fromString posix-string)))
    (catch IllegalArgumentException _
      (util/warn "Could not parse mode string, ignoring...\n"))))

(core/deftask target
  "Writes output files to the given directory on the filesystem."
  [d dir PATH #{str} "The set of directories to write to (target)."
   m mode VAL str    "The mode of written files in 'rwxrwxrwx' format"
   L no-link  bool   "Don't create hard links."
   C no-clean bool   "Don't clean target before writing project files."]
  (let [dir   (or (seq dir) ["target"])
        sync! (#'core/fileset-syncer dir :clean (not no-clean))]
    (core/with-pass-thru [fs]
      (util/info "Writing target dir(s)...\n")
      (sync! fs :link (not no-link) :mode (mk-posix-file-permissions mode)))))

(core/deftask watch
  "Call the next handler when source files change."

  [q quiet          bool     "Suppress all output from running jobs."
   v verbose        bool     "Print which files have changed."
   M manual         bool     "Use a manual trigger instead of a file watcher."
   d debounce MS    int      "Debounce time (how long to wait for filesystem events) in milliseconds."
   i include REGEX  #{regex} "The set of regexes the paths of changed files must match for watch to fire."
   e exclude REGEX  #{regex} "The set of regexes the paths of changed files must not match for watch to fire."]

  (fn [next-task]
    (fn [fileset]
      (let [q            (LinkedBlockingQueue.)
            k            (gensym)
            return       (atom fileset)
            srcdirs      (->> (map (comp :dir val) (core/get-checkouts))
                              (into (core/user-dirs fileset))
                              (map (memfn getPath)))
            watcher      (apply file/watcher! :time srcdirs)
            incl-excl    (if-not (or (seq include) (seq exclude))
                           identity
                           (let [f (partial file/keep-filters? include exclude)]
                             (partial filter (comp f io/file second))))
            watch-target (if manual core/new-build-at core/last-file-change)]
        (.offer q (System/currentTimeMillis))
        (add-watch watch-target k #(.offer q %4))
        (core/cleanup (remove-watch watch-target k))
        (when-not quiet (util/info "\nStarting file watcher (CTRL-C to quit)...\n\n"))
        (loop [ret (util/guard [(.take q)])]
          (when ret
            (if-let [more (.poll q (or debounce 10) TimeUnit/MILLISECONDS)]
              (recur (conj ret more))
              (let [start        (System/currentTimeMillis)
                    etime        #(- (System/currentTimeMillis) start)
                    changed      (when-not manual (incl-excl (watcher)))
                    should-fire? (or manual (not (empty? changed)))]
                (when should-fire?
                  (when verbose
                    (doseq [[op p _] changed]
                      (util/info (format "\u25C9 %s %s\n" op p)))
                    (util/info "\n"))
                  (binding [*out* (if quiet (new java.io.StringWriter) *out*)
                            *err* (if quiet (new java.io.StringWriter) *err*)]
                    (core/reset-build!)
                    (try (reset! return (-> fileset core/reset-fileset core/commit! next-task))
                         (catch Throwable ex (util/print-ex ex)))
                    (util/info "Elapsed time: %.3f sec\n\n" (float (/ (etime) 1000)))))
                (recur (util/guard [(.take q)]))))))
        @return))))

(core/deftask repl
  "Start a REPL session for the current project.

  If no bind/host is specified the REPL server will listen on 127.0.0.1 and
  the client will connect to 127.0.0.1.

  If no port is specified the server will choose a random one and the client
  will read the .nrepl-port file and use that.

  The *default-middleware* and *default-dependencies* atoms in the boot.repl
  namespace contain vectors of default REPL middleware and REPL dependencies to
  be loaded when starting the server. You may modify these in your build.boot
  file."

  [s server         bool  "Start REPL server only."
   c client         bool  "Start REPL client only."
   C no-color       bool  "Disable colored REPL client output."
   e eval EXPR      edn   "The form the client will evaluate in the boot.user ns."
   b bind ADDR      str   "The address server listens on."
   H host HOST      str   "The host client connects to."
   i init PATH      str   "The file to evaluate in the boot.user ns."
   I skip-init      bool  "Skip default client initialization code."
   p port PORT      int   "The port to listen on and/or connect to."
   P pod NAME       str   "The name of the pod to start nREPL server in (core)."
   n init-ns NS     sym   "The initial REPL namespace."
   m middleware SYM [sym] "The REPL middleware vector."
   x handler SYM    sym   "The REPL handler (overrides middleware options)."]

  (let [cpl-path (.getPath (core/tmp-dir!))
        srv-opts (->> [:bind :port :init-ns :middleware :handler :pod]
                      (select-keys *opts*))
        cli-opts (-> *opts*
                     (select-keys [:host :port :history])
                     (assoc :standalone true
                            :custom-eval eval
                            :custom-init init
                            :color (and @util/*colorize?* (not no-color))
                            :skip-default-init skip-init))
        deps     (remove pod/dependency-loaded? @repl/*default-dependencies*)
        repl-svr (delay (apply core/launch-nrepl (mapcat identity srv-opts)))
        repl-cli (delay (pod/with-call-worker (boot.repl-client/client ~cli-opts)))]
    (comp (core/with-pass-thru [fs]
            (when (or server (not client)) @repl-svr))
          (core/with-post-wrap [_]
            (when (or client (not server)) @repl-cli)))))

(core/deftask socket-server
  "Start a socket server.

  The default behavior is to serve a simple REPL handled by
  clojure.core.server/repl. To serve a different handler function, specify a
  symbol using `--accept'.

  If no bind address is specified, the socket server will listen on 127.0.0.1.

  If no port is specified, an open port will be chosen automatically. The port
  number is written to .socket-port in the current directory.

  The REPL can be accessed with the command

     $ nc localhost $(cat .server-port)"

  [b bind ADDR      str    "The address server listens on."
   p port PORT      int    "The port to listen to."
   a accept ACCEPT  sym    "Namespaced symbol of the accept function to invoke."]
  (let [repl-soc (delay (repl/launch-socket-server *opts*))]
    (core/with-pass-thru [fs]
      @repl-soc)))

(core/deftask pom
  "Create project pom.xml file.

  The project and version must be specified to make a pom.xml."

  [p project SYM           sym         "The project id (eg. foo/bar)."
   v version VER           str         "The project version."
   d description DESC      str         "The project description."
   c classifier STR        str         "The project classifier."
   P packaging STR         str         "The project packaging type, i.e. war, pom"
   u url URL               str         "The project homepage url."
   s scm KEY=VAL           {kw str}    "The project scm map (KEY is one of url, tag, connection, developerConnection)."
   l license NAME:URL      {str str}   "The map {name url} of project licenses."
   o developers NAME:EMAIL {str str}   "The map {name email} of project developers."
   D dependencies SYM:VER  [[sym str]] "The project dependencies vector (overrides boot env dependencies)."]

  (let [tgt  (core/tmp-dir!)
        tag  (or (:tag scm) (util/guard (git/last-commit)))
        scm  (when scm (assoc scm :tag tag))
        deps (or dependencies (:dependencies (core/get-env)))
        opts (assoc *opts*
               :scm scm
               :dependencies deps
               :developers developers
               :classifier classifier
               :packaging (or packaging "jar"))]
    (when-not (and project version)
      (throw (Exception. "need project and version to create pom.xml")))
    (let [[project version] (pod/canonical-coord [project version])
          [gid aid] (util/extract-ids project)
          pomdir    (io/file tgt "META-INF" "maven" gid aid)
          xmlfile   (io/file pomdir "pom.xml")
          propfile  (io/file pomdir "pom.properties")]
      (pod/with-call-worker
        (boot.pom/spit-pom! ~(.getPath xmlfile) ~(.getPath propfile) ~opts))
      (core/with-pre-wrap [fs]
        (util/info "Writing %s and %s...\n" (.getName xmlfile) (.getName propfile))
        (-> fs (core/add-resource tgt :meta {:project project}) core/commit!)))))

(core/deftask sift
  "Transform the fileset, matching paths against regexes.

  The --to-asset, --to-resource, and --to-source options move matching paths
  to the corresponding section of the fileset. This can be used to make source
  files into resource files, for example, etc. If --invert is also specified
  the transformation is done to paths that DO NOT match.

  The --add-asset, --add-resource, and --add-source options add the contents
  of a directory to the fileset as assets, resources, or sources, respectively.
  The --invert option has no effect on these options.

  The --add-jar option extracts the contents of a jar file on the classpath
  and adds them to the fileset. The PROJECT part of the argument specifies the
  group-id/artifact-id symbol associated with the jar, and the MATCH portion
  selects which entries in the jar will be extracted. If --invert is also
  specified then entries whose paths DO NOT match the regex will be extracted.

  The --with-meta option specifies a set of metadata keys files in the fileset
  must have. Files without one of these keys will be filtered out. If --invert
  is also specified then files that DO have one of these keys will be filtered
  out, instead.

  The --add-meta option adds a key to the metadata map associated with paths
  matching the regex portion of the argument. For example:

      boot sift --add-meta 'foo$':bar

  merges {:bar true} into the metadata map associated with all paths that end
  with 'foo'. If --invert is also specified the metadata is added to paths
  that DO NOT match the regex portion.

  The --move option applies a find/replace transformation on all paths in the
  output fileset. The --invert option has no effect on this operation.

  The --include option specifies a set of regexes that will be used to filter
  the fileset. Only paths matching one of these will be kept. If --invert is
  also specified then only paths NOT matching one of the regexes will be kept."

  [a to-asset MATCH        #{regex}    "The set of regexes of paths to move to assets."
   r to-resource MATCH     #{regex}    "The set of regexes of paths to move to resources."
   s to-source MATCH       #{regex}    "The set of regexes of paths to move to sources."
   A add-asset PATH        #{str}      "The set of directory paths to add to assets."
   R add-resource PATH     #{str}      "The set of directory paths to add to resources."
   S add-source PATH       #{str}      "The set of directory paths to add to sources."
   j add-jar PROJECT:MATCH {sym regex} "The map of jar to path regex of entries in jar to unpack."
   w with-meta KEY         #{kw}       "The set of metadata keys files must have."
   M add-meta MATCH:KEY    {regex kw}  "The map of path regex to meta key to add."
   m move MATCH:REPLACE    {regex str} "The map of regex to replacement path strings."
   i include MATCH         #{regex}    "The set of regexes that paths must match."
   v invert                bool        "Invert the sense of matching."]

  (let [v?      (:invert *opts*)
        *opts*  (dissoc *opts* :invert)
        action  (partial helpers/sift-action v?)
        process (reduce-kv #(comp (action %2 %3) %1) identity *opts*)]
    (core/with-pre-wrap [fs]
      (util/info "Sifting output files...\n")
      (-> fs process core/commit!))))

(core/deftask add-repo
  "Add all files in project git repo to fileset.

  The ref option (default HEAD) facilitates pulling files from tags or specific
  commits."

  [u untracked     bool   "Add untracked (but not ignored) files."
   r ref REF       str    "The git reference for the desired file tree."]

  (let [tgt (core/tmp-dir!)]
    (core/with-pre-wrap [fs]
      (core/empty-dir! tgt)
      (util/info "Adding repo files...\n")
      (doseq [p (core/git-files :ref ref :untracked untracked)]
        (file/copy-with-lastmod (io/file p) (io/file tgt p)))
      (-> fs (core/add-resource tgt) core/commit!))))

(core/deftask uber
  "Add jar entries from dependencies to fileset.

  Use this task before the packaging task (jar, war, etc.) to create
  uberjars, uberwars, etc. This provides the means to package the project
  with all of its dependencies included.

  By default, entries from dependencies with the following scopes will be
  copied to the fileset: compile, runtime, and provided. The --include-scope
  and --exclude-scope options may be used to add or remove scope(s) from this
  set.

  The --as-jars option pulls in dependency jars without exploding them such
  that the jarfiles themselves are copied into the fileset. When using the
  --as-jars option you need a special classloader like a servlet container
  (e.g. Tomcat, Jetty) that will add the jars to the application classloader.

  When jars are exploded, the --include and --exclude options control which
  paths are added to the uberjar; a path is only added if it matches an
  --include regex and does not match any --exclude regexes.

  The --exclude option default is:

      #{ #\"(?i)^META-INF/INDEX.LIST$\"
         #\"(?i)^META-INF/[^/]*\\.(MF|SF|RSA|DSA)$\" }

  And --include option default is:

      #{ #\".*\" }

  If exploding the jars results in duplicate entries, they will be merged
  using the rules specified by the --merge option. A merge rule is a
  [regex fn] pair, where fn takes three parameters:

    - an InputStream for the previous entry,
    - an InputStream of the new entry,
    - and an OutputStream that will replace the entry.

  The --merge option default is:

      [[ #\"data_readers.clj$\"    into-merger       ]
       [ #\"META-INF/services/.*\" concat-merger     ]
       [ #\".*\"                   first-wins-merger ]]

  The merge rule regular expressions are tested in order, and the fn from
  the first match is applied.

  Setting the --include, --exclude, or --merge options replaces the default."

  [j as-jars                bool           "Copy entire jar files instead of exploding them."
   s include-scope SCOPE    #{str}         "The set of scopes to add."
   S exclude-scope SCOPE    #{str}         "The set of scopes to remove."
   i include       MATCH    #{regex}       "The set of regexes that paths must match."
   e exclude       MATCH    #{regex}       "The set of regexes that paths must not match."
   m merge         REGEX=FN [[regex code]] "The list of duplicate file mergers."]

  (let [tgt        (core/tmp-dir!)
        cache      (core/cache-dir! ::uber :global true)
        dfl-scopes #{"compile" "runtime" "provided"}
        scopes     (-> dfl-scopes
                       (set/union include-scope)
                       (set/difference exclude-scope))
        scope?     #(contains? scopes (:scope (util/dep-as-map %)))
        jars       (-> (core/get-env)
                       (update-in [:dependencies] (partial filter scope?))
                       pod/resolve-dependency-jars)
        jars       (remove #(.endsWith (.getName %) ".pom") jars)
        checkouts  (->> (core/get-checkouts)
                        (filter (comp scope? :dep val))
                        (into {}))
        co-jars    (->> checkouts (map (comp :jar val)))
        co-dirs    (->> checkouts (map (comp :dir val)))
        exclude    (or exclude pod/standard-jar-exclusions)
        merge      (or merge pod/standard-jar-mergers)
        reducer    (fn [xs jar]
                     (core/add-cached-resource
                       xs (digest/md5 jar) (partial pod/unpack-jar jar)
                       :include include :exclude exclude :mergers merge))
        co-reducer #(core/add-resource
                      %1 %2 :include include :exclude exclude :mergers merge)]
    (core/with-pre-wrap [fs]
      (when (seq jars)
        (util/info "Adding uberjar entries...\n"))
      (when as-jars
        (doseq [jar (reduce into [] [jars co-jars])]
          (let [hash (digest/md5 jar)
                name (str hash "-" (.getName jar))
                src  (io/file cache hash)]
            (when-not (.exists src)
              (util/dbug* "Caching jar %s...\n" name)
              (file/copy-atomically jar src))
            (util/dbug* "Adding cached jar %s...\n" name)
            (file/hard-link src (io/file tgt name)))))
      (core/commit! (if as-jars
                      (core/add-resource fs tgt)
                      (reduce co-reducer (reduce reducer fs jars) co-dirs))))))

(core/deftask web
  "Create project web.xml file.

  The --serve option is required. The others are optional."

  [s serve           SYM sym "The 'serve' callback function."
   c create          SYM sym "The 'create' callback function."
   d destroy         SYM sym "The 'destroy' callback function."
   C context-create  SYM sym "The context 'create' callback function, called when the servlet is first loaded by the container."
   D context-destroy SYM sym "The context 'destroyed' callback function, called when the servlet is unloaded by the container."]

  (let [tgt     (core/tmp-dir!)
        xmlfile (io/file tgt "WEB-INF" "web.xml")
        implp   'tailrecursion/clojure-adapter-servlet
        implv   "0.2.1"
        classes #"^tailrecursion/.*\.(class|clj)$"
        webxml  (delay
                  (util/info "Adding servlet impl...\n")
                  (pod/copy-dependency-jar-entries
                    (core/get-env) tgt [implp implv] classes)
                  (util/info "Writing %s...\n" (.getName xmlfile))
                  (pod/with-call-worker
                    (boot.web/spit-web! ~(.getPath xmlfile)
                                        ~serve
                                        ~create
                                        ~destroy
                                        ~context-create
                                        ~context-destroy)))]
    (core/with-pre-wrap [fs]
      (assert (and (symbol? serve) (namespace serve))
              (format "serve function must be namespaced symbol (%s)" serve))
      @webxml
      (-> fs (core/add-resource tgt) core/commit!))))

(core/deftask aot
  "Perform AOT compilation of Clojure namespaces."

  [a all          bool   "Compile all namespaces."
   n namespace NS #{sym} "The set of namespaces to compile."]

  (let [tgt         (core/tmp-dir!)
        pod-env     (update-in (core/get-env) [:directories] conj (.getPath tgt))
        compile-pod (future (pod/make-pod pod-env))]
    (core/with-pre-wrap [fs]
      (core/empty-dir! tgt)
      (let [all-nses (->> fs core/fileset-namespaces)
            nses     (->> all-nses (set/intersection (if all all-nses namespace)) sort)]
        (pod/with-eval-in @compile-pod
          (binding [*compile-path* ~(.getPath tgt)]
            (doseq [[idx ns] (map-indexed vector '~nses)]
              (boot.util/info "Compiling %s/%s %s...\n" (inc idx) (count '~nses) ns)
              (compile ns)))))
      (-> fs (core/add-resource tgt) core/commit!))))

(core/deftask javac
  "Compile java sources."
  [o options OPTIONS [str] "List of options passed to the java compiler."]
  (let [tgt (core/tmp-dir!)]
    (core/with-pre-wrap [fs]
      (let [throw?    (atom nil)
            diag-coll (DiagnosticCollector.)
            compiler  (or (ToolProvider/getSystemJavaCompiler)
                          (throw (Exception. "The java compiler is not working. Please make sure you use a JDK!")))
            file-mgr  (.getStandardFileManager compiler diag-coll nil nil)
            opts      (->> ["-d"  (.getPath tgt)
                            "-cp" (core/get-env :boot-class-path)]
                           (concat options)
                           (into-array String) Arrays/asList)
            handler   {Diagnostic$Kind/ERROR util/fail
                       Diagnostic$Kind/WARNING util/warn
                       Diagnostic$Kind/MANDATORY_WARNING util/warn}
            srcs      (some->> (core/input-files fs)
                               (core/by-ext [".java"])
                               (map core/tmp-file)
                               (into-array File)
                               Arrays/asList
                               (.getJavaFileObjectsFromFiles file-mgr))]
        (when (seq srcs)
          (util/info "Compiling %d Java source files...\n" (count srcs))
          (-> compiler (.getTask *err* file-mgr diag-coll opts nil srcs) .call)
          (doseq [d (.getDiagnostics diag-coll) :let [k (.getKind d)]]
            (when (= Diagnostic$Kind/ERROR k) (reset! throw? true))
            (let [log (handler k util/info)]
              (if (nil? (.getSource d))
                (log "%s: %s\n"
                     (.toString k)
                     (.getMessage d nil))
                (log "%s: %s, line %d: %s\n"
                     (.toString k)
                     (.. d getSource getName)
                     (.getLineNumber d)
                     (.getMessage d nil)))))
          (.close file-mgr)
          (when @throw? (throw (Exception. "java compiler error")))))
      (-> fs (core/add-resource tgt) core/commit!))))

(defn- sift-poms
  [fileset project]
  (let [poms      (->> (core/output-files fileset)
                       (core/by-name ["pom.xml"]))
        prj-match (when project
                    (str "/" (pod/full-id project) "/pom.xml"))
        project?  #(if-not prj-match
                     (:project %)
                     (.endsWith (core/tmp-path %) prj-match))]
    (if (< (count poms) 2) poms (->> poms (filter project?)))))

(core/deftask jar
  "Build a jar file for the project."

  [f file PATH        str       "The target jar file name."
   M manifest KEY=VAL {str str} "The jar manifest map."
   m main MAIN        sym       "The namespace containing the -main function."
   p project SYM      sym       "The project symbol -- used to find the correct pom.xml file."]

  (let [old-fs (atom nil)
        tgt    (core/tmp-dir!)
        out    (atom nil)]
    (core/with-pre-wrap [fs]
      (let [new-fs    (core/output-fileset fs)
            [pom & p] (map core/tmp-file (sift-poms fs project))
            {:keys [project version]}
            (when (and pom (not (seq p)))
              (pod/with-call-worker
                (boot.pom/pom-xml-parse-string ~(slurp pom))))
            pomname (when (and project version)
                      (str (name project) "-" version ".jar"))
            fname   (or file pomname "project.jar")
            out*    (io/file tgt fname)]
        (when (and project (seq p))
          (util/warn "Multiple pom.xml files for project: %s\n" project))
        (when (not= @out out*)
          (when (and @out (.exists @out))
            (file/move @out out*))
          (reset! out out*))
        (util/info "Writing %s...\n" fname)
        (jar/update-jar! @out @old-fs (reset! old-fs new-fs) manifest main)
        (-> fs (core/add-resource tgt) core/commit!)))))

(core/deftask war
  "Create war file for web deployment."

  [f file PATH str "The target war file name."]

  (let [tgt (core/tmp-dir!)]
    (core/with-pre-wrap [fs]
      (core/empty-dir! tgt)
      (let [warname (or file "project.war")
            warfile (io/file tgt warname)
            inf?    #(contains? #{"META-INF" "WEB-INF"} %)
            ->war   #(let [r    (core/tmp-path %)
                           r'   (file/split-path r)
                           path (->> (if (.endsWith r ".jar")
                                       ["lib" (last r')]
                                       (into ["classes"] r'))
                                     (into ["WEB-INF"]))]
                       (if (inf? (first r')) r (.getPath (apply io/file path))))
            entries (core/output-files fs)
            index   (->> entries (mapv (juxt ->war #(.getPath (core/tmp-file %)))))]
        (util/info "Writing %s...\n" (.getName warfile))
        (jar/spit-jar! (.getPath warfile) index {} nil)
        (-> fs (core/add-resource tgt) core/commit!)))))

(core/deftask zip
  "Build a zip file for the project."

  [f file PATH str "The target zip file name."]

  (let [old-fs (atom nil)
        tgt    (core/tmp-dir!)
        fname  (or file "project.zip")
        out    (io/file tgt fname)]
    (core/with-pre-wrap [fs]
      (let [new-fs (core/output-fileset fs)]
        (util/info "Writing %s...\n" fname)
        (jar/update-zip! out @old-fs (reset! old-fs new-fs))
        (-> fs (core/add-resource tgt) core/commit!)))))

(core/deftask install
  "Install project jar to local Maven repository.

  The --file option allows installation of arbitrary jar files. If no
  file option is given then any jar artifacts created during the build
  will be installed.

  The pom.xml file that's required when installing a jar can usually be
  found in the jar itself. However, sometimes a jar might contain more
  than one pom.xml file or may not contain one at all.

  The --pom option can be used in these situations to specify which
  pom.xml file to use. The optarg denotes either the path to a pom.xml
  file in the filesystem or a subdir of the META-INF/maven/ dir in which
  the pom.xml contained in the jar resides.

  Example:

    Given a jar file (warp-0.1.0.jar) with the following contents:

        .
        ├── META-INF
        │   ├── MANIFEST.MF
        │   └── maven
        │       └── tailrecursion
        │           └── warp
        │               ├── pom.properties
        │               └── pom.xml
        └── tailrecursion
            └── warp.clj

    The jar could be installed with the following boot command:

        $ boot install -f warp-0.1.0.jar -p tailrecursion/warp"

  [f file PATH str "The jar file to install."
   p pom PATH  str "The pom.xml file to use."]

  (core/with-pass-thru [fs]
    (let [jarfiles (or (and file [(io/file file)])
                       (->> (core/output-files fs)
                            (core/by-ext [".jar"])
                            (map core/tmp-file)))]
      (when-not (seq jarfiles) (throw (Exception. "can't find jar file")))
      (doseq [jarfile jarfiles]
        (util/info "Installing %s...\n" (.getName jarfile))
        (pod/with-call-worker
          (boot.aether/install ~(core/get-env) ~(.getPath jarfile) ~pom))))))

(core/deftask push
  "Deploy jar file to a Maven repository.

  If the file option is not specified the task will look for jar files
  created by the build pipeline. The jar file(s) must contain pom.xml
  entries.

  The repo option is required. The repo option is used to get repository
  map from Boot envinronment. Additional repo-map option can be used to
  add options, like credentials, or to provide complete repo-map if Boot
  envinronment doesn't hold the named repository."

  [f file PATH            str      "The jar file to deploy."
   P pom PATH             str      "The pom.xml file to use (see install task)."
   F file-regex MATCH     #{regex} "The set of regexes of paths to deploy."
   g gpg-sign             bool     "Sign jar using GPG private key."
   k gpg-user-id KEY      str      "The name or key-id used to select the signing key."
   ^{:deprecated "Check GPG help about changing GNUPGHOME."}
   K gpg-keyring PATH     str      "The path to secring.gpg file to use for signing."
   p gpg-passphrase PASS  str      "The passphrase to unlock GPG signing key."
   r repo NAME            str      "The name of the deploy repository."
   e repo-map REPO        edn      "The repository map of the deploy repository."
   t tag                  bool     "Create git tag for this version."
   B ensure-branch BRANCH str      "The required current git branch."
   C ensure-clean         bool     "Ensure that the project git repo is clean."
   R ensure-release       bool     "Ensure that the current version is not a snapshot."
   S ensure-snapshot      bool     "Ensure that the current version is a snapshot."
   T ensure-tag TAG       str      "The SHA1 of the commit the pom's scm tag must contain."
   V ensure-version VER   str      "The version the jar's pom must contain."]

  (let [tgt (core/tmp-dir!)]
    (core/with-pass-thru [fs]
      (core/empty-dir! tgt)
      (let [jarfiles (or (and file [(io/file file)])
                         (->> (core/output-files fs)
                              (core/by-ext [".jar"])
                              ((if (seq file-regex) #(core/by-re file-regex %) identity))
                              (map core/tmp-file)))
            ; Get options from Boot env by repo name
            r        (get (->> (core/get-env :repositories) (into {})) repo)
            repo-map (merge r (when repo-map ((core/configure-repositories!) repo-map)))]
        (when-not (and repo-map (seq jarfiles))
          (throw (Exception. "missing jar file or repo not found")))
        (doseq [f jarfiles]
          (let [{{t :tag} :scm
                 v :version} (pod/pom-xml-map f pom)
                b            (util/guard (git/branch-current))
                commit       (util/guard (git/last-commit))
                tags         (util/guard (git/ls-tags))
                clean?       (util/guard (git/clean?))
                snapshot?    (.endsWith v "-SNAPSHOT")
                artifact-map (when gpg-sign
                               (util/info "Signing %s...\n" (.getName f))
                               (gpg/sign-jar tgt f pom {:gpg-key gpg-user-id
                                                        :gpg-passphrase gpg-passphrase}))]
            (assert (or (not ensure-branch) (= b ensure-branch))
                    (format "current git branch is %s but must be %s" b ensure-branch))
            (assert (or (not ensure-clean) clean?)
                    "project repo is not clean")
            (assert (or (not ensure-release) (not snapshot?))
                    (format "not a release version (%s)" v))
            (assert (or (not ensure-snapshot) snapshot?)
                    (format "not a snapshot version (%s)" v))
            (assert (or (not ensure-tag) (not t) (= t ensure-tag))
                    (format "scm tag in pom doesn't match (%s, %s)" t ensure-tag))
            (when (and ensure-tag (not t))
              (util/warn "The --ensure-tag option was specified but scm info is missing from pom.xml\n"))
            (assert (or (not ensure-version) (= v ensure-version))
                    (format "jar version doesn't match project version (%s, %s)" v ensure-version))
            (util/info "Deploying %s...\n" (.getName f))
            (pod/with-call-worker
              (boot.aether/deploy
                ~(core/get-env) ~[repo repo-map] ~(.getPath f) ~pom ~artifact-map))
            (when tag
              (if (and tags (= commit (get tags tag)))
                (util/info "Tag %s already created for %s\n" tag commit)
                (do (util/info "Creating tag %s...\n" v)
                    (git/tag v "release"))))))))))
