(ns tailrecursion.boot.core.task
  (:refer-clojure :exclude [print])
  (:require
    [clojure.java.io                :refer [resource]]
    [clojure.pprint                 :refer [pprint print-table]]
    [clojure.string                 :refer [split join blank?]]
    [tailrecursion.boot.table.core  :refer [table]]
    [tailrecursion.boot.core        :refer [deftask]]))

(defn first-line [s] (when s (first (split s #"\n"))))
(defn not-blank? [s] (when-not (blank? s) s))

(defn get-doc [sym]
  (when (symbol? sym)
    (when-let [ns (namespace sym)] (require (symbol ns))) 
    (join "\n" (-> sym find-var meta :doc str (split #" *\n *")))))

(defn print-tasks [tasks]
  (let [get-task  #(-> % str (subs 1))
        get-desc  #(or (-> % :doc first-line not-blank?)
                       (-> % :main first get-doc first-line))
        get-row   (fn [[k v]] [(get-task k) (get-desc v)])]
    (with-out-str (table (into [["" ""]] (map get-row tasks)) :style :none))))

(defn pad-left [thing lines]
  (let [pad (apply str (repeat (count thing) " "))
        pads (concat [thing] (repeat pad))]
    (join "\n" (map (comp (partial apply str) vector) pads lines))))

(defn version-info []
  (let [[_ proj vers & kvs]
        (try (read-string (slurp (resource "project.clj")))
          (catch Throwable _))
        {desc :description url :url lic :license}
        (into {} (map (partial apply vector) (partition 2 kvs)))]
    {:proj proj, :vers vers, :desc desc, :url url, :lic lic}))

(defn version-str []
  (let [{:keys [proj vers desc url lic]} (version-info)]
    (str (format "%s %s: %s\n" (name proj) vers desc))))

;; CORE TASKS ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftask nop
  "Does nothing."
  [boot]
  (fn [continue] (fn [event] (continue event))))

(deftask help
  "Print this help info.
  
  Some things more..."
  [boot]
  (let [tasks (:tasks @boot)]
    (fn [continue]
      (fn [event]
        (println)
        (println (version-str))
        (-> ["boot task ..." "boot [task arg arg] ..." "boot [help task]"]
            (->> (pad-left "Usage: ") println))
        (println)
        (println (pad-left "Tasks: " (split (print-tasks tasks) #"\n"))) 
        (println)
        (flush)
        (continue event)))))
