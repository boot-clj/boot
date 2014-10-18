(ns boot.jgit
  (:require
   [clojure.set        :as set]
   [clojure.java.io    :as io]
   [clj-jgit.porcelain :as jgit])
  (:import
   [org.eclipse.jgit.api          Git]
   [org.eclipse.jgit.treewalk     TreeWalk]
   [org.eclipse.jgit.lib          Ref Repository]
   [org.eclipse.jgit.revwalk      RevCommit RevTree RevWalk]
   [org.eclipse.jgit.storage.file FileRepositoryBuilder]))

(defn status
  []
  (jgit/with-repo "."
    (jgit/git-status repo)))

(defn branch-current
  []
  (jgit/with-repo "."
    (jgit/git-branch-current repo)))

(defn clean?
  []
  (->> (status) vals (reduce set/union) empty?))

(defn last-commit
  []
  (jgit/with-repo "."
    (->> (jgit/git-log repo) first .getName)))

(defn ls-files
  [& {:keys [ref untracked] :or {ref "HEAD"}}]
  (jgit/with-repo "."
    (let [r      (.getRepository repo)
          walk   (RevWalk. r)
          head   (.getRef r ref)
          commit (.parseCommit walk (.getObjectId head))
          tree   (.getTree commit)
          twalk  (doto (TreeWalk. r) (.addTree tree) (.setRecursive true))
          files  (set (when untracked (:untracked (jgit/git-status repo))))]
      (->> (loop [go? (.next twalk) files files]
             (if-not go?
               files
               (recur (.next twalk) (conj files (.getPathString twalk)))))
        (remove (comp #(or (not (.exists %)) (.isDirectory %)) io/file))
        set))))

(defn tag
  [name message]
  (jgit/with-repo "."
    (.. repo tag (setName name) (setMessage message) call)))

