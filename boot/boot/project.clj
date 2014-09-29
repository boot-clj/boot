(import [java.util Properties])
(require '[clojure.java.io :as io])
(def propsfile "../../version.properties")
(def version (-> (doto (Properties.) (.load (io/input-stream propsfile)))
               (.getProperty "version")))

(defproject boot version
  :description  "Placeholder to synchronize other boot module versions."
  :url          "http://github.com/tailrecursion/boot"
  :scm          {:url "https://github.com/tailrecursion/boot.git" :dir "../../"}
  :license      {:name "Eclipse Public License"
                 :url "http://www.eclipse.org/legal/epl-v10.html"})

