(ns boot.task-helpers.notify
  (:require [clojure.java.io    :as io]
            [boot.core          :as core]
            [boot.pod           :as pod]
            [boot.util          :as util]
            [boot.from.io.aviso.ansi :as ansi]))

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

(defn sh-with-timeout [& args]
  (try
    (apply util/dosh-timed 1000 args)
    0
    (catch Exception _
      1)))

(defn- ^{:boot/from :jeluard/boot-notify} program-exists?
  [s]
  (= 0 (sh-with-timeout "sh" "-c" (format "command -v %s >/dev/null" s))))

(defn- escape [s]
  (pr-str (str s)))

(defmulti notify-method
  (fn [os _message]
    os))

(defmethod notify-method "Mac OS X"
  [_ {:keys [message title icon uid] :as notification}]
  (cond
    (program-exists? "terminal-notifier")
    (sh-with-timeout
     "terminal-notifier"
     "-message" (str message)
     "-title" (str title)
     "-contentImage" (str icon)
     "-group" (str uid))

    (program-exists? "osascript")
    (sh-with-timeout
     "osascript"
     "-e"
     (str "display notification"
          (escape message)
          "with title"
          (escape title)))

    :else
    ((get-method notify-method :default) :default notification)))

(defmethod notify-method "Linux"
  [_ {:keys [message title icon] :as notification}]
  (if (program-exists? "notify-send")
    (sh-with-timeout
     "notify-send"
     (str title)
     (str message)
     "--icon"
     (str icon))
    ((get-method notify-method :default) :default notification)))

(defmethod notify-method :default
  [_ {:keys [message title]}]
  (util/info "%s%s %s \u2022 %s\n"
             ansi/reset-font
             (ansi/italic "notification:")
             (ansi/bold title)
             message))

(defn ^{:boot/from :jeluard/boot-notify} visual-notify!
  [data]
  (notify-method (System/getProperty "os.name") data))

(defn audible-notify!
  [options]
  (pod/with-call-worker
    (boot.notify/notify! ~options)))
