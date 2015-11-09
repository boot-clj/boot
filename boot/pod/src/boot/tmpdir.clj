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

(def CACHE_VERSION "1.0.0")
(def state (atom {:prev {} :manifest {}}))

(defprotocol ITmpFile
  (id   [this])
  (dir  [this])
  (bdir [this])
  (path [this])
  (hash [this])
  (time [this])
  (file [this]))

(defprotocol ITmpFileSet
  (ls         [this])
  (commit!    [this])
  (rm         [this paths])
  (add        [this dest-dir src-dir opts])
  (add-tmp    [this dest-dir tmpfiles])
  (add-cached [this dest-dir cache-key cache-fn opts])
  (mv         [this from-path to-path])
  (cp         [this src-file dest-tmpfile]))

(defrecord TmpFile [dir bdir path id hash time]
  ITmpFile
  (id   [this] id)
  (dir  [this] dir)
  (bdir [this] bdir)
  (path [this] path)
  (hash [this] hash)
  (time [this] time)
  (file [this] (io/file dir path)))

(defrecord TmpDir [dir user input output]
  ITmpFile
  (id   [this] nil)
  (dir  [this] dir)
  (bdir [this] nil)
  (path [this] "")
  (hash [this] "")
  (time [this] 0)
  (file [this] dir))

(defn- file-stat
  [^File f]
  (let [h (digest/md5 f)
        t (.lastModified f)]
    {:id (str h "." t) :hash h :time t}))

(def ^:dynamic *hard-link* nil)

(defn- add-blob!
  [^File blob ^File src id]
  (let [out (io/file blob id)]
    (when-not (.exists out)
      (let [tmp (File/createTempFile (.getName out) nil blob)]
        (if *hard-link*
          (do (.setReadOnly src) (file/hard-link src out))
          (do (io/copy src tmp) (.setReadOnly tmp) (file/move tmp out)))))))

(defn- dir->tree
  [^File dir ^File blob]
  (let [->path #(str (file/relative-to dir %))
        ->tmpf (fn [^String p ^File f]
                 (let [{:keys [id] :as stat} (file-stat f)]
                   (add-blob! blob f id)
                   (map->TmpFile (assoc (file-stat f) :dir dir :bdir blob :path p))))]
    (->> dir file-seq (reduce (fn [xs ^File f]
                                (or (and (not (.isFile f)) xs)
                                    (let [p (->path f)]
                                      (assoc xs p (->tmpf p f))))) {}))))

(defn- ^File cache-dir
  [cache-key]
  (-> (boot.App/bootdir)
      (io/file "cache" "cache" "fileset")
      (io/file CACHE_VERSION cache-key)))

(defn- ^File manifest-file
  [cache-key]
  (io/file (cache-dir cache-key) "manifest.edn"))

