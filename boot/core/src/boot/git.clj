(ns boot.git
  (:require
   [boot.pod  :as pod]
   [boot.util :as util]))

(defn status         [] (pod/call-worker `(boot.jgit/status)))
(defn clean?         [] (pod/call-worker `(boot.jgit/clean?)))
(defn dirty?         [] (not (clean?)))
(defn last-commit    [] (pod/call-worker `(boot.jgit/last-commit)))
(defn branch-current [] (pod/call-worker `(boot.jgit/branch-current)))

(defn ls-files
  [& {:keys [ref untracked]}]
  (pod/call-worker
    `(boot.jgit/ls-files :ref ~ref :untracked ~untracked)))

(defn tag
  [name message]
  (pod/call-worker
    `(boot.jgit/tag ~name ~message)))

(defn make-gitignore-matcher
  []
  (let [fs (util/guard (ls-files :untracked true))]
    (prn :make fs)
    (if-not fs (constantly false) #(do (prn :f % :p (.getPath %)) (prn :m (not (contains? fs (.getPath %)))) (prn :fs fs)
                                       (not (contains? fs (.getPath %)))))))
