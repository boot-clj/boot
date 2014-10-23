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

(defn- tasks-table [tasks]
  (let [get-task #(-> % :name str)
        get-desc #(-> % :doc first-line)
        built-in {nil (get tasks 'boot.task.built-in)}]
    (->> (dissoc tasks 'boot.task.built-in)
      (concat built-in) (interpose nil)
      (mapcat (fn [[_ xs]] (or xs [{:name "" :doc ""}])))
      (mapv (fn [x] ["" (get-task x) (get-desc x)])))))

(defn- set-title [[[_ & xs] & zs] title] (into [(into [title] xs)] zs))

(defn- version-str []
  (str
    (format "App Version:     %s\n" core/*app-version*)
    (format "Boot Version:    %s\n" core/*boot-version*)
    (format "Clojure Version: %s\n" (clojure-version))
    (format "Documentation:   %s"   "http://github.com/boot-clj/boot")))

(defn- available-tasks [sym]
  (let [base  {nil (the-ns sym)}
        task? #(:boot.core/task %)
        nssym #(->> % meta :ns ns-name)
        addrf #(if-not (seq %1) %2 (symbol %1 (str %2)))
        proc  (fn [a [k v]] (assoc (meta v) :ns* (nssym v) :name (addrf a k) :var v))
        pubs  (fn [[k v]] (map proc (repeat (str k)) (ns-publics v)))]
    (->>
      (concat
        (->> sym ns-refers (map proc (repeat nil)))
        (->> sym ns-aliases (into base) (mapcat pubs)))
      (filter task?) (sort-by :name) (group-by :ns*) (into (sorted-map)))))

(def ^:dynamic *sh-dir* nil)

(defn sh [& args]
  (let [opts (into [:redirect-err true] (when *sh-dir* [:dir *sh-dir*]))
        proc (apply conch/proc (concat args opts))]
    (future (conch/stream-to-out proc :out))
    #(.waitFor (:process proc))))

(defn dosh [& args]
  (let [status ((apply sh args))]
    (when-not (= 0 status)
      (throw (Exception. (-> "%s: non-zero exit status (%d)"
                           (format (first args) status)))))))

(def ^:private bgs
  "List of tasks running in other threads that will need to be cleaned up before
  boot can exit."
  (atom ()))

;; cleanup background tasks on shutdown
(def ^:private add-shutdown!
  (delay (pod/add-shutdown-hook! #(doseq [job @bgs] (future-cancel job)))))

(defn read-pass
  [prompt]
  (String/valueOf (.readPassword (System/console) prompt nil)))

(defn sign-jar [out jar pass keyring user-id]
  (let [prompt (pod/call-worker
                 `(boot.pgp/prompt-for ~keyring ~user-id))
        pass   (or pass (read-pass prompt))]
    (pod/call-worker
      `(boot.pgp/sign-jar
         ~(.getPath out)
         ~(.getPath jar)
         ~pass
         :keyring ~keyring
         :user-id ~user-id))))

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
