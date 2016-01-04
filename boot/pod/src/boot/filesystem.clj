(ns boot.filesystem
  (:require
    [clojure.java.io        :as io]
    [clojure.set            :as set]
    [clojure.data           :as data]
    [boot.filesystem.patch  :as fsp]
    [boot.file              :as file]
    [boot.tmpdir            :as tmpd]
    [boot.from.digest       :as digest :refer [md5]]
    [boot.util              :as util   :refer [with-let]])
  (:import
    [java.net URI]
    [java.io File]
    [java.nio.file.attribute FileAttribute FileTime]
    [java.util.zip ZipEntry ZipOutputStream ZipException]
    [java.util.jar JarEntry JarOutputStream Manifest Attributes$Name]
    [java.nio.file Path Files FileSystems StandardCopyOption StandardOpenOption
     LinkOption SimpleFileVisitor FileVisitResult]))

(def continue     FileVisitResult/CONTINUE)
(def skip-subtree FileVisitResult/SKIP_SUBTREE)
(def link-opts    (into-array LinkOption []))
(def copy-opts    (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING]))
(def open-opts    (into-array StandardOpenOption [StandardOpenOption/CREATE]))

(defn- path->segs
  [^Path path]
  (->> path .iterator iterator-seq (map str)))

(defn- segs->path
  [^Path any-path-same-filesystem segs]
  (let [segs-ary (into-array String (rest segs))]
    (-> any-path-same-filesystem .getFileSystem (.getPath (first segs) segs-ary))))

(defn mkfs
  [^File rootdir]
  (.toPath rootdir))

(defn mkjarfs
  [^File jarfile & {:keys [create]}]
  (when create (io/make-parents jarfile))
  (let [jaruri (->> jarfile .getCanonicalFile .toURI (str "jar:") URI/create)]
    (FileSystems/newFileSystem jaruri {"create" (str (boolean create))})))

(defn mkpath
  [fs file]
  (if (instance? java.nio.file.Path fs)
    (.resolve fs (.toPath file))
    (let [[seg & segs] (file/split-path file)]
      (.getPath fs seg (into-array String segs)))))

(defn mkignores
  [ignores]
  (some->> (seq ignores) (map #(partial re-find %)) (apply some-fn)))

(defn mkvisitor
  [root tree & {:keys [ignore]}]
  (let [ign? (mkignores ignore)]
    (proxy [SimpleFileVisitor] []
      (preVisitDirectory [path attr]
        (let [p (.relativize root path)]
          (if (and ign? (ign? (.toString p))) skip-subtree continue)))
      (visitFile [path attr]
        (with-let [_ continue]
          (let [p (.relativize root path)
                s (path->segs p)]
            (when-not (and ign? (ign? (.toString p)))
              (->> (.toMillis (Files/getLastModifiedTime path link-opts))
                   (hash-map :path s :file path :time)
                   (assoc! tree s)))))))))

(defrecord FileSystemTree [root tree])

(defn mktree
  ([] (FileSystemTree. nil nil))
  ([root & {:keys [ignore]}]
   (FileSystemTree.
     root
     (persistent!
       (with-let [tree (transient {})]
         (Files/walkFileTree root (mkvisitor root tree :ignore ignore)))))))

(defn merge-trees
  [{tree1 :tree} {tree2 :tree}]
  (FileSystemTree. (:root tree1) (merge tree1 tree2)))

(defn tree-diff
  [{t1 :tree :as before} {t2 :tree :as after}]
  (let [reducer #(assoc %1 %2 (:time %3))
        [d1 d2] (map #(reduce-kv reducer {} %) [t1 t2])
        [x y _] (map (comp set keys) (data/diff d1 d2))]
    {:adds (->> (set/difference   y x) (select-keys t2) (assoc after :tree))
     :rems (->> (set/difference   x y) (select-keys t1) (assoc after :tree))
     :chgs (->> (set/intersection x y) (select-keys t2) (assoc after :tree))}))

(defn tree-patch
  [before after link]
  (let [->p #(.toString (segs->path (:root before) %))
        writeop (if (= :all link) :link :write)
        {:keys [adds rems chgs]} (tree-diff before after)]
    (-> (->> rems :tree vals (map #(vector :delete (->p (:path %)))))
        (into (for [x (->> adds :tree (merge (:tree chgs)) vals)]
                [writeop (->p (:path x)) (:file x) (:time x)])))))

(defmethod fsp/patch FileSystemTree
  [before after link]
  (tree-patch before after link))

(defn- reroot
  [new-root segs]
  (.resolve new-root (segs->path new-root segs)))

(defmethod fsp/patch-result FileSystemTree
  [before after]
  (let [update-file #(assoc %1 :file (reroot (:root before) %2))
        update-path #(-> after (get-in [:tree %]) (update-file %))
        update-tree (fn [xs k _] (assoc xs k (update-path k)))]
    (assoc before :tree (reduce-kv update-tree {} (:tree after)))))

(defn mkparents!
  [path]
  (when-let [p (.getParent path)]
    (Files/createDirectories p (into-array FileAttribute []))))

(defn touch!
  [fs path time]
  (let [dst (mkpath fs (io/file path))]
    (util/dbug "Filesystem: touching %s...\n" path)
    (Files/setLastModifiedTime dst (FileTime/fromMillis time))))

(defn copy!
  [fs path src time]
  (let [dst (mkpath fs (io/file path))]
    (util/dbug "Filesystem: copying %s...\n" path)
    (Files/copy src (doto dst mkparents!) copy-opts)
    (Files/setLastModifiedTime dst (FileTime/fromMillis time))))

(defn link!
  [fs path src]
  (let [dst (mkpath fs (io/file path))]
    (util/dbug "Filesystem: linking %s...\n" path)
    (Files/deleteIfExists dst)
    (Files/createLink (doto dst mkparents!) src)))

(defn delete!
  [fs path]
  (util/dbug "Filesystem: deleting %s...\n" path)
  (Files/delete (mkpath fs (io/file path))))

(defn write!
  [fs writer path]
  (let [dst (mkpath fs (io/file path))]
    (mkparents! dst)
    (with-open [os (Files/newOutputStream dst open-opts)]
      (util/dbug "Filesystem: writing %s...\n" path)
      (.write writer os))))

(defn patch!
  [fs before after & {:keys [link]}]
  (with-let [_ (fsp/patch-result before after)]
    (doseq [[op path & [arg1 arg2]] (fsp/patch before after link)]
      (case op
        :delete (delete! fs path)
        :write  (copy!   fs path arg1 arg2)
        :link   (link!   fs path arg1)
        :touch  (touch!  fs path arg1)))))
