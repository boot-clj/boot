(ns boot.task-helpers
  (:require
   [clojure.set               :as set]
   [clojure.string            :as string]
   [clojure.stacktrace        :as trace]
   [boot.pod                  :as pod]
   [boot.core                 :as core]
   [boot.file                 :as file]
   [boot.gitignore            :as git]
   [boot.from.me.raynes.conch :as conch]))

(defn- first-line [s] (when s (first (string/split s #"\n"))))

(defn tasks-table [tasks]
  (let [get-task  #(-> % :name str)
        get-desc  #(-> % :doc first-line)]
    (->> tasks (map (fn [x] ["" (get-task x) (get-desc x)])))))

(defn set-title [[[_ & xs] & zs] title] (into [(into [title] xs)] zs))

(defn version-str []
  (format "Boot Version:  %s\nDocumentation: %s"
    (core/get-env :boot-version) "http://github.com/tailrecursion/boot"))

(defn available-tasks [sym]
  (let [base  {nil (the-ns sym)}
        task? #(:boot.core/task %)
        addrf #(if-not (seq %1) %2 (symbol %1 (str %2)))
        proc  (fn [a [k v]] (assoc (meta v) :name (addrf a k) :var v))
        pubs  (fn [[k v]] (map proc (repeat (str k)) (ns-publics v)))]
    (->>
      (concat
        (->> sym ns-refers (map proc (repeat nil)))
        (->> sym ns-aliases (into base) (mapcat pubs)))
      (filter task?) (sort-by :name))))

(def ^:dynamic *sh-dir* nil)

(defn sh [& args]
  (let [opts (into [:redirect-err true] (when *sh-dir* [:dir *sh-dir*]))
        proc (apply conch/proc (concat args opts))]
    (future (conch/stream-to-out proc :out))
    #(.waitFor (:process proc))))

(def ^:private bgs
  "List of tasks running in other threads that will need to be cleaned up before
  boot can exit."
  (atom ()))

;; cleanup background tasks on shutdown
(def ^:private add-shutdown!
  (delay (pod/add-shutdown-hook! #(doseq [job @bgs] (future-cancel job)))))

;; Task helpers ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn once
  "Evaluate the given `task` only once, then pass through."
  [task]
  (let [ran? (atom false)
        run? (partial compare-and-set! ran? @ran?)]
    (fn [continue]
      (let [task (task continue)]
        #(continue ((if (run? true) task identity) %))))))

(defn bg-task
  "Run the given task once, in a separate, background thread."
  [task]
  @add-shutdown!
  (once
    (core/with-pre-wrap
      (swap! bgs conj (future ((task identity) core/*event*))))))
