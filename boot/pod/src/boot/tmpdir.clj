(ns boot.tmpdir
  (:refer-clojure :exclude [time hash])
  (:require
    [clojure.java.io  :as io]
    [clojure.set      :as set]
    [clojure.data     :as data]
    [boot.pod         :as pod]
    [boot.util        :as util]
    [boot.file        :as file]
    [boot.from.digest :as digest])
  (:import [java.io File]))

(set! *warn-on-reflection* true)

(def state (atom {}))

(defprotocol ITmpFile
  (id   [this])
  (dir  [this])
  (path [this])
  (hash [this])
  (time [this])
  (file [this]))

(defprotocol ITmpFileSet
  (ls      [this])
  (commit! [this])
  (rm      [this paths])
  (add     [this dest-dir src-dir opts])
  (add-tmp [this dest-dir tmpfiles])
  (mv      [this from-path to-path])
  (cp      [this src-file dest-tmpfile]))

(defrecord TmpFile [dir path id hash time]
  ITmpFile
  (id   [this] id)
  (dir  [this] dir)
  (path [this] path)
  (hash [this] hash)
  (time [this] time)
  (file [this] (io/file dir path)))

(defrecord TmpDir [dir user input output]
  ITmpFile
  (id   [this] nil)
  (dir  [this] dir)
  (path [this] "")
  (hash [this] "")
  (time [this] 0)
  (file [this] dir))

(defn- dir->tree
  [^File dir]
  (let [->path #(str (file/relative-to dir %))
        ->tmpf (fn [^String p ^File f]
                 (let [h (digest/md5 f)
                       t (.lastModified f)
                       i (str h "." t)]
                   (map->TmpFile {:dir dir :path p :id i :hash h :time t})))]
    (->> dir file-seq (reduce (fn [xs ^File f]
                                (or (and (not (.isFile f)) xs)
                                    (let [p (->path f)]
                                      (assoc xs p (->tmpf p f))))) {}))))

(defn- add-blob!
  [^File blob ^File src id]
  (let [out (io/file blob id)]
    (when-not (.exists out)
      (let [tmp (File/createTempFile "boot-" id blob)]
        (io/copy src tmp)
        (.setReadOnly tmp)
        (file/move tmp out)))))

(declare diff*)

(defrecord TmpFileSet [dirs tree blob]
  ITmpFileSet

  (ls [this]
    (set (vals tree)))

  (commit! [this]
    (util/with-let [{:keys [dirs tree blob]} this]
      (let [prev (@state dirs)
            {:keys [added removed changed]} (diff* prev this [:id :dir])]
        (doseq [tmpf (set/union (ls removed) (ls changed))
                :let [prev (get-in prev [:tree (path tmpf)])]]
          (when (.exists ^File (file prev))
            (util/dbug "Removing %s...\n" (path prev))
            (file/delete-file (file prev))))
        (doseq [tmpf (set/union (ls added) (ls changed))]
          (util/dbug "Adding %s...\n" (path tmpf))
          (file/copy-with-lastmod (io/file blob (id tmpf)) (file tmpf)))
        (swap! state assoc dirs this))))

  (rm [this tmpfiles]
    (let [{:keys [dirs tree blob]} this
          treefiles (set (vals tree))
          remove?   (->> tmpfiles set (set/difference treefiles) complement)]
      (assoc this :tree (reduce-kv #(if (remove? %3) %1 (assoc %1 %2 %3)) {} tree))))

  (add [this dest-dir src-dir opts]
    (assert ((set (map file dirs)) dest-dir)
            (format "dest-dir not in dir set (%s)" dest-dir))
    (when-let [mergers (:mergers opts)]
      (doseq [tmpf (ls this)]
        (let [[p f] ((juxt path file) tmpf)
              out   (io/file src-dir p)]
          (when (.exists out)
            (pod/merge-duplicate-jar-entry mergers f p out :merged-url out)))))
    (let [{:keys [dirs tree blob]} this
          src-tree (-> #(assoc %1 %2 (assoc %3 :dir dest-dir))
                       (reduce-kv {} (dir->tree src-dir)))]
      (doseq [[path tmpf] src-tree]
        (add-blob! blob (io/file src-dir path) (id tmpf)))
      (assoc this :tree (merge tree src-tree))))

  (add-tmp [this dest-dir tmpfiles]
    (assert ((set (map file dirs)) dest-dir)
            (format "dest-dir not in dir set (%s)" dest-dir))
    (reduce #(assoc-in %1 [:tree (path %2)] (assoc %2 :dir dest-dir)) this tmpfiles))

  (mv [this from-path to-path]
    (if (= from-path to-path)
      this
      (if-let [from (get-in this [:tree from-path])]
        (update-in this [:tree] #(-> % (assoc to-path (assoc from :path to-path))
                                       (dissoc from-path)))
        (throw (Exception. (format "not in fileset (%s)" from-path))))))

  (cp [this src-file dest-tmpfile]
    (let [hash (digest/md5 src-file)
          p'   (path dest-tmpfile)
          d'   (dir dest-tmpfile)]
      (assert ((set (map file dirs)) d')
              (format "dest-dir not in dir set (%s)" d'))
      (add-blob! blob src-file hash)
      (assoc this :tree (merge tree {p' (assoc dest-tmpfile :id hash)})))))

(defn tmpfile?
  [x]
  (instance? TmpFile x))

(defn tmpfileset?
  [x]
  (instance? TmpFileSet x))

(defn- diff-tree
  [tree props]
  (let [->map #(select-keys % props)]
    (reduce-kv #(assoc %1 %2 (->map %3)) {} tree)))

(defn- diff*
  [before after props]
  (if-not before
    {:added   after
     :removed (assoc after :tree {})
     :changed (assoc after :tree {})}
    (let [props   (or (seq props) [:id])
          t1      (:tree before)
          t2      (:tree after)
          d1      (diff-tree t1 props)
          d2      (diff-tree t2 props)
          [x y _] (map (comp set keys) (data/diff d1 d2))]
      {:added   (->> (set/difference   y x) (select-keys t2) (assoc after :tree))
       :removed (->> (set/difference   x y) (select-keys t1) (assoc after :tree))
       :changed (->> (set/intersection x y) (select-keys t2) (assoc after :tree))})))

(defn diff
  [before after & props]
  (let [{:keys [added changed]}
        (diff* before after props)]
    (update-in added [:tree] merge (:tree changed))))

(defn removed
  [before after & props]
  (:removed (diff* before after props)))

(defn added
  [before after & props]
  (:added (diff* before after props)))

(defn changed
  [before after & props]
  (:changed (diff* before after props)))
