(ns boot.tmpdir
  (:require
    [clojure.java.io  :as io]
    [clojure.set      :as set]
    [clojure.data     :as data]
    [boot.util        :as util]
    [boot.file        :as file]
    [boot.from.digest :as digest]))

(defprotocol ITmpFile
  (id   [this])
  (dir  [this])
  (path [this])
  (file [this]))

(defprotocol ITmpFileSet
  (ls      [this])
  (commit! [this])
  (rm      [this paths])
  (add     [this dest-dir src-dir])
  (mv      [this from-path to-path])
  (cp      [this src-file dest-tmpfile]))

(defrecord TmpFile [dir path id]
  ITmpFile
  (id   [this] id)
  (dir  [this] dir)
  (path [this] path)
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
                          [p (TmpFile. dir p (digest/md5 %))])]
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

(def ^:dynamic *locked* false)

(defrecord TmpFileSet [dirs tree blob]
  ITmpFileSet
  (ls [this]
    (set (vals tree)))
  (commit! [this]
    (assert (not *locked*) "can't commit! during this phase")
    (util/with-let [{:keys [dirs tree blob]} this]
      (apply file/empty-dir! (map file dirs))
      (doseq [[p tmpf] tree]
        (let [srcf (io/file blob (id tmpf))]
          (file/copy-with-lastmod srcf (file tmpf))))))
  (rm [this tmpfiles]
    (assert (not *locked*) "can't rm during this phase")
    (let [{:keys [dirs tree blob]} this
          treefiles (set (vals tree))
          remove?   (->> tmpfiles set (set/difference treefiles) complement)]
      (assoc this :tree (reduce-kv #(if (remove? %3) %1 (assoc %1 %2 %3)) {} tree))))
  (add [this dest-dir src-dir]
    (assert (not *locked*) "can't add during this phase")
    (assert ((set (map file dirs)) dest-dir)
            (format "dest-dir not in dir set (%s)" dest-dir))
    (let [{:keys [dirs tree blob]} this
          src-tree (-> #(assoc %1 %2 (assoc %3 :dir dest-dir))
                       (reduce-kv {} (dir->tree src-dir)))]
      (doseq [[path tmpf] src-tree]
        (add-blob! blob (io/file src-dir path) (id tmpf)))
      (assoc this :tree (merge tree src-tree))))
  (mv [this from-path to-path]
    (assert (not *locked*) "can't mv during this phase")
    (if-let [f (assoc (get-in this [:tree from-path]) :path to-path)]
      (update-in this [:tree] #(-> % (assoc to-path f) (dissoc from-path)))
      (throw (Exception. (format "not in fileset (%s)" from-path)))))
  (cp [this src-file dest-tmpfile]
    (assert (not *locked*) "can't cp during this phase")
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

(defn diff [this fileset]
  (if-not this
    fileset
    (let [t1 (:tree this)
          t2 (:tree fileset)]
      (->> (data/diff t1 t2) second keys (select-keys t2) (assoc fileset :tree)))))

(defn removed [this fileset]
  (if-not fileset
    this
    (let [t1 (:tree this)
          t2 (:tree fileset)]
      (->> (data/diff t1 t2) first keys (select-keys t1) (assoc fileset :tree)))))
