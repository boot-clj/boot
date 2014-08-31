(ns boot.task-helpers
  (:require
   [clojure.set               :as set]
   [clojure.string            :as string]
   [clojure.stacktrace        :as trace]
   [boot.core                 :as core]
   [boot.file                 :as file]
   [boot.from.me.raynes.conch :as conch]))

(defn- first-line [s] (when s (first (string/split s #"\n"))))
(defn- now        [ ] (System/currentTimeMillis))
(defn- ms->s      [x] (double (/ x 1000)))

(defmacro print-time [ok fail expr]
  `(let [start# (now)]
     (try
       (let [end# (do ~expr (ms->s (- (now) start#)))]
         (printf ~ok end#))
       (catch Throwable e#
         (let [time#  (ms->s (- (now) start#))
               trace# (with-out-str (trace/print-cause-trace e#))]
           (println (format ~fail trace# time#)))))))

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
(-> (Runtime/getRuntime)
  (.addShutdownHook (Thread. #(doseq [job @bgs] (future-cancel job)))))

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
  (once
    (core/with-pre-wrap
      (swap! bgs conj (future ((task identity) core/*event*))))))

(defn auto
  "Run every `msec` (default 200) milliseconds."
  [& [msec]]
  (fn [continue]
    (fn [event]
      (continue event)
      (Thread/sleep (or msec 200))
      (recur (core/make-event event)))))

(defn files-changed?
  [& [type fancy?]]
  (let [dirs      (remove core/tmpfile? (core/get-env :src-paths)) 
        watchers  (map file/make-watcher dirs)
        since     (atom 0)]
    (fn [continue]
      (fn [event]
        (let [clean #(assoc %2 %1 (set (remove core/ignored? (get %2 %1))))
              info  (->> (map #(%) watchers)
                      (reduce (partial merge-with set/union))
                      (clean :time)
                      (clean :hash))]
          (if-let [mods (->> (or type :time) (get info) seq)]
            (do
              (let [path   (file/path (first mods))
                    ok-v   "\033[34m↳ Elapsed time: %6.3f sec ›\033[33m 00:00:00 \033[0m"
                    ok-q   "Elapsed time: %6.3f sec\n"
                    fail-v "\n\033[31m%s\033[0m\n\033[34m↳ Elapsed time: %6.3f sec ›\033[33m 00:00:00 \033[0m"
                    fail-q "\n%s\nElapsed time: %6.3f sec\n"
                    ok     (if-not fancy? ok-q ok-v)
                    fail   (if-not fancy? fail-q fail-v)]
                (when (not= 0 @since) (println)) 
                (reset! since (:time event))
                (print-time ok fail (continue (assoc event :watch info)))
                (flush)))
            (let [diff  (long (/ (- (:time event) @since) 1000))
                  pad   (apply str (repeat 9 "\b"))
                  s     (mod diff 60)
                  m     (mod (long (/ diff 60)) 60)
                  h     (mod (long (/ diff 3600)) 24)]
              (core/sync!)
              (when fancy?
                (printf "\033[33m%s%02d:%02d:%02d \033[0m" pad h m s)
                (flush)))))))))
