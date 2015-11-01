(ns boot.jgit
  (:require
   [clojure.set        :as set]
   [clojure.java.io    :as io]
   [clj-jgit.porcelain :as jgit]
   [boot.util          :as util])
  (:import
   [org.eclipse.jgit.api          Git]
   [org.eclipse.jgit.treewalk     TreeWalk]
   [org.eclipse.jgit.lib          Ref Repository ObjectIdRef$PeeledNonTag]
   [org.eclipse.jgit.revwalk      RevCommit RevTree RevWalk]
   [org.eclipse.jgit.storage.file FileRepositoryBuilder]))

(def repo-dir
  (delay (let [repo #(and (util/guard (jgit/with-repo % repo)) %)]
           (loop [d (.getCanonicalFile (io/file "."))]
             (when d (or (repo d) (recur (.getParentFile d))))))))

(defmacro with-repo
  [& body]
  `(do (assert @repo-dir "This does not appear to be a git repo.")
       (jgit/with-repo @repo-dir ~@body)))

(defn status
  []
  (with-repo (jgit/git-status repo)))

(defn describe
  []
  (with-repo (.. repo describe call)))

(defn branch-current
  []
  (with-repo (jgit/git-branch-current repo)))

(defn clean?
  []
  (->> (status) vals (reduce set/union) empty?))

(defn last-commit
  []
  (with-repo (->> (jgit/git-log repo) first .getName)))

(defn ls-files
  [& {:keys [ref untracked]}]
  (with-repo
    (let [r      (.getRepository repo)
          walk   (RevWalk. r)
          head   (.getRef r (or ref "HEAD"))
          commit (.parseCommit walk (.getObjectId head))
          tree   (.getTree commit)
          twalk  (doto (TreeWalk. r) (.addTree tree) (.setRecursive true))
          files  (when untracked
                   (->> (jgit/git-status repo)
                     ((juxt :untracked :added))
                     (apply into)))
          files  (let [{a :added u :untracked} (jgit/git-status repo)]
                   (into a (when untracked u)))]
      (->> (loop [go? (.next twalk) files files]
             (if-not go?
               files
               (recur (.next twalk) (conj files (.getPathString twalk)))))
        (remove (comp #(or (not (.exists %)) (.isDirectory %)) io/file))
        set))))

(defn ls-tags
  []
  (with-repo
    (let [r        (.getRepository repo)
          ;; PeeledNonTags are returned if working dir isn't clean
          non-tag? #(instance? ObjectIdRef$PeeledNonTag %)
          tags     (remove non-tag? (.call (.tagList repo)))]
      (->> (for [t tags]
             [(subs (.getName t) 10) (.getName (.getPeeledObjectId (.peel r t)))])
           (into {})))))

(defn tag
  [name message]
  (with-repo (.. repo tag (setName name) (setMessage message) call)))

