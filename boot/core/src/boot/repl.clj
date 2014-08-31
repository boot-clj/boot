(ns boot.repl
  (:require
   [boot.util :as util])
  (:import
   [java.util.concurrent CancellationException]))

(defn- ^{:boot/from :gfredricks/repl-utils} time-str
  [ms-delta]
  (let [quot-rem (juxt quot rem)
        [sec' ms]  (quot-rem ms-delta 1000)
        [min' sec] (quot-rem sec' 60)
        [hour min] (quot-rem min' 60)]
    (format "%02d:%02d:%02d.%03d" hour min sec ms)))

(defonce ^:private bg-id-counter (atom 0))
(defonce ^:private bgs           (atom (sorted-map)))
(defn ^:private    now []        (System/currentTimeMillis))

(defn- print-job [{:keys [start-time end-time state n form]}]
  (let [t (time-str (- (or end-time (now)) start-time))]
    (format "[%s] %-10s %12s    %s" n (name state) t (pr-str form))))

(defn send-flash [msg]
  (util/guard
    (do (require 'boot.repl-server)
        ((resolve 'boot.repl-server/send-flash) msg))))

(defmacro bg
  "Runs the expr in a future."
  [expr]
  (let [id    (swap! bg-id-counter inc)
        start (now)
        bgs   `(var-get #'bgs)
        now   `(var-get #'now)
        prn   `(var-get #'print-job)]
    `(let [f#   (future ~expr)]
       (swap! ~bgs assoc ~id
         {:form '~expr :n ~id :state :starting :start-time ~start :future f#})
       (future
         (try
           (swap! ~bgs update-in [~id] assoc :state :running)
           (binding [*out* *err*]
             (println (~prn (get @~bgs ~id))))
           @f#
           (swap! ~bgs update-in [~id] assoc :state :done :end-time (~now))
           (send-flash (~prn (get @~bgs ~id)))
           (catch Throwable t#
             (let [u# (if (instance? CancellationException t#) :killed :error)]
               (swap! ~bgs update-in [~id] assoc :state u# :end-time (~now))
               (when-not (= u# :killed)
                 (send-flash (~prn (get @~bgs ~id))))))))
       (loop [s# :starting]
         (when (= :starting s#)
           (Thread/sleep 50)
           (recur (get-in @~bgs [~id :state]))))
       nil)))

(defmacro fg
  "Deref's the given bg object's future. Blocks and returns the value,
  or throws an exception if the bg threw an exception."
  [id]
  `(let [ret# @(get-in @@#'bgs [~id :future])]
     (loop [s# :running]
       (when (= :running s#)
         (Thread/sleep 50)
         (recur (get-in @@#'bgs [~id :state]))))
     ret#))

(defmacro kill
  "Kills the background job."
  [id]
  `(let [fut# (get-in @@#'bgs [~id :future])
         ret# (future-cancel fut#)]
     (loop [s# :running]
       (when (= s# :running)
         (Thread/sleep 50)
         (recur (get-in @@#'bgs [~id :state]))))
     (binding [*out* *err*]
       (println (#'print-job (get @@#'bgs ~id))))
     ret#))

(defn jobs [& states]
  (let [s (cond
            (contains? (set states) :all)  #{:running :started :killed :error :done}
            (seq states)                   (set states)
            :else                          #{:running})]
    (doseq [[id job] @bgs]
      (when (contains? s (:state job))
        (println (print-job job)) (flush)))))
