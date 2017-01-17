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

(defn- warn-ex-thrown []
  (util/warn
   "An exception was thrown while trying to send a notification. To see more info, increase the verbosity of your build with e.g. \"boot -v\" or \"boot -vv\"\n"))

(defn sh-with-timeout [& args]
  (try
    (apply util/dosh-timed 1000 args)
    0
    (catch Exception e
      (if (= 1 @util/*verbosity*)
        (warn-ex-thrown)
        (util/print-ex e))
      1)))

(defn- ^{:boot/from :jeluard/boot-notify} program-exists?
  [s]
  (= 0 (sh-with-timeout "sh" "-c" (format "command -v %s >/dev/null" s))))

(defn- try-notify-with-shell-program [program-name & args]
  (and
   (program-exists? program-name)
   (= 0
      (apply sh-with-timeout program-name args))))

(defn- escape [s]
  (pr-str (str s)))

(defn- notify-default [{:keys [message title]}]
  (util/info "%s%s %s \u2022 %s\n"
             ansi/reset-font
             (ansi/italic "notification:")
             (ansi/bold title)
             message))

(defmulti notify-method
  (fn [os _notification]
    os))

(defmethod notify-method :default
  [_ notification]
  (notify-default notification))

(defmethod notify-method "Mac OS X"
  [_ {:keys [message title icon uid] :as notification}]
  (or
   (try-notify-with-shell-program
    "terminal-notifier"
    "-message" (str message)
    "-title" (str title)
    "-contentImage" (str icon)
    "-group" (str uid))

   (try-notify-with-shell-program
    "osascript"
    "-e"
    (str "display notification"
         (escape message)
         "with title"
         (escape title)))

   (notify-default notification)))

(defmethod notify-method "Linux"
  [_ {:keys [message title icon] :as notification}]
  (or
   (try-notify-with-shell-program
    "notify-send"
    (str title)
    (str message)
    "--icon"
    (str icon))
   (notify-default notification)))

(defn ^{:boot/from :jeluard/boot-notify} visual-notify!
  [data]
  (notify-method (System/getProperty "os.name") data))

(defn audible-notify!
  [options]
  (pod/with-call-worker
    (boot.notify/notify! ~options)))
