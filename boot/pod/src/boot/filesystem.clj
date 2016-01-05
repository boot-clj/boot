(ns boot.filesystem
  (:require
    [clojure.java.io        :as io]
    [clojure.set            :as set]
    [clojure.data           :as data]
    [clojure.string         :as string]
    [boot.filesystem.patch  :as fsp]
    [boot.file              :as file]
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

(defprotocol IToPath
  (->path [x] "Returns a java.nio.file.Path for x."))

(extend-protocol IToPath
  java.nio.file.Path
  (->path [x] x)

  java.io.File
  (->path [x] (.toPath x))

  java.lang.String
  (->path [x] (.toPath (io/file x)))

  java.nio.file.FileSystem
  (->path [x] (first (.getRootDirectories x))))

(defn path->segs
  [^Path path]
  (->> path .iterator iterator-seq (map str)))

(defn- segs->path
  [^Path any-path-same-filesystem segs]
  (let [segs-ary (into-array String (rest segs))]
    (-> (.getFileSystem any-path-same-filesystem)
        (.getPath (first segs) segs-ary))))

(defn- rel
  [root segs]
  (.resolve root (segs->path root segs)))

(defn mkjarfs
  [^File jarfile & {:keys [create]}]
  (when create (io/make-parents jarfile))
  (let [jaruri (->> jarfile .getCanonicalFile .toURI (str "jar:") URI/create)]
    (FileSystems/newFileSystem jaruri {"create" (str (boolean create))})))

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
                   (swap! tree assoc s)))))))))

(defrecord FileSystemTree [root tree])

(defn mktree
  ([] (FileSystemTree. nil nil))
  ([root & {:keys [ignore]}]
   (FileSystemTree.
     root
     @(with-let [tree (atom {})]
        (Files/walkFileTree root (mkvisitor root tree :ignore ignore))))))

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
  (let [->p     (partial segs->path (:root before))
        writeop (if (= :all link) :link :write)
        {:keys [adds rems chgs]} (tree-diff before after)]
    (-> (->> rems :tree vals (map #(vector :delete (:path %))))
        (into (for [x (->> adds :tree (merge (:tree chgs)) vals)]
                [writeop (:path x) (:file x) (:time x)])))))

(defmethod fsp/patch FileSystemTree
  [before after link]
  (tree-patch before after link))

(defmethod fsp/patch-result FileSystemTree
  [before after]
  (let [update-file #(assoc %1 :file (rel (:root before) %2))
        update-path #(-> after (get-in [:tree %]) (update-file %))
        update-tree (fn [xs k _] (assoc xs k (update-path k)))]
    (assoc before :tree (reduce-kv update-tree {} (:tree after)))))

(defn mkparents!
  [path]
  (when-let [p (.getParent path)]
    (Files/createDirectories p (into-array FileAttribute []))))

(defn touch!
  [dest path time]
  (let [dst (rel dest path)]
    (util/dbug "Filesystem: touching %s...\n" (string/join "/" path))
    (Files/setLastModifiedTime dst (FileTime/fromMillis time))))

(defn copy!
  [dest path src time]
  (let [dst (rel dest path)]
    (util/dbug "Filesystem: copying %s...\n" (string/join "/" path))
    (Files/copy src (doto dst mkparents!) copy-opts)
    (Files/setLastModifiedTime dst (FileTime/fromMillis time))))

(defn link!
  [dest path src]
  (let [dst (rel dest path)]
    (util/dbug "Filesystem: linking %s...\n" (string/join "/" path))
    (Files/deleteIfExists dst)
    (Files/createLink (doto dst mkparents!) src)))

(defn delete!
  [dest path]
  (util/dbug "Filesystem: deleting %s...\n" (string/join "/" path))
  (Files/delete (rel dest path)))

(defn write!
  [dest writer path]
  (let [dst (rel dest path)]
    (mkparents! dst)
    (with-open [os (Files/newOutputStream dst open-opts)]
      (util/dbug "Filesystem: writing %s...\n" (string/join "/" path))
      (.write writer os))))

(defn patch!
  [dest before after & {:keys [link]}]
  (with-let [_ (fsp/patch-result before after)]
    (doseq [[op path & [arg1 arg2]] (fsp/patch before after link)]
      (case op
        :delete (delete! dest path)
        :write  (copy!   dest path arg1 arg2)
        :link   (link!   dest path arg1)
        :touch  (touch!  dest path arg1)))))
