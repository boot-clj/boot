(ns boot.main
  (:import
    [boot App])
  (:require
    [clojure.java.io             :as io]
    [clojure.string              :as string]
    [clojure.pprint              :as pp]
    [boot.pod                    :as pod]
    [boot.core                   :as core]
    [boot.file                   :as file]
    [boot.util                   :as util]
    [boot.from.clojure.tools.cli :as cli]))

(def cli-opts
  [["-a" "--asset-paths PATH"    "Add PATH to set of asset directories."
    :assoc-fn #(update-in %1 [%2] (fnil conj #{}) %3)]
   ["-b" "--boot-script"         "Print generated boot script for debugging."]
   ["-B" "--no-boot-script"      "Ignore boot script in current directory."]
   ["-c" "--checkouts SYM:VER"   "Add checkout dependency (eg. -c foo/bar:1.2.3)."
    :assoc-fn #(let [[p v] (string/split %3 #":" 2)]
                 (update-in %1 [%2] (fnil conj [])
                            (pod/canonical-coord [(read-string p) (or v "(0,)")])))]
   ["-C" "--no-colors"           "Remove ANSI escape codes from printed output."]
   ["-d" "--dependencies SYM:VER" "Add dependency to project (eg. -d foo/bar:1.2.3)."
    :assoc-fn #(let [[p v] (string/split %3 #":" 2)]
                 (update-in %1 [%2] (fnil conj [])
                            (pod/canonical-coord [(read-string p) (or v "RELEASE")])))]
   ["-E" "--exclusions SYM"      "Add the SYM dependency to the set of global exclusions."
    :assoc-fn #(update-in %1 [%2] (fnil conj #{}) (symbol %3))]
   ["-e" "--set-env KEY=VAL"     "Add KEY => VAL to project env map."
    :assoc-fn #(let [[k v] (string/split %3 #"=" 2)]
                 (update-in %1 [%2] (fnil assoc {}) (keyword k) v))]
   ["-i" "--init EXPR"           "Evaluate EXPR in the boot.user context."
    :assoc-fn #(update-in %1 [%2] (fnil conj []) (read-string %3))]
   ["-f" "--file PATH"           "Evaluate PATH (implies -BP). Args and options passed to -main."]
   ["-h" "--help"                "Print basic usage and help info."]
   ["-o" "--offline"             "Don't attempt to access remote repositories." :id :offline?]
   ["-P" "--no-profile"          "Skip loading of profile.boot script."]
   ["-r" "--resource-paths PATH" "Add PATH to set of resource directories."
    :assoc-fn #(update-in %1 [%2] (fnil conj #{}) %3)]
   ["-q" "--quiet"               "Suppress output from boot itself."]
   ["-s" "--source-paths PATH"   "Add PATH to set of source directories."
    :assoc-fn #(update-in %1 [%2] (fnil conj #{}) %3)]
   ["-u" "--update"              "Update boot to latest release version."]
   ["-U" "--update-snapshot"     "Update boot to latest snapshot version."]
   ["-v" "--verbose"             "More error info (-vv more verbose, etc.)"
    :assoc-fn (fn [x y _] (update-in x [y] (fnil inc 0)))]
   ["-V" "--version"             "Print boot version info."]
   ["-x" "--exclude-clojure"     "Add org.clojure/clojure to the set of global exclusions."]])

(defn- dep-ns-decls
  [jar]
  (binding [*print-meta* true]
    (pod/with-eval-worker
      (require '[clojure.tools.namespace.find :as nsf])
      (with-open [jf (java.util.jar.JarFile. ~(.getPath (io/file jar)))]
        (into [] (nsf/find-ns-decls-in-jarfile jf))))))

(defn- export-tasks?
  [[_ name docstring? attr-map?]]
  (->> [docstring? attr-map?]
    (filter map?)
    first
    (merge (meta name))
    :boot/export-tasks))

(defn- export-task-namespaces
  [env]
  (-> #(->> (pod/resolve-dependency-jar env %)
         dep-ns-decls (filter export-tasks?) (map second))
    (mapcat (:dependencies env))))

(defn- parse-cli-opts [args]
  ((juxt :errors :options :arguments)
   (cli/parse-opts args cli-opts :in-order true)))

(defn- with-comments [tag forms]
  (concat
    [`(comment ~(format "start %s" tag))]
    forms
    [`(comment ~(format "end %s" tag))]))

(defn emit [boot? argv userscript localscript bootscript import-ns inits]
  (let [boot-use '[boot.core boot.util boot.task.built-in]]
    `(~(list 'ns 'boot.user
         (list* :use (concat boot-use import-ns)))
      ~@(when userscript (with-comments "global profile" userscript))
      ~@(when localscript (with-comments "local profile" localscript))
      ~@(when (seq inits) (with-comments "--init exprs" inits))
      ~@(with-comments "boot script" bootscript)
      (let [boot?# ~boot?]
        (if-not boot?#
          (when-let [main# (resolve 'boot.user/-main)] (main# ~@argv))
          (core/boot ~@(or (seq argv) ["boot.task.built-in/help"])))))))

(defn shebang? [arg]
  (when (and (<= 0 (.indexOf arg (int \/))) (.exists (io/file arg)))
    (let [bang-line (str (first (string/split (slurp arg) #"\n")))
          full-path (System/getProperty "boot.app.path")
          base-path (.getName (io/file full-path))
          full-pat  (re-pattern (format "^#!\\s*\\Q%s\\E(?:\\s+.*)?$" full-path))
          base-pat  (re-pattern (format "^#!\\s*/usr/bin/env\\s+\\Q%s\\E(?:\\s+.*)?$" base-path))]
      (or (re-find full-pat bang-line) (re-find base-pat bang-line)))))

(defn pr-boot-form [form]
  (if (<= @util/*verbosity* 1)
    (pr-str form)
    (let [[op & [msg & more]] form]
      (if (and (= op 'clojure.core/comment) (not more) (string? msg))
        (format ";; %s" msg)
        (with-out-str (pp/write form :dispatch pp/code-dispatch))))))

(defn parse-bootignore [f]
  (when (.isFile f)
    (->> (string/split (slurp f) #"\n") (remove string/blank?) (map re-pattern) set)))

(defn -main [pod-id worker-pod shutdown-hooks [arg0 & args :as args*]]
  (when (not= (boot.App/getVersion) (boot.App/getBootVersion))
    (let [url "https://github.com/boot-clj/boot#install"]
      (util/exit-error
        (println (format "Please download latest Boot binary: %s" url)))))

  (pod/set-pod-id! pod-id)
  (pod/set-worker-pod! worker-pod)
  (reset! pod/shutdown-hooks shutdown-hooks)

  (let [[arg0 args args*] (if (seq args*)
                            [arg0 args args*]
                            ["--help" nil ["--help"]])
        bootscript        (App/config "BOOT_FILE" "build.boot")
        exists?           #(when (.isFile (io/file %)) %)
        have-bootscript?  (exists? bootscript)
        [arg0 args]       (cond
                            (shebang? arg0)  [arg0 args]
                            have-bootscript? [bootscript args*]
                            :else            [nil args*])
        boot?             (contains? #{nil bootscript} arg0)
        [errs opts args]  (if-not boot? [nil {} args] (parse-cli-opts args))
        opts              (if-let [x (:exclude-clojure opts)]
                            (-> (dissoc opts :exclude-clojure)
                                (update-in [:exclusions] (fnil conj #{}) 'org.clojure/clojure))
                            opts)
        arg0              (or (:file opts) (if (:no-boot-script opts) nil arg0))
        boot?             (and boot? (not (:file opts)))
        verbosity         (if (:quiet opts)
                            (* -1 @util/*verbosity*)
                            (or (:verbose opts) 0))]

    (when (seq errs)
      (util/exit-error
        (println (apply str (interpose "\n" errs)))))

    (when (:no-colors opts)
      (reset! util/*colorize?* false))

    (swap! util/*verbosity* + verbosity)

    (pod/with-eval-in worker-pod
      (require '[boot.util :as util])
      (swap! util/*verbosity* + ~verbosity))

    (binding [*out*               (util/auto-flush *out*)
              *err*               (util/auto-flush *err*)
              core/*boot-opts*    opts
              core/*boot-script*  arg0
              core/*boot-version* (boot.App/getBootVersion)
              core/*app-version*  (boot.App/getVersion)]

      (util/exit-ok
        (let [userscript  (util/with-let [x (-> (System/getProperty "user.home")
                                                (io/file ".profile.boot")
                                                exists?)]
                            (when x
                              (util/warn "** WARNING: ~/.profile.boot is deprecated.\n")
                              (util/warn "** Please use $BOOT_HOME/profile.boot instead.\n")
                              (util/warn "** See: https://github.com/boot-clj/boot/issues/157\n")))
              userscript  (or userscript (exists? (io/file (App/getBootDir) "profile.boot")))
              localscript (exists? (io/file "profile.boot"))
              profile?    (not (:no-profile opts))
              bootforms   (some->> arg0 slurp util/read-string-all)
              userforms   (when profile?
                            (some->> userscript slurp util/read-string-all))
              localforms  (when profile?
                            (some->> localscript slurp util/read-string-all))
              initial-env (->> [:source-paths :resource-paths :asset-paths
                                :dependencies :exclusions :checkouts :offline?]
                               (reduce #(if-let [v (opts %2)] (assoc %1 %2 v) %1) {})
                               (merge {} (:set-env opts)))
              import-ns   (export-task-namespaces initial-env)
              scriptforms (emit boot? args userforms localforms bootforms import-ns (:init opts))
              scriptstr   (binding [*print-meta* true]
                            (str (string/join "\n\n" (map pr-boot-form scriptforms)) "\n"))]

          (when (:boot-script opts) (util/exit-ok (print scriptstr)))

          (when (:version opts) (util/exit-ok (boot.App/printVersion)))

          (reset! core/bootignore (parse-bootignore (io/file ".bootignore")))

          (#'core/init!)

          (let [tmpf (.getPath (file/tmpfile "boot.user" ".clj"))]
            (pod/with-call-worker (boot.aether/load-wagon-mappings))
            (apply core/set-env! (->> initial-env (mapcat identity) seq))
            (reset! @#'core/cli-base initial-env)
            (try (doto tmpf (spit scriptstr) (load-file))
                 (catch clojure.lang.Compiler$CompilerException cx
                   (let [l (.-line cx)
                         s (->> (io/file (.-source cx)) .getPath)
                         c (.getCause cx)
                         m (.getMessage (or c cx))
                         x (or c cx)]
                     (throw (ex-info m (sorted-map :file s :line l) x)))))))))))
