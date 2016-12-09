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
     (try
       (-> (or (.getResourceAsStream (clojure.lang.RT/baseLoader) file)
               (FileInputStream. (io/file file))
               (throw (RuntimeException. (str file " not found."))))
           java.io.BufferedInputStream.
           javazoom.jl.player.Player.
           .play)
       (catch Exception e
         (util/warn "\nError attempting to play sound file: %s\n\n"
                    (.getMessage e)))))))

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
          (sh "say" msg)
          :else (play! (or file (path-for theme "warning"))))))))

(defn notify! [{:keys [file theme type]}]
  (play! (or file (path-for theme (name type)))))
