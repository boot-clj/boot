(ns boot.main
  (:require
   [clojure.java.io             :as io]
   [clojure.string              :as string]
   [boot.pod                    :as pod]
   [boot.core                   :as core]
   [boot.file                   :as file]
   [boot.util                   :as util]
   [boot.tmpregistry            :as tmp]
   [boot.from.clojure.tools.cli :as cli]))

(def cli-opts
  [
   ["-b" "--boot-script"         "Print generated boot script for debugging."]
   ["-d" "--dependencies ID:VER" "Add dependency to project (eg. -d riddley:0.1.7)."
    :assoc-fn #(let [[p v] (string/split %3 #":" 2)]
                 (update-in %1 [%2] (fnil conj []) [(read-string p) v]))]
   ["-e" "--set-env KEY=VAL"     "Add KEY => VAL to project env map."
    :assoc-fn #(let [[k v] (string/split %3 #"=" 2)]
                 (update-in %1 [%2] (fnil assoc {}) (keyword k) v))]
   ["-h" "--help"                "Print basic usage and help info."]
   ["-P" "--no-profile"          "Skip loading of profile.boot script."]
   ["-s" "--src-paths PATH"      "Add PATH to set of source directories."
    :assoc-fn #(update-in %1 [%2] (fnil conj #{}) %3)]
   ["-t" "--tgt-path PATH"       "Set the target directory to PATH."]
   ["-v" "--verbose"             "More error info (-vv more verbose, etc.)"
    :assoc-fn (fn [x y _] (update-in x [y] (fnil inc 0)))]
   ["-V" "--version"             "Print boot version info."]])

(defn- parse-cli-opts [args]
  ((juxt :errors :options :arguments)
   (cli/parse-opts args cli-opts :in-order true)))

(defn- with-comments [tag forms]
  (concat
    [`(comment ~(format "start %s" tag))]
    forms
    [`(comment ~(format "end %s" tag))]))

(defn emit [boot? argv userscript bootscript]
  `(~'(ns boot.user
        (:require
         [boot.task.built-in :refer :all]
         [boot.task-helpers  :refer :all]
         [boot.repl          :refer :all]
         [boot.cli           :refer :all]
         [boot.core          :refer :all]
         [boot.util          :refer :all]))
    ~@(when userscript (with-comments "profile" userscript))
    ~@(with-comments "boot script" bootscript)
    (let [boot?# ~boot?]
      (if-not boot?#
        (when-let [main# (resolve 'boot.user/-main)] (main# ~@argv))
        (core/boot ~@(or (seq argv) ["boot.task.built-in/help"]))))))

(defn -main [pod-id worker-pod shutdown-hooks [arg0 & args :as args*]]
  (reset! pod/pod-id pod-id)
  (reset! pod/worker-pod worker-pod)
  (reset! pod/shutdown-hooks shutdown-hooks)

  (let [dotboot?         #(.endsWith (.getName (io/file %)) ".boot")
        script?          #(when (and % (.isFile (io/file %)) (dotboot? %)) %)
        bootscript       (io/file "build.boot")
        [arg0 args]      (cond
                           (script? arg0)       [arg0 args]
                           (script? bootscript) [bootscript args*]
                           :else                [nil args*])
        boot?            (contains? #{nil bootscript} arg0)
        [errs opts args] (if-not boot? [nil {} args] (parse-cli-opts args))]
    
    (when (seq errs)
      (util/exit-error
        (println (apply str (interpose "\n" errs)))))

    (binding [*out* (util/auto-flush *out*)
              *err* (util/auto-flush *err*)
              core/*boot-opts* opts
              core/*boot-version* (str (boot.App/getVersion) "-" (boot.App/getRelease))]
      (util/exit-ok
        (let [userscript  (script? (io/file (boot.App/getBootDir) "profile.boot"))
              profile?    (not (:no-profile opts))
              bootforms   (some->> arg0 slurp util/read-string-all)
              userforms   (when profile? (some->> userscript slurp util/read-string-all))
              scriptforms (emit boot? args userforms bootforms)
              scriptstr   (str (string/join "\n\n" (map pr-str scriptforms)) "\n")
              initial-env (->> [:src-paths :tgt-path :dependencies]
                             (reduce #(if-let [v (opts %2)] (assoc %1 %2 v) %1) {})
                             (merge {} (:set-env opts)))]

          (swap! util/verbose-exceptions + (or (:verbose opts) 0))
          (when (:boot-script opts) (util/exit-ok (print scriptstr)))
          (when (:version opts) (util/exit-ok (println core/*boot-version*)))

          (reset! (var-get #'core/tmpregistry)
            (tmp/init! (tmp/registry (io/file ".boot" "tmp"))))

          (#'core/init!)

          (let [tmpf (.getPath (file/tmpfile "boot.user" ".clj"))]
            (pod/call-worker `(boot.aether/load-wagon-mappings))
            (apply core/set-env! (->> initial-env (mapcat identity) seq))
            (try (doto tmpf (spit scriptstr) (load-file))
                 (catch clojure.lang.Compiler$CompilerException cx
                   (let [l (.-line cx)
                         s (->> (io/file (.-source cx))
                             (file/relative-to (io/file "."))
                             .getPath)
                         c (.getCause cx)
                         m (.getMessage (or c cx))
                         x (or c cx)]
                     (throw (ex-info m (sorted-map :file s :line l) x)))))))))))
