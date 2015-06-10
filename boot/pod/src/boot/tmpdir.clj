(ns boot.tmpdir
  (:refer-clojure :exclude [time])
  (:require
    [clojure.java.io  :as io]
    [clojure.set      :as set]
    [clojure.data     :as data]
    [boot.pod         :as pod]
    [boot.util        :as util]
    [boot.file        :as file]
    [boot.from.digest :as digest]))

(defprotocol ITmpFile
  (id   [this])
  (dir  [this])
  (path [this])
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

(defrecord TmpFile [dir path id time]
  ITmpFile
  (id   [this] id)
  (dir  [this] dir)
  (path [this] path)
  (time [this] time)
  (file [this] (io/file dir path)))

(defrecord TmpDir [dir user input output]
  ITmpFile
  (id   [this] nil)
  (dir  [this] dir)
  (path [this] "")
  (file [this] dir))

(defn- dir->tree
  [dir]
  (let [file->rel-path #(file/relative-to dir %)
        file->kv       #(let [p (str (file->rel-path %))]
                          [p (TmpFile. dir p (digest/md5 %) (.lastModified %))])]
    (->> dir file-seq (filter (memfn isFile)) (map file->kv) (into {}))))

(defn- add-blob!
  [blob src hash]
  (let [out    (io/file blob hash)
        mod    #(.lastModified %)
        write! #(.setWritable %1 %2)
        mod!   #(.setLastModified %1 %2)]
    (if (.exists out)
      (when (< (mod out) (mod src))
        (doto out (write! true) (mod! (mod src)) (write! false)))
      (doto out (#(io/copy src %)) (mod! (mod src)) (write! false)))))

(defrecord TmpFileSet [dirs tree blob]
  ITmpFileSet
  (ls [this]
    (set (vals tree)))
  (commit! [this]
    (util/with-let [{:keys [dirs tree blob]} this]
      (apply file/empty-dir! (map file dirs))
      (doseq [[p tmpf] tree]
        (let [srcf (io/file blob (id tmpf))]
          (file/copy-with-lastmod srcf (file tmpf))))))
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
      (if-let [f (assoc (get-in this [:tree from-path]) :path to-path)]
        (update-in this [:tree] #(-> % (assoc to-path f) (dissoc from-path)))
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
  (let [->map #(-> {:hash (id %)
                    :time (time %)}
                   (select-keys props))]
    (reduce-kv #(assoc %1 %2 (->map %3)) {} tree)))

(defn- diff*
  [before after props]
  (if-not before
    {:added   after
     :removed (assoc after :tree {})
     :changed (assoc after :tree {})}
    (let [props   (or (seq props) [:time :hash])
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
