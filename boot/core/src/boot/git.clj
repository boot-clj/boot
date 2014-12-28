(ns boot.git
  (:require
   [boot.pod  :as pod]
   [boot.util :as util]))

(defn status         [] (pod/with-call-worker (boot.jgit/status)))
(defn clean?         [] (pod/with-call-worker (boot.jgit/clean?)))
(defn dirty?         [] (not (clean?)))
(defn last-commit    [] (pod/with-call-worker (boot.jgit/last-commit)))
(defn branch-current [] (pod/with-call-worker (boot.jgit/branch-current)))

(defn ls-files
  [& {:keys [ref untracked]}]
  (pod/with-call-worker
    (boot.jgit/ls-files :ref ~ref :untracked ~untracked)))

(defn tag
  [name message]
  (pod/with-call-worker
    (boot.jgit/tag ~name ~message)))

(defn make-gitignore-matcher
  []
  (let [fs (util/guard (ls-files :untracked true))]
    (if-not fs (constantly false) #(not (contains? fs (.getPath %))))))

(defn describe
  []
  (pod/with-call-worker
    (boot.jgit/describe)))
