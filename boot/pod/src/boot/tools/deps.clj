(ns boot.tools.deps
  "Dependency management using tools.deps."
  {:boot/export-tasks true})

(deftask deps
  "Use tools.deps to read and resolve the specified deps.edn files.

  The dependencies read in are added to your Boot classpath. There are two
  options to update Boot's :dependencies if additional tasks in your pipeline
  rely on that: -B will completely overwrite the Boot :dependencies with the
  ones produced by tools.deps and should be used when you are creating uber
  jars; -Q will perform a quick merge of the dependencies from tools.deps into
  the Boot environment and may be needed for certain testing tools.

  As much as possible, the recommended approach is to avoid using the Boot
  :dependencies vector when using boot-tools-deps so that deps.edn represents
  the total dependencies for your project.

  Most of the arguments are intended to match the clj script usage
  (as passed to clojure.tools.deps.alpha.script.make-classpath/-main).

  In particular, the -c / --config-paths option is assumed to be the COMPLETE
  list of EDN files to read (and therefore overrides the default set of
  system deps, user deps, and local deps).

  The -r option is intended to be equivalent to the -Srepro option in
  tools.deps, which will exclude both the system deps and the user deps.

  The -D option is intended to be the equivalent to the -Sdeps option, which
  allows you to specify an additional deps.edn-like map of dependencies which
  are added to the set of deps.edn-derived dependencies (even when -r is
  given).

  The -A, -C, -M, and -R options mirror the clj script usage for aliases.

  The -x option will run clojure.main with any main-opts found by deps.edn.

  The -v option makes boot-tools-deps verbose, explaining which files it looked
  for, the dependencies it got back from tools.dep, and the changes it made to
  Boot's classpath, :resource-paths, and :source-path. If you specify it twice
  (-vv) then tools.deps will also be verbose about its work."
  
  [;; options that mirror tools.deps itself:
   c config-paths    PATH [str] "the list of deps.edn files to read."
   r repeatable           bool  "Use only the specified deps.edn file for a repeatable build."
   D config-data      EDN edn   "is treated as a final deps.edn file."
   A aliases           KW [kw]  "the list of aliases (for -C, -M, and -R)."
   C classpath-aliases KW [kw]  "the list of classpath aliases to use."
   M main-aliases      KW [kw]  "the list of main-opt aliases to use."
   R resolve-aliases   KW [kw]  "the list of resolve aliases to use."
   ;; options specific to boot-tools-deps
   B overwrite-boot-deps  bool  "Overwrite Boot's :dependencies."
   Q quick-merge          bool  "Merge into Boot's :dependencies."
   v verbose              int   "the verbosity level."
   x execute              bool  "Execute clojure.main with any main-opts found."]
  (let [{:keys [main-opts]}
        (load-deps {:config-paths        config-paths
                    :config-data         config-data
                    :classpath-aliases   (into (vec aliases) classpath-aliases)
                    :main-aliases        (into (vec aliases) main-aliases)
                    :resolve-aliases     (into (vec aliases) resolve-aliases)
                    :overwrite-boot-deps overwrite-boot-deps
                    :quick-merge         quick-merge
                    :repeatable          repeatable
                    :verbose             verbose})]
    (boot/with-pass-thru fs
      (when execute
        (when verbose
          (println "Executing clojure.main" (str/join " " main-opts)))
        (apply clojure.main/main main-opts)))))
