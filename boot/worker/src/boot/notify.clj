(ns boot.notify
  (:require
   [clojure.java.io    :as io]
   [boot.pod           :as pod]
   [boot.util          :as util]
   [clojure.java.shell :refer [sh]])
  (:import
   (java.io File FileInputStream)))

(defn path-for [theme type]
  (let [p (format "boot/notify/%s_%s.mp3" (or theme "system") type)]
    (if (io/resource p) p (path-for "system" type))))

(let [notified? (atom 0)]
  (defn- fg-first-time! [future]
    ((if (< 1 (swap! notified? inc)) identity deref) future)))

(defn play! [file]
  (fg-first-time!
    (future
      (-> (or (.getResourceAsStream (clojure.lang.RT/baseLoader) file)
            (FileInputStream. (io/file file))
            (or (throw (RuntimeException. (str file " not found.")))))
        java.io.BufferedInputStream.
        javazoom.jl.player.Player.
        .play))))

(defn success! [theme file] (play! (or file (path-for theme "success"))))
(defn failure! [theme file] (play! (or file (path-for theme "failure"))))

(defn warning! [theme n file]
  (fg-first-time!
    (future
      (let [msg (str n "warning" (if (> n 1) "s"))]
        (cond
          (.exists (File. "/usr/bin/espeak"))
          (sh "espeak" "-v+f2" msg)
          (.exists (File. "/usr/bin/say"))
          (sh "say" "-v" "Vicki" msg)
          :else (play! (or file (path-for theme "warning"))))))))

#_(defmacro cljs-warnings
  [warnings & body]
  (try (require 'cljs.analyzer)
       `(cljs.analyzer/with-warning-handlers
          (conj cljs.analyzer/*cljs-warning-handlers*
                (fn [& _#] (swap! ~warnings inc)))
          ~@body)
       (catch Throwable _ `(do ~@body))))

(defn- ^{:boot/from :jeluard/boot-notify} program-exists?
  [s]
  (= 0 (:exit (sh "sh" "-c" (format "command -v %s" s)))))

(defprotocol Notifier
  (-supported? [this] "Check if this notifier is supported on current platform")
  (-notify [this m] "Perform the notification"))

(deftype ConsolePrintNotifier
    []
  Notifier
  (-supported? [_] true)
  (-notify [_ {:keys [message title]}] (printf "%s: %s" title message)))

(deftype TerminalNotifierNotifier
    []
  Notifier
  (-supported? [_] (program-exists? "terminal-notifier"))
  (-notify [_ {:keys [message title icon pid]}]
    (sh "terminal-notifier" "-message" message "-title" title "-contentImage" icon "-group" pid)))

(deftype NotifySendNotifier
    []
  Notifier
  (-supported? [_] (program-exists? "notify-send"))
  (-notify [_ {:keys [message title icon]}] (sh "notify-send" title message "--icon" icon)))

(def ^{:boot/from :jeluard/boot-notify} default-notifier
  (condp = (System/getProperty "os.name")
    "Mac OS X" (TerminalNotifierNotifier.)
    "Linux" (NotifySendNotifier.)
    (ConsolePrintNotifier.)))

(defn ^{:boot/from :jeluard/boot-notify} notify!
  [s m]
  (-notify default-notifier (assoc m :message s)))
