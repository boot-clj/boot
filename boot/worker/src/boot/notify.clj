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

(defmulti notify-method
  (fn [os _message]
    os))

(defmethod notify-method "Mac OS X"
  [_ {:keys [message title icon pid] :as notification}]
  (if (program-exists? "terminal_notifier")
    (sh "terminal-notifier" "-message" message "-title" title "-contentImage" icon "-group" pid)
    ((get-method notify-method :default) :default notification)))

(defmethod notify-method "Linux"
  [_ {:keys [message title icon] :as notification}]
  (if (program-exists? "notify-send")
    (sh "notify-send" title message "--icon" icon)
    ((get-method notify-method :default) :default notification)))

(defmethod notify-method :default
  [_ {:keys [message title]}]
  (printf "%s: %s" title message))

(defn ^{:boot/from :jeluard/boot-notify} notify!
  [s m]
  (notify-method (System/getProperty "os.name") (assoc m :message s)))