(defn- read-manifest
  [manifile bdir]
  (let [prep #(-> % map->TmpFile (assoc :bdir bdir))]
    (->> manifile slurp read-string (reduce-kv #(assoc %1 %2 (prep %3)) {}))))

(defn- write-manifest
  [manifile manifest]
  (let [prep #(-> (into {} %) (dissoc :dir :bdir))]
    (spit manifile (pr-str (reduce-kv #(assoc %1 %2 (prep %3)) {} manifest)))))

(defn- apply-mergers
  [mergers old-file path new-file merged-file]
  (when-let [merger (some (fn [[re v]] (when (re-find re path) v)) mergers)]
    (util/dbug "Merging duplicate entry (%s)\n" path)
    (let [out-file (File/createTempFile (.getName merged-file) nil
                                        (.getParentFile merged-file))]
      (with-open [curr-stream (io/input-stream old-file)
                  new-stream  (io/input-stream new-file)
                  out-stream  (io/output-stream out-file)]
        (merger curr-stream new-stream out-stream))
      (file/move out-file merged-file))))

(defn- set-dir
  [tree dir]
  (reduce-kv #(assoc %1 %2 (assoc %3 :dir dir)) {} tree))

(defn- get-cached
  [cache-key seedfn]
  (util/dbug "Adding cached fileset %s...\n" cache-key)
  (or (get-in @state [:manifest cache-key])
      (let [cache-dir (cache-dir cache-key)
            manifile  (manifest-file cache-key)
            store!    #(util/with-let [m %]
                         (swap! state assoc-in [:manifest cache-key] m))]
        (or (and (.exists manifile)
                 (store! (read-manifest manifile cache-dir)))
            (let [tmp-dir (file/tmpdir "boot-scratch")]
              (util/dbug "Not found in cache: %s...\n" cache-key)
              (.mkdirs cache-dir)
              (seedfn tmp-dir)
              (binding [*hard-link* true]
                (let [m (dir->tree tmp-dir cache-dir)]
                  (write-manifest manifile m)
                  (store! (read-manifest manifile cache-dir)))))))))

(defn- merge-trees
  [old new mergers]
  (util/with-let [tmp (file/tmpdir "boot-scratch")]
    (doseq [[path newtmp] new]
      (when-let [oldtmp (get old path)]
        (util/dbug "Merging %s...\n" path)
        (prn :xxx newtmp)
        (prn :yyy oldtmp)
        (let [newf   (io/file (bdir newtmp) (id newtmp))
              oldf   (io/file (bdir oldtmp) (id oldtmp))
              mergef (doto (io/file tmp path) io/make-parents)]
          (apply-mergers mergers oldf path newf mergef))))))

(defn- comp-res
  [regexes]
  (when-let [res (seq regexes)]
    (->> (map #(partial re-find %) res) (apply some-fn))))

(defn- filter-tree
  [tree include exclude]
  (let [incl    (or (comp-res include) (constantly true))
        excl    (or (comp-res exclude) (constantly false))
        reducer #(if (or (not (incl %2)) (excl %2)) %1 (assoc %1 %2 %3))]
    (reduce-kv reducer {} tree)))

(declare diff*)

(defrecord TmpFileSet [dirs tree blob]
  ITmpFileSet

  (ls [this]
    (set (vals tree)))

  (commit! [this]
    (util/with-let [{:keys [dirs tree blob]} this]
      (let [prev (get-in @state [:prev dirs])
            {:keys [added removed changed]} (diff* prev this [:id :dir])]
        (doseq [tmpf (set/union (ls removed) (ls changed))
                :let [prev (get-in prev [:tree (path tmpf)])]]
          (when (.exists ^File (file prev))
            (util/dbug "Removing %s...\n" (path prev))
            (file/delete-file (file prev))))
        (doseq [tmpf (set/union (ls added) (ls changed))]
          (util/dbug "Adding %s...\n" (path tmpf))
          (file/copy-with-lastmod (io/file (bdir tmpf) (id tmpf)) (file tmpf)))
        (swap! state assoc-in [:prev dirs] this))))

  (rm [this tmpfiles]
    (let [{:keys [dirs tree blob]} this
          treefiles (set (vals tree))
          remove?   (->> tmpfiles set (set/difference treefiles) complement)]
      (assoc this :tree (reduce-kv #(if (remove? %3) %1 (assoc %1 %2 %3)) {} tree))))

  (add [this dest-dir src-dir opts]
    (assert ((set (map file dirs)) dest-dir)
            (format "dest-dir not in dir set (%s)" dest-dir))
    (let [{:keys [dirs tree blob]} this
          {:keys [mergers include exclude]} opts
          ->tree   #(set-dir (dir->tree % blob) dest-dir)
          new-tree (-> (->tree src-dir) (filter-tree include exclude))
          mrg-tree (when mergers
                     (->tree (merge-trees tree new-tree mergers)))]
      (assoc this :tree (merge tree new-tree mrg-tree))))

  (add-cached [this dest-dir cache-key cache-fn opts]
    (assert ((set (map file dirs)) dest-dir)
            (format "dest-dir not in dir set (%s)" dest-dir))
    (let [{:keys [dirs tree blob]} this
          {:keys [mergers include exclude]} opts
          new-tree (let [cached (get-cached cache-key cache-fn)]
                     (-> (set-dir cached dest-dir)
                         (filter-tree include exclude)))
          mrg-tree (when mergers
                     (let [merged (merge-trees tree new-tree mergers)]
                       (set-dir (dir->tree merged blob) dest-dir)))]
      (assoc this :tree (merge tree new-tree mrg-tree))))

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
