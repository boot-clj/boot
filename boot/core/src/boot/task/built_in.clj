(ns boot.task.built-in
  (:require
   [clojure.java.io      :as io]
   [clojure.set          :as set]
   [clojure.pprint       :as pprint]
   [clojure.string       :as string]
   [boot.pod             :as pod]
   [boot.file            :as file]
   [boot.core            :as core]
   [boot.main            :as main]
   [boot.util            :as util]
   [boot.gitignore       :as git]
   [boot.task-helpers    :as helpers]
   [boot.from.table.core :as table])
  (:import
   [java.util.concurrent LinkedBlockingQueue TimeUnit]))

;; Tasks ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(core/deftask help
  "Print usage info and list available tasks.

  Here is some other stuff."
  []
  (core/with-pre-wrap
    (let [tasks (#'helpers/available-tasks 'boot.user)
          opts  (->> main/cli-opts (mapv (fn [[x y z]] ["" (str x " " y) z])))]
      (printf "%s\n\n" (#'helpers/version-str))
      (printf "%s\n"
        (-> [[""       ""]
             ["Usage:" "boot OPTS <task> TASK_OPTS <task> TASK_OPTS ..."]]
          (table/table :style :none)
          with-out-str))
      (printf "%s\nDo `boot <task> -h` to see usage info and TASK_OPTS for <task>.\n"
        (-> [["" "" ""]]
          (into (#'helpers/set-title (conj opts ["" "" ""]) "OPTS:"))
          (into (#'helpers/set-title (#'helpers/tasks-table tasks) "Tasks:"))
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

(core/deftask print-deps
  "Print the project's dependency graph."
  []
  (core/with-pre-wrap
    (print (pod/call-worker `(boot.aether/dep-tree ~(core/get-env))))))

(core/deftask print-env
  "Print the boot environment map."
  []
  (core/with-pre-wrap (prn (core/get-env))))

(core/deftask print-event
  "Print the event map."
  []
  (core/with-pre-wrap (prn core/*event*)))

(core/deftask wait
  "Wait before calling the next handler.

  Waits forever if the --time option is not specified."

  [t time MSEC int "The interval in milliseconds."]

  (core/with-pre-wrap
    (if (zero? (or time 0)) @(promise) (Thread/sleep time))))

(core/deftask watch
  "Call the next handler whenever source files change.

  Debouncing time is 10ms by default."

  [d debounce MSEC long "The time to wait (millisec) for filesystem to settle down."]

  (.require @pod/worker-pod (into-array String ["boot.watcher"]))
  (let [ms   TimeUnit/MILLISECONDS
        q    (LinkedBlockingQueue.)
        ps   (into-array String (:src-paths (core/get-env)))
        ps   (->> (core/get-env) :src-paths
               (remove core/tmpfile?) (into-array String))
        k    (.invoke @pod/worker-pod "boot.watcher/make-watcher" q ps)
        ign? (git/make-gitignore-matcher (core/get-env :src-paths))]
    (fn [continue]
      (fn [event]
        (continue event)
        (loop [ret (util/guard [(.take q)])]
          (when ret
            (if-let [more (.poll q (or debounce 10) TimeUnit/MILLISECONDS)]
              (recur (conj ret more))
              (let [start   (System/currentTimeMillis)
                    etime   #(- (System/currentTimeMillis) start)
                    changed (->> ret (remove (comp ign? io/file)) set)]
                (when-not (empty? changed)
                  (continue (assoc (core/make-event event) ::watch changed))
                  (util/info "Elapsed time: %.3f sec\n" (float (/ (etime) 1000))))
                (recur (util/guard [(.take q)]))))))
        (.invoke @pod/worker-pod "boot.watcher/stop-watcher" k)))))

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

(core/deftask pom
  "Write the project's pom.xml file."

  [p project SYM      sym      "The project id (eg. foo/bar)."
   v version VER      str      "The project version."
   d description DESC str      "The project description."
   u url URL          str      "The project homepage url."
   l license KEY=VAL  {kw str} "The project license map (KEY in name, url)."
   s scm KEY=VAL      {kw str} "The project scm map (KEY in url, tag)."]

  (let [tgt  (core/mktgtdir! ::pom-tgt)
        opts (assoc *opts* :dependencies (:dependencies (core/get-env)))]
    (core/with-pre-wrap
      (when-not (and project version)
        (throw (Exception. "need project and version to create pom.xml")))
      (let [[gid aid] (util/extract-ids project)
            pomdir    (io/file tgt "META-INF" "maven" gid aid)
            xmlfile   (io/file pomdir "pom.xml")
            propfile  (io/file pomdir "pom.properties")]
        (when-not (and (.exists xmlfile) (.exists propfile))
          (pod/call-worker
            `(boot.pom/spit-pom! ~(.getPath xmlfile) ~(.getPath propfile) ~opts)))))))

(core/deftask add-dir
  "Add files in resource directories to fileset."

  [d dirs PATH #{str} "The set of resource directories."]

  (let [tgt (core/mktgtdir! ::add-dir-tgt)]
    (core/with-pre-wrap
      (apply file/sync :time tgt dirs))))

(core/deftask add-src
  "Add source files to fileset."
  []
  (let [tgt (core/mktgtdir! ::add-srcs-tgt)]
    (core/with-pre-wrap
      (doseq [in (remove core/tmpfile? (core/src-files))]
        (let [out (io/file tgt (core/relative-path in))]
          (when-not (.exists out) (io/copy in (doto out io/make-parents))))))))

(core/deftask uber
  "Add files from dependency jars to fileset.

  By default, files from dependencies with the following scopes will be included
  in the fileset: compile, runtime, and provided."

  [x exclude-scope SCOPE #{str} "The set of excluded scopes."]

  (let [tgt        (core/mktgtdir! ::uber-tgt)
        dfl-scopes #{"compile" "runtime" "provided"}
        scopes     (set/difference dfl-scopes exclude-scope)]
    (core/with-pre-wrap
      (let [scope? #(contains? scopes (:scope (util/dep-as-map %)))
            urls   (-> (core/get-env)
                     (update-in [:dependencies] (partial filter scope?))
                     pod/jar-entries-in-dep-order)]
        (doseq [[relpath url-str] urls]
          (let [segs    (file/split-path relpath)
                outfile (apply io/file tgt segs)]
            (when-not (or (.exists outfile) (= "META-INF" (first (file/split-path relpath))))
              (pod/copy-url url-str outfile))))))))

(core/deftask web
  "Create project web.xml file.

  The --serve option is required. The others are optional."

  [s serve SYM        sym "The 'serve' callback function."
   c create SYM       sym "The 'create' callback function."
   d destroy SYM      sym "The 'destroy' callback function."]

  (let [tgt     (core/mktgtdir! ::web-tgt)
        xmlfile (io/file tgt "WEB-INF" "web.xml")
        clsfile (io/file tgt "WEB-INF" "classes" "tailrecursion" "ClojureAdapterServlet.class")]
    (core/with-pre-wrap
      (when-not (and (.exists xmlfile) (.exists clsfile))
        (-> (and (symbol? serve) (namespace serve))
          (assert "no serve function specified"))
        (pod/call-worker
          `(boot.web/spit-web! ~(.getPath xmlfile) ~(.getPath clsfile) ~serve ~create ~destroy))))))

(core/deftask jar
  "Build a jar file for the project."

  [f file PATH        str       "The target jar file."
   M manifest KEY=VAL {str str} "The jar manifest map."
   m main MAIN        sym       "The namespace containing the -main function."]

  (let [tgt (core/mktgtdir! ::jar-tgt)]
    (core/with-pre-wrap
      (let [pomprop (->> (core/tgt-files) (core/by-name ["pom.properties"]) first)
            [aid v] (some->> pomprop pod/pom-properties-map ((juxt :artifact-id :version)))
            jarname (or file (and aid v (str aid "-" v ".jar")) "project.jar")
            jarfile (io/file tgt jarname)]
        (when-not (.exists jarfile)
          (let [index (->> (core/tgt-files)
                        (map (juxt core/relative-path (memfn getPath))))]
            (pod/call-worker
              `(boot.jar/spit-jar! ~(.getPath jarfile) ~index ~manifest ~main))
            (doseq [[_ f] index] (core/consume-file! (io/file f)))))))))

(core/deftask war
  "Create war file for web deployment."

  [f file PATH str "The target war file."]

  (let [tgt (core/mktgtdir! ::war-tgt)]
    (core/with-pre-wrap
      (let [warname (or file "project.war")
            warfile (io/file tgt warname)]
        (when-not (.exists warfile)
          (let [->war #(let [r  (core/relative-path %)
                             r' (file/split-path r)]
                         (if (contains? #{"META-INF" "WEB-INF"} (first r'))
                           r
                           (.getPath (apply io/file "WEB-INF" "classes" r'))))
                index (->> (core/tgt-files) (map (juxt ->war (memfn getPath))))]
            (pod/call-worker
              `(boot.jar/spit-jar! ~(.getPath warfile) ~index {} nil))
            (doseq [[_ f] index] (core/consume-file! (io/file f)))))))))

(core/deftask install
  "Install project jar to local Maven repository."

  [f file PATH str "The jar file to install."]

  (core/with-pre-wrap
    (let [jarfiles (or (and file [(io/file file)])
                     (->> (core/tgt-files) (core/by-ext [".jar"])))]
      (when-not (seq jarfiles) (throw (Exception. "can't find jar file")))
      (doseq [jarfile jarfiles]
        (util/info "Installing %s...\n" (.getName jarfile))
        (pod/call-worker
          `(boot.aether/install ~(.getPath jarfile)))))))

(core/deftask push
  "Push project jar to Clojars."

  [f file PATH str "The jar file to push to Clojars."]

  (let [tmp (core/mktmpdir! ::push-tmp)]
    (core/with-pre-wrap
      (let [jarfiles (or (and file [(io/file file)])
                       (->> (core/tgt-files) (core/by-ext [".jar"])))]
        (when-not (seq jarfiles) (throw (Exception. "can't find jar file")))
        (doseq [jarfile jarfiles]
          (if-let [xml (pod/pom-xml jarfile)]
            (let [pom (doto (io/file tmp "pom.xml") (spit xml))]
              ((helpers/sh "scp" (.getPath jarfile) (.getPath pom) "clojars@clojars.org:")))
            (throw (Exception. "jar file has no pom.xml"))))))))
