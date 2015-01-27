(ns boot.github
  (:require [boot.pod :as pod])
  (:import [java.util HashMap]))

(def api-url "https://api.github.com")

(defn getJSON [url]
  (:body (pod/with-eval-worker
           (require 'clj-http.client)
           (clj-http.client/get ~(str api-url url) {:as :json}))))

(defn latest-boot-release
  []
  (->> (getJSON "/repos/boot-clj/boot/releases") first
       ((juxt :tag_name :html_url)) (zipmap ["tag" "url"])))
