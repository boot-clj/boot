(ns boot.task-helpers.notify
  (:require [clojure.java.io    :as io]
            [clojure.java.shell :as shell]
            [boot.core          :as core]
            [boot.pod           :as pod]))

(defn get-themefiles [theme tmp-dir]
  (let [resource   #(vector %2 (format "boot/notify/%s_%s.mp3" %1 %2))
        resources  #(map resource (repeat %) ["success" "warning" "failure"])]
    (into {}
          (let [rs (when theme (resources theme))]
            (when (and (seq rs) (every? (comp io/resource second) rs))
              (for [[x r] rs]
                (let [f (io/file tmp-dir (.getName (io/file r)))]
                  (pod/copy-resource r f)
                  [(keyword x) (.getPath f)])))))))

(defn ^{:boot/from :jeluard/boot-notify} boot-logo
  []
  (let [d (core/tmp-dir!)
        f (io/file d "logo.png")]
    (io/copy (io/input-stream (io/resource "boot-logo-3.png")) f)
    (.getAbsolutePath f)))

(defn- ^{:boot/from :jeluard/boot-notify} program-exists?
  [s]
  (= 0 (:exit (shell/sh "sh" "-c" (format "command -v %s" s)))))

(defmulti notify-method
  (fn [os _message]
    os))

(defmethod notify-method "Mac OS X"
  [_ {:keys [message title icon pid] :as notification}]
  (if (program-exists? "terminal_notifier")
    (shell/sh "terminal-notifier" "-message" message "-title" title "-contentImage" icon "-group" pid)
    ((get-method notify-method :default) :default notification)))

(defmethod notify-method "Linux"
  [_ {:keys [message title icon] :as notification}]
  (if (program-exists? "notify-send")
    (shell/sh "notify-send" title message "--icon" icon)
    ((get-method notify-method :default) :default notification)))

(defmethod notify-method :default
  [_ {:keys [message title]}]
  (printf "%s: %s" title message))

(defn ^{:boot/from :jeluard/boot-notify} visual-notify!
  [data]
  (notify-method (System/getProperty "os.name") data))

(defn audible-notify!
  [options]
  (pod/with-call-worker
    (boot.notify/notify! ~options)))
