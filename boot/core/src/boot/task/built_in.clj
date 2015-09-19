(ns boot.task.built-in
  (:require
   [clojure.java.io      :as io]
   [clojure.set          :as set]
   [clojure.string       :as string]
   [clojure.pprint       :as pp]
   [boot.pod             :as pod]
   [boot.git             :as git]
   [boot.file            :as file]
   [boot.repl            :as repl]
   [boot.core            :as core]
   [boot.main            :as main]
   [boot.util            :as util]
   [boot.from.table.core :as table]
   [boot.task-helpers    :as helpers]
   [boot.pedantic        :as pedantic])
  (:import
   [java.io File]
   [java.util Arrays]
   [javax.tools ToolProvider DiagnosticCollector Diagnostic$Kind]
   [java.util.concurrent LinkedBlockingQueue TimeUnit]))

;; Tasks ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(core/deftask help
  "Print usage info and list available tasks."
  []
  (core/with-pre-wrap fileset
    (let [tasks (#'helpers/available-tasks 'boot.user)
          opts  (->> main/cli-opts (mapv (fn [[x y z]] ["" (str x " " y) z])))
          envs  [["" "BOOT_AS_ROOT"            "Set to 'yes' to allow boot to run as root."]
                 ["" "BOOT_CLOJURE_VERSION"    "The version of Clojure boot will provide (1.7.0)."]
                 ["" "BOOT_CLOJURE_NAME"       "The artefact name of Clojure boot will provide (org.clojure/clojure)."]
                 ["" "BOOT_HOME"               "Directory where boot stores global state (~/.boot)."]
                 ["" "BOOT_FILE"               "Build script name (build.boot)."]
                 ["" "BOOT_JAVA_COMMAND"       "Specify the Java executable (java)."]
                 ["" "BOOT_JVM_OPTIONS"        "Specify JVM options (Unix/Linux/OSX only)."]
                 ["" "BOOT_LOCAL_REPO"         "The local Maven repo path (~/.m2/repository)."]
                 ["" "BOOT_VERSION"            "Specify the version of boot core to use."]
                 ["" "BOOT_COLOR"              "Turn colorized output on or off"]]
          files [["" "./boot.properties"       "Specify boot and clj versions for this project."]
                 ["" "$BOOT_HOME/profile.boot" "A script to run before running the build script."]]
          br    #(conj % ["" "" ""])]
      (boot.App/usage)
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
                   (string/join "\n")))
      fileset)))

