(defproject tailrecursion/boot "1.1.1"
  :description  "A dependency setup/build tool for Clojure."
  :url          "https://github.com/tailrecursion/boot"
  :license      {:name  "Eclipse Public License"
                 :url   "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.6.0"]]
  :aot          :all
  :main         tailrecursion.boot.loader)
