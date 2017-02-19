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
    [java.util.zip ZipEntry ZipOutputStream ZipException]
    [java.util.jar JarEntry JarOutputStream Manifest Attributes$Name]
    [java.nio.file.attribute FileAttribute FileTime PosixFilePermission
     PosixFilePermissions]
    [java.nio.file Path Files FileSystems StandardCopyOption StandardOpenOption
     LinkOption SimpleFileVisitor FileVisitResult]))

(set! *warn-on-reflection* true)

(def continue     FileVisitResult/CONTINUE)
(def skip-subtree FileVisitResult/SKIP_SUBTREE)

(def ^"[Ljava.nio.file.LinkOption;" link-opts
  (into-array LinkOption []))

(def ^"[Ljava.nio.file.attribute.FileAttribute;" tmp-attrs
  (into-array FileAttribute []))

(def ^"[Ljava.nio.file.StandardCopyOption;" copy-opts
  (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING]))

(def ^"[Ljava.nio.file.StandardOpenOption;" open-opts
  (into-array StandardOpenOption [StandardOpenOption/CREATE]))

(def read-only
  (PosixFilePermissions/fromString "r--r--r--"))

(def windows? (boot.App/isWindows))

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

(defn- ^Path segs->path
  [^Path any-path-same-filesystem segs]
  (let [segs-ary (into-array String (rest segs))]
    (-> (.getFileSystem any-path-same-filesystem)
        (.getPath (first segs) segs-ary))))

(defn- rel
  [^Path root segs]
  (.resolve root (segs->path root segs)))

(defn mkjarfs
  [^File jarfile & {:keys [create]}]
  (when create (io/make-parents jarfile))
  (let [jaruri (->> jarfile .getCanonicalFile .toURI (str "jar:") URI/create)
        ^java.util.Map opts {"create" (str (boolean create))}]
    (FileSystems/newFileSystem jaruri opts)))

(defn mkignores
  [ignores]
  (some->> (seq ignores) (map #(partial re-find %)) (apply some-fn)))

(defn mkvisitor
  [^Path root tree & {:keys [ignore]}]
  (let [ign? (mkignores ignore)]
    (proxy [SimpleFileVisitor] []
      (preVisitDirectory [path attr]
        (let [p (.relativize root path)]
          (try (if (and ign? (ign? (.toString p))) skip-subtree continue)
               (catch java.nio.file.NoSuchFileException _
                 (util/dbug* "Filesystem: file not found: %s\n" (.toString p))
                 skip-subtree))))
      (visitFile [path attr]
        (with-let [_ continue]
          (let [p (.relativize root path)
                s (path->segs p)]
            (try (when-not (and ign? (ign? (.toString p)))
                   (->> (.toMillis (Files/getLastModifiedTime path link-opts))
                        (hash-map :path s :file path :time)
                        (swap! tree assoc s)))
                 (catch java.nio.file.NoSuchFileException _
                   (util/dbug* "Filesystem: file not found: %s\n" (.toString p))))))))))

(defrecord FileSystemTree [root tree])

(defn mktree
  ([] (FileSystemTree. nil nil))
  ([root & {:keys [ignore]}]
   (FileSystemTree.
     root
     @(with-let [tree (atom {})]
        (file/walk-file-tree root (mkvisitor root tree :ignore ignore))))))

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
  [^Path path]
  (when-let [p (.getParent path)]
    (Files/createDirectories p (into-array FileAttribute []))))

(defn touch!
  [dest path time]
  (let [dst (rel dest path)]
    (util/dbug* "Filesystem: touching %s...\n" (string/join "/" path))
    (Files/setLastModifiedTime dst (FileTime/fromMillis time))))

(defn copy!
  [^Path dest path ^Path src time & {:keys [mode]}]
  (let [dst (doto (rel dest path) mkparents!)]
    (util/dbug* "Filesystem: copying %s...\n" (string/join "/" path))
    (try (Files/copy ^Path src ^Path dst copy-opts)
         (Files/setLastModifiedTime dst (FileTime/fromMillis time))
         (when (and mode (not windows?)) (Files/setPosixFilePermissions dst mode))
         (catch java.nio.file.NoSuchFileException ex
           (util/dbug* "Filesystem: %s\n", (str ex))))))

(defn link!
  [dest path src & {:keys [mode]}]
  (let [dst (rel dest path)]
    (util/dbug* "Filesystem: linking %s...\n" (string/join "/" path))
    (try (Files/deleteIfExists dst)
         (Files/createLink (doto dst mkparents!) src)
         (when (and mode (not windows?)) (Files/setPosixFilePermissions dst mode))
         (catch java.nio.file.NoSuchFileException ex
           (util/dbug* "Filesystem: %s\n" (str ex))))))

(defn delete!
  [dest path]
  (util/dbug* "Filesystem: deleting %s...\n" (string/join "/" path))
  (try (Files/delete (rel dest path))
       (catch java.nio.file.NoSuchFileException ex
         (util/dbug* "Filesystem: %s\n" (str ex)))))

(defn write!
  [dest writer-fn path]
  (let [dst (rel dest path)]
    (mkparents! dst)
    (with-open [os (Files/newOutputStream dst open-opts)]
      (util/dbug* "Filesystem: writing %s...\n" (string/join "/" path))
      (writer-fn os))))

(defn patch!
  [dest before after & {:keys [link mode]}]
  (with-let [_ (fsp/patch-result before after)]
    (doseq [[op path & [arg1 arg2]] (fsp/patch before after link)]
      (case op
        :delete (delete! dest path)
        :write  (copy!   dest path arg1 arg2 :mode mode)
        :link   (link!   dest path arg1 :mode mode)
        :touch  (touch!  dest path arg1)))))