(core/deftask checkout
  "Checkout dependencies task.
  
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
        tmps  (reduce #(assoc %1 %2 (core/tmp-dir!)) {} names)]
    (when (seq deps)
      (util/info "Adding checkout dependencies:\n")
      (doseq [dep deps]
        (util/info "\u2022 %s\n" (pr-str dep))))
    (core/merge-env! :dependencies (vec deps)
                     :source-paths (set dirs))
    (core/cleanup (core/set-env! :source-paths #(apply disj % dirs)))
    (core/with-pre-wrap fileset
      (let [diff (->> (core/fileset-diff @prev fileset)
                      core/input-files
                      (filter (comp (set names) core/tmp-path))
                      (map (juxt core/tmp-path core/tmp-file)))]
        (reset! prev fileset)
        (doseq [[path file] diff]
          (let [tmp (tmps path)]
            (core/empty-dir! tmp)
            (util/info "Checking out %s...\n" path)
            (pod/unpack-jar (.getPath file) tmp
              :exclude pod/standard-jar-exclusions)))
        (->> tmps vals (reduce core/add-source fileset) core/commit!)))))

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

(core/deftask show
  "Print project/build info (e.g. dependency graph, etc)."

  [C fake-classpath   bool "Print the project's fake classpath."
   c classpath        bool "Print the project's full classpath."
   d deps             bool "Print project dependency graph."
   e env              bool "Print the boot env map."
   f fileset          bool "Print the build fileset object."
   p pedantic         bool "Print graph of dependency conflicts."
   U update-snapshots bool "Include snapshot versions in updates searches."
   u updates          bool "Print newer releases of outdated dependencies."]

  (let [updates (or updates update-snapshots)
        pretty-str #(with-out-str (pp/pprint %))]
    (core/with-pre-wrap fileset'
      (cond
        deps           (print (pod/with-call-worker (boot.aether/dep-tree ~(core/get-env))))
        env            (println (pretty-str (core/get-env)))
        fileset        (helpers/print-fileset fileset')
        classpath      (println (or (System/getProperty "boot.class.path") ""))
        fake-classpath (println (or (System/getProperty "fake.class.path") ""))
        updates        (mapv prn (pod/outdated (core/get-env) :snapshots update-snapshots))
        pedantic       (pedantic/prn-conflicts (core/get-env))
        :else          (*usage*))
      fileset')))

(core/deftask wait
  "Wait before calling the next handler.

  Waits forever if the --time option is not specified."

  [t time MSEC int "The interval in milliseconds."]

  (if (zero? (or time 0))
    (core/with-post-wrap _ @(promise))
    (core/with-pre-wrap fileset (Thread/sleep time) fileset)))

(core/deftask watch
  "Call the next handler when source files change.

  Debouncing time is 10ms by default."

  [q quiet         bool "Suppress all output from running jobs."
   v verbose       bool "Print which files have changed."
   M manual        bool "Use a manual trigger instead of a file watcher."]

  (pod/require-in @pod/worker-pod "boot.watcher")
  (fn [next-task]
    (fn [fileset]
      (let [q            (LinkedBlockingQueue.)
            k            (gensym)
            return       (atom fileset)
            srcdirs      (map (memfn getPath) (core/user-dirs fileset))
            watcher      (apply file/watcher! :time srcdirs)
            debounce     (core/get-env :watcher-debounce)
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
                    changed      (watcher)
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
   e eval EXPR      edn   "The form the client will evaluate in the boot.user ns."
   b bind ADDR      str   "The address server listens on."
   H host HOST      str   "The host client connects to."
   i init PATH      str   "The file to evaluate in the boot.user ns."
   I skip-init      bool  "Skip default client initialization code."
   p port PORT      int   "The port to listen on and/or connect to."
   n init-ns NS     sym   "The initial REPL namespace."
   m middleware SYM [sym] "The REPL middleware vector."
   x handler SYM    sym   "The REPL handler (overrides middleware options)."]

  (let [srv-opts (select-keys *opts* [:bind :port :init-ns :middleware :handler])
        cli-opts (-> *opts*
                     (select-keys [:host :port :history])
                     (assoc :standalone true
                            :custom-eval eval
                            :custom-init init
                            :color @util/*colorize?*
                            :skip-default-init skip-init))
        deps     (remove pod/dependency-loaded? @repl/*default-dependencies*)
        repl-svr (delay (when (seq deps)
                          (pod/add-dependencies
                            (assoc (core/get-env) :dependencies deps)))
                        (require 'boot.repl-server)
                        ((resolve 'boot.repl-server/start-server) srv-opts))
        repl-cli (delay (pod/with-call-worker (boot.repl-client/client ~cli-opts)))]
    (comp
      (core/with-pre-wrap fileset
        (when (or server (not client)) @repl-svr)
        fileset)
      (core/with-post-wrap _
        (when (or client (not server)) @repl-cli)))))

(core/deftask pom
  "Create project pom.xml file.

  The project and version must be specified to make a pom.xml."

  [p project SYM      sym       "The project id (eg. foo/bar)."
   v version VER      str       "The project version."
   d description DESC str       "The project description."
   u url URL          str       "The project homepage url."
   l license NAME:URL {str str} "The project license map."
   s scm KEY=VAL      {kw str}  "The project scm map (KEY in url, tag)."]

  (let [tgt (core/tmp-dir!)]
    (core/with-pre-wrap fileset
      (let [tag  (or (:tag scm) (util/guard (git/last-commit)))
            scm  (when scm (assoc scm :tag tag))
            opts (assoc *opts* :scm scm :dependencies (:dependencies (core/get-env)))]
      (core/empty-dir! tgt)
      (when-not (and project version)
        (throw (Exception. "need project and version to create pom.xml")))
      (let [[gid aid] (util/extract-ids project)
            pomdir    (io/file tgt "META-INF" "maven" gid aid)
            xmlfile   (io/file pomdir "pom.xml")
            propfile  (io/file pomdir "pom.properties")]
        (util/info "Writing %s and %s...\n" (.getName xmlfile) (.getName propfile))
        (pod/with-call-worker
          (boot.pom/spit-pom! ~(.getPath xmlfile) ~(.getPath propfile) ~opts))
        (-> fileset (core/add-resource tgt) core/commit!))))))

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

  (core/with-pre-wrap fileset
    (let [tmp      (core/tmp-dir!)
          negate   #(if-not invert % (not %))
          include* (when-not invert include)
          exclude* (when invert include)
          keep?    (partial file/keep-filters? include* exclude*)
          mvpath   (partial reduce-kv #(string/replace %1 %2 %3))
          mover    #(or (and (not move) %1)
                        (let [from-path (core/tmp-path %2)
                              to-path   (mvpath from-path move)]
                          (core/mv %1 from-path to-path)))
          findany  #(reduce (fn [xs x] (or (re-find x %2) xs)) false %1)
          match?   #(and %1 (negate (findany %1 (core/tmp-path %2))))
          to-src   #(if-not (match? to-source   %2) %1 (core/mv-source   %1 [%2]))
          to-rsc   #(if-not (match? to-resource %2) %1 (core/mv-resource %1 [%2]))
          to-ast   #(if-not (match? to-asset    %2) %1 (core/mv-asset    %1 [%2]))
          remover  #(if (keep? (io/file (core/tmp-path %2))) %1 (core/rm %1 [%2]))
          pathfor  #(-> (fn [p] (negate (re-find %1 p)))
                        (filter (map core/tmp-path (core/ls %2))))
          pathfor  #(filter (partial re-find %1) (map core/tmp-path (core/ls %2)))
          addmeta  #(-> (fn [meta-map re key]
                          (-> (fn [meta-map path]
                                (assoc-in meta-map [path key] true))
                              (reduce meta-map (pathfor re %))))
                        (reduce-kv {} add-meta))
          withmeta #(or (and (not with-meta) %1)
                        (if (negate (some with-meta (keys %2))) %1 (core/rm %1 [%2])))]
      (util/info "Sifting output files...\n")
      (doseq [[proj re] add-jar]
        (let [inc (when-not invert [re])
              exc (when invert [re])
              env (core/get-env)
              dep (->> env :dependencies (filter #(= proj (first %))) first)
              jar (pod/resolve-dependency-jar (core/get-env) dep)]
          (util/info "Adding jar entries from %s...\n" (.getName (io/file jar)))
          (pod/unpack-jar jar tmp :include inc :exclude exc)))
      (-> fileset
          ((partial reduce core/add-asset)    (map io/file add-asset))
          ((partial reduce core/add-resource) (map io/file add-resource))
          ((partial reduce core/add-source)   (map io/file add-source))
          (core/fileset-reduce core/ls remover to-src to-rsc to-ast mover withmeta)
          (#(core/add-meta % (addmeta %)))
          (core/add-resource tmp)
          core/commit!))))

(core/deftask add-repo
  "Add all files in project git repo to fileset.

  The ref option (default HEAD) facilitates pulling files from tags or specific
  commits."

  [u untracked     bool   "Add untracked (but not ignored) files."
   r ref REF       str    "The git reference for the desired file tree."]

  (let [tgt (core/tmp-dir!)]
    (core/with-pre-wrap fileset
      (core/empty-dir! tgt)
      (util/info "Adding repo files...\n")
      (doseq [p (core/git-files :ref ref :untracked untracked)]
        (file/copy-with-lastmod (io/file p) (io/file tgt p)))
      (-> fileset (core/add-resource tgt) core/commit!))))

(core/deftask uber
  "Add jar entries from dependencies to fileset.

  Use this task before the packaging task (jar, war, etc.) to create uberjars,
  uberwars, etc. This provides the means to package the project with all of its
  dependencies included.

  By default, entries from dependencies with the following scopes will be copied
  to the fileset: compile, runtime, and provided. The include-scope and exclude-
  scope options may be used to add or remove scope(s) from this set.

  The as-jars option pulls in dependency jars without exploding them, such that
  the jarfiles themselves are copied into the fileset. When using as-jars you
  need a special classloader like a servlet container (e.g. Tomcat, Jetty) that
  picks up the packaged jars and places them on the classpath.

  When jars are exploded, the include and exclude options control what paths are
  added to the uberjar, with a path only being added if it matches an include
  regex and does not match any exclude regexes. exclude defaults to:

      #{#\"(?i)^META-INF/[^/]*\\.(MF|SF|RSA|DSA)$\"
        #\"^((?i)META-INF)/.*pom\\.(properties|xml)$\"
        #\"(?i)^META-INF/INDEX.LIST$\"}

  And include-path defaults to:

      #{#\".*\"}

  If exploding the jars results in duplicate entries, they will be merged using
  the rules specified by the merge option. A merge rule is a [regex fn] pair,
  where fn takes three parameters: an InputStream for the previous entry, an
  InputStream of the new entry, and an OutputStream that will replace the entry.
  merge defaults to:

      [[#\"data_readers.clj$\"    into-merger]
       [#\"META-INF/services/.*\" concat-merger]
       [#\".*\"                   first-wins-merger]]

  The merge rule regular expressions are tested in order, and the fn from the
  first match is applied.

  Setting the include, exclude, or merge options replaces the appropriate
  default."

  [j as-jars                bool           "Copy entire jar files instead of exploding them."
   s include-scope SCOPE    #{str}         "The set of scopes to add."
   S exclude-scope SCOPE    #{str}         "The set of scopes to remove."
   i include       MATCH    #{regex}       "The set of regexes that paths must match."
   e exclude       MATCH    #{regex}       "The set of regexes that paths must not match."
   m merge         REGEX=FN [[regex code]] "The list of duplicate file mergers."]

  (let [tgt        (core/tmp-dir!)
        dfl-scopes #{"compile" "runtime" "provided"}
        scopes     (-> dfl-scopes
                       (set/union include-scope)
                       (set/difference exclude-scope))
        scope?     #(contains? scopes (:scope (util/dep-as-map %)))
        jars       (-> (core/get-env)
                       (update-in [:dependencies] (partial filter scope?))
                       pod/resolve-dependency-jars)
        merge      (or merge pod/standard-jar-mergers)
        add-uber   (delay
                     (util/info "Adding uberjar entries...\n")
                     (doseq [jar jars]
                       (when-not (.endsWith (.getName jar) ".pom")
                         (if as-jars
                           (file/copy-with-lastmod jar (io/file tgt (.getName jar)))
                           (pod/unpack-jar jar tgt
                             :mergers merge
                             :include include
                             :exclude (or exclude pod/standard-jar-exclusions))))))]
    (core/with-pre-wrap fileset
      @add-uber
      (-> fileset (core/add-resource tgt :mergers merge) core/commit!))))

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
    (core/with-pre-wrap fileset
      (assert (and (symbol? serve) (namespace serve))
              (format "serve function must be namespaced symbol (%s)" serve))
      @webxml
      (-> fileset (core/add-resource tgt) core/commit!))))

(core/deftask aot
  "Perform AOT compilation of Clojure namespaces."

  [a all          bool   "Compile all namespaces."
   n namespace NS #{sym} "The set of namespaces to compile."]

  (let [tgt         (core/tmp-dir!)
        pod-env     (update-in (core/get-env) [:directories] conj (.getPath tgt))
        compile-pod (future (pod/make-pod pod-env))]
    (core/with-pre-wrap fileset
      (core/empty-dir! tgt)
      (let [all-nses (->> fileset core/fileset-namespaces)
            nses     (->> all-nses (set/intersection (if all all-nses namespace)) sort)]
        (pod/with-eval-in @compile-pod
          (binding [*compile-path* ~(.getPath tgt)]
            (doseq [[idx ns] (map-indexed vector '~nses)]
              (boot.util/info "Compiling %s/%s %s...\n" (inc idx) (count '~nses) ns)
              (compile ns)))))
      (-> fileset (core/add-resource tgt) core/commit!))))

(core/deftask javac
  "Compile java sources."
  [o options OPTIONS [str] "List of options passed to the java compiler."]
  (let [tgt (core/tmp-dir!)]
    (core/with-pre-wrap fileset
      (let [throw?    (atom nil)
            diag-coll (DiagnosticCollector.)
            compiler  (or (ToolProvider/getSystemJavaCompiler)
                          (throw (Exception. "The java compiler is not working. Please make sure you use a JDK!")))
            file-mgr  (.getStandardFileManager compiler diag-coll nil nil)
            opts      (->> ["-d"  (.getPath tgt)
                            "-cp" (System/getProperty "boot.class.path")]
                           (concat options)
                           (into-array String) Arrays/asList)
            handler   {Diagnostic$Kind/ERROR util/fail
                       Diagnostic$Kind/WARNING util/warn
                       Diagnostic$Kind/MANDATORY_WARNING util/warn}
            srcs      (some->> (core/input-files fileset)
                               (core/by-ext [".java"])
                               (map core/tmp-file)
                               (into-array File)
                               Arrays/asList
                               (.getJavaFileObjectsFromFiles file-mgr))]
        (when srcs
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
      (-> fileset (core/add-resource tgt) core/commit!))))

(core/deftask jar
  "Build a jar file for the project."

  [f file PATH        str       "The target jar file name."
   M manifest KEY=VAL {str str} "The jar manifest map."
   m main MAIN        sym       "The namespace containing the -main function."]

  (let [tgt (core/tmp-dir!)]
    (core/with-pre-wrap fileset
      (core/empty-dir! tgt)
      (let [pomprop (->> (core/output-files fileset)
                         (map core/tmp-file)
                         (core/by-name ["pom.properties"])
                         first)
            [aid v] (some->> pomprop
                             pod/pom-properties-map
                             ((juxt :artifact-id :version)))
            jarname (or file (and aid v (str aid "-" v ".jar")) "project.jar")
            jarfile (io/file tgt jarname)]
        (let [entries (core/output-files fileset)
              index   (->> entries (map (juxt core/tmp-path #(.getPath (core/tmp-file %)))))]
          (util/info "Writing %s...\n" (.getName jarfile))
          (pod/with-call-worker
            (boot.jar/spit-jar! ~(.getPath jarfile) ~index ~manifest ~main))
          (-> fileset (core/add-resource tgt) core/commit!))))))

(core/deftask war
  "Create war file for web deployment."

  [f file PATH str "The target war file name."]

  (let [tgt (core/tmp-dir!)]
    (core/with-pre-wrap fileset
      (core/empty-dir! tgt)
      (let [warname (or file "project.war")
            warfile (io/file tgt warname)
            inf?    #(contains? #{"META-INF" "WEB-INF"} %)]
        (let [->war   #(let [r    (core/tmp-path %)
                             r'   (file/split-path r)
                             path (->> (if (.endsWith r ".jar")
                                         ["lib" (last r')]
                                         (into ["classes"] r'))
                                       (into ["WEB-INF"]))]
                         (if (inf? (first r')) r (.getPath (apply io/file path))))
              entries (core/output-files fileset)
              index   (->> entries (mapv (juxt ->war #(.getPath (core/tmp-file %)))))]
          (util/info "Writing %s...\n" (.getName warfile))
          (pod/with-call-worker
            (boot.jar/spit-jar! ~(.getPath warfile) ~index {} nil))
          (-> fileset (core/add-resource tgt) core/commit!))))))

(core/deftask zip
  "Build a zip file for the project."

  [f file PATH str "The target zip file name."]

  (let [tgt (core/tmp-dir!)]
    (core/with-pre-wrap fileset
      (core/empty-dir! tgt)
      (let [zipname (or file "project.zip")
            zipfile (io/file tgt zipname)]
        (when-not (.exists zipfile)
          (let [entries (core/output-files fileset)
                index   (->> entries (map (juxt core/tmp-path #(.getPath (core/tmp-file %)))))]
            (util/info "Writing %s...\n" (.getName zipfile))
            (pod/with-call-worker
              (boot.jar/spit-zip! ~(.getPath zipfile) ~index))
            (-> fileset (core/add-resource tgt) core/commit!)))))))

(core/deftask install
  "Install project jar to local Maven repository.

  The file option allows installation of arbitrary jar files. If no file option
  is given then any jar artifacts created during the build will be installed.

  Note that installation requires the jar to contain a pom.xml file."

  [f file PATH str "The jar file to install."]

  (core/with-pre-wrap fileset
    (util/with-let [_ fileset]
      (let [jarfiles (or (and file [(io/file file)])
                         (->> (core/output-files fileset)
                              (core/by-ext [".jar"])
                              (map core/tmp-file)))]
        (when-not (seq jarfiles) (throw (Exception. "can't find jar file")))
        (doseq [jarfile jarfiles]
          (util/info "Installing %s...\n" (.getName jarfile))
          (pod/with-call-worker
            (boot.aether/install ~(core/get-env) ~(.getPath jarfile))))))))

(core/deftask push
  "Deploy jar file to a Maven repository.

  The repo option is required. If the file option is not specified the task will
  look for jar files created by the build pipeline. The jar file(s) must contain
  pom.xml entries."

  [f file PATH            str      "The jar file to deploy."
   F file-regex MATCH     #{regex} "The set of regexes of paths to deploy."
   g gpg-sign             bool     "Sign jar using GPG private key."
   k gpg-user-id NAME     str      "The name used to find the GPG key."
   K gpg-keyring PATH     str      "The path to secring.gpg file to use for signing."
   p gpg-passphrase PASS  str      "The passphrase to unlock GPG signing key."
   r repo ALIAS           str      "The alias of the deploy repository."
   t tag                  bool     "Create git tag for this version."
   B ensure-branch BRANCH str      "The required current git branch."
   C ensure-clean         bool     "Ensure that the project git repo is clean."
   R ensure-release       bool     "Ensure that the current version is not a snapshot."
   S ensure-snapshot      bool     "Ensure that the current version is a snapshot."
   T ensure-tag TAG       str      "The SHA1 of the commit the pom's scm tag must contain."
   V ensure-version VER   str      "The version the jar's pom must contain."]

  (let [tgt (core/tmp-dir!)]
    (core/with-pre-wrap fileset
      (util/with-let [_ fileset]
        (core/empty-dir! tgt)
        (let [jarfiles (or (and file [(io/file file)])
                           (->> (core/output-files fileset)
                                (core/by-ext [".jar"])
                                ((if (seq file-regex) #(core/by-re file-regex %) identity))
                                (map core/tmp-file)))
              repo-map (->> (core/get-env :repositories) (into {}))
              r        (get repo-map repo)]
          (when-not (and r (seq jarfiles))
            (throw (Exception. "missing jar file or repo not found")))
          (doseq [f jarfiles]
            (let [{{t :tag} :scm
                   v :version} (pod/with-call-worker (boot.pom/pom-xml-parse ~(.getPath f)))
                  b            (util/guard (git/branch-current))
                  clean?       (util/guard (git/clean?))
                  snapshot?    (.endsWith v "-SNAPSHOT")
                  artifact-map (when gpg-sign
                                 (util/info "Signing %s...\n" (.getName f))
                                 (helpers/sign-jar tgt f gpg-passphrase gpg-keyring gpg-user-id))]
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
                (boot.aether/deploy ~(core/get-env) ~[repo r] ~(.getPath f) ~artifact-map))
              (when tag
                (util/info "Creating tag %s...\n" v)
                (git/tag v "release")))))))))
