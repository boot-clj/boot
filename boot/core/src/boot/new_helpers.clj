(ns boot.new-helpers
  "The top-level logic for the new task. This namespace is dynamically
  loaded into the new task so as to reduce and delay dependencies."
  (:require [clojure.string :as str]
            [boot.core :as core]
            [boot.new.templates :as bnt]
            [boot.util :as util]
            ;; this is Boot's version with no Leiningen dependencies:
            [leiningen.new.templates :as lnt])
  (:import java.io.FileNotFoundException))

(def ^:dynamic *debug* nil)
(def ^:dynamic *use-snapshots?* false)
(def ^:dynamic *template-version* nil)

(defn resolve-remote-template
  "Given a template name, attempt to resolve it as a Boot template first,
  then as a Leiningen template. Return the type of template we found."
  [template-name]
  (let [selected (atom nil)
        failure  (atom nil)
        output
        (with-out-str
          (binding [*err* *out*]
            (try
              (core/merge-env! :dependencies [[(symbol (str template-name "/boot-template"))
                                               (cond *template-version* *template-version*
                                                     *use-snapshots?*   "(0.0.0,)"
                                                     :else              "RELEASE")]])
              (reset! selected :boot)
              (catch Exception e
                (when (and *debug* (> *debug* 2))
                  (println "Unable to find Boot template:")
                  (clojure.stacktrace/print-stack-trace e))
                (reset! failure e)
                (try
                  (core/merge-env! :dependencies [['leiningen-core "2.5.3"]
                                                  ['slingshot "0.10.3"]
                                                  [(symbol (str template-name "/lein-template"))
                                                   (cond *template-version* *template-version*
                                                         *use-snapshots?*   "(0.0.0,)"
                                                         :else              "RELEASE")]])
                  (reset! selected :leiningen)
                  (catch Exception e
                    (when (and *debug* (> *debug* 1))
                      (println "Unable to find Leiningen template:")
                      (clojure.stacktrace/print-stack-trace e))
                    (reset! failure e)))))))]
    (when *debug*
      (println "Output from locating template:")
      (println output))
    (if @selected
      (try
        (require (symbol (str (name @selected) ".new." template-name)))
        @selected
        (catch Exception e
          (when *debug*
            (when (> *debug* 3)
              (println "Boot environment at failure:" (core/get-env)))
            (println "Unable to require the template symbol:")
            (clojure.stacktrace/print-stack-trace e)
            (when (> *debug* 1)
              (clojure.stacktrace/print-cause-trace e)))
          (util/exit-error (println "Could not load template, failed with:" (.getMessage e)))))
      (util/exit-error (println output)
                       (println "Could not load template, failed with:" (.getMessage @failure))))))

(defn resolve-template
  "Given a template name, resolve it to a symbol (or exit if not possible)."
  [template-name]
  (if-let [type (try (require (symbol (str "boot.new." template-name)))
                     :boot
                     (catch FileNotFoundException _
                       (resolve-remote-template template-name)))]
    (resolve (symbol (str (name type) ".new." template-name) template-name))
    (util/exit-error (println "Could not find template" template-name "on the classpath."))))

(defn create*
  "Given a template name, a project name and list of template arguments,
  perform sanity checking on the project name and, if it's sane, then
  generate the project from the template."
  [template-name project-name args]
  (cond
    (and (re-find #"(?i)(?<!(clo|compo))jure" project-name)
         (not (System/getenv "BOOT_IRONIC_JURE")))
    (util/exit-error (println "Sorry, names such as clojure or *jure are not allowed."
                              "\nIf you intend to use this name ironically, please set the"
                              "\nBOOT_IRONIC_JURE environment variable and try again."))
    (and (re-find #"(?i)(?<!(cl|comp))eaxure" project-name)
         (not (System/getenv "BOOT_IRONIC_EAXURE")))
    (util/exit-error (println "Sorry, names such as cleaxure or *eaxure are not allowed."
                              "\nIf you intend to use this name ironically, please set the"
                              "\nBOOT_IRONIC_EAXURE environment variable and try again."))
    (= project-name "clojure")
    (util/exit-error (println "Sorry, clojure can't be used as a project name."
                              "\nIt will confuse Clojure compiler and cause obscure issues."))
    (and (re-find #"[A-Z]" project-name)
         (not (System/getenv "BOOT_BREAK_CONVENTION")))
    (util/exit-error (println "Project names containing uppercase letters are not recommended"
                              "\nand will be rejected by repositories like Clojars and Central."
                              "\nIf you're truly unable to use a lowercase name, please set the"
                              "\nBOOT_BREAK_CONVENTION environment variable and try again."))
    (not (symbol? (try (read-string project-name) (catch Exception _))))
    (util/exit-error (println "Project names must be valid Clojure symbols."))
    :else (apply (resolve-template template-name) project-name args)))

(defn create
  "Exposed to Boot new task with simpler signature."
  [{:keys [args force name snapshot template template-version to-dir verbose]
    :or {template "default"}}]
  (binding [*debug*            verbose
            *use-snapshots?*   snapshot
            *template-version* template-version
            bnt/*dir*          to-dir
            bnt/*force?*       force
            lnt/*dir*          to-dir
            lnt/*force?*       force]
    (create* template name args)))

(defn generate-code*
  "Given an optional template name, an optional path prefix, a list of
  things to generate (type, type=name), and an optional set of arguments
  for the generator, resolve the template (if provided), and then resolve
  and apply each specified generator."
  [template-name prefix generations args]
  (when template-name (resolve-template template-name))
  (doseq [thing generations]
    (let [[gen-type gen-arg] (str/split thing #"=")
          _ (try (require (symbol (str "boot.generate." gen-type))) (catch Exception _))
          generator (resolve (symbol (str "boot.generate." gen-type) "generate"))]
      (if generator
        (apply generator prefix gen-arg args)
        (println (str "Unable to resolve boot.generate."
                      gen-type
                      "/generate -- ignoring: "
                      gen-type
                      (when gen-arg (str "=\"" gen-arg "\""))))))))

(defn generate-code
  "Exposed to Boot new task with simpler signature."
  [{:keys [args force generate prefix template]
    :or {prefix "src"}}]
  (binding [bnt/*dir*        "."
            bnt/*force?*     force
            bnt/*overwrite?* false]
    (generate-code* template (or prefix "src") generate args)))

(defn template-show
  "Show details for a given template."
  [template-name]
  (let [resolved (meta (resolve-template template-name))]
    (println (:doc resolved "No documentation available."))
    (println)
    (println "Argument list:" (or (:help-arglists resolved)
                                  (:arglists resolved)))))
