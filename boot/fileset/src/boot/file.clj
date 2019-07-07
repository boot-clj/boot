(ns boot.file
  (:refer-clojure :exclude [list sync name file-seq])
  (:require
   [clojure.java.io  :as io]
   [clojure.set      :as set]
   [clojure.data     :as data]
   [clojure.string   :as str])
  (:import
    [java.security MessageDigest]
    [java.math BigInteger]
    [java.io File]
    [java.nio.file Files StandardCopyOption FileVisitOption]
    [java.nio.file.attribute FileAttribute PosixFilePermissions]))

(set! *warn-on-reflection* true)

;; MD5 Digest ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn md5
  "Converts string to MD5 digest."
  [^String s]
  (->> s
    (.getBytes)
    (.digest (MessageDigest/getInstance "MD5"))
    (BigInteger. 1)
    (format "%032x")))
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Boot Files API ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn file?
  "Check if argument is a file."
  [^File f]
  (.isFile (io/file f)))

(defn directory?
  "Check if argument is a directory."
  [^File f]
  (.isDirectory (io/file f)))

(defn exists?
  "Check if file/directory exists."
  [^File f]
  (.exists (io/file f)))

(defn writeable?
  "Check if file/directory is writeable."
  [^File f]
  (.canWrite (io/file f)))

(defn path
  "Return path of file/directory."
  [^File f]
  (.getPath (io/file f)))

(defn name
  "Return name of file/directory."
  [^File f]
  (.getName (io/file f)))

(defn parent
  "Return parent of file/directory."
  [^File f]
  (.getParentFile (io/file f)))

(defn delete!
  "Delete a file/directory."
  [^File file]
  (.delete (io/file file)))

(defn list
  "List files in a directory."
  [^File dir]
  (.listFiles (io/file dir)))

(defn last-modified
  "Get last modified data of file/directory."
  [^File file]
  (.getLastModifiedTime (io/file file)))

(defn last-modified!
  "Set last modified data of file/directory."
  [^File file time]
  (.setLastModifiedTime (io/file file) time))

(defn hard-link!
  "Create a hard link from existing to link."
  [existing link]
  (let [^File link     (path (io/file link))
        ^File existing (path (io/file existing))]
    (Files/deleteIfExists link)
    (Files/createLink link existing)))

(defn sym-link!
  "Create a symbolic link from target to link."
  [target link]
  (let [^File link   (path (io/file link))
        ^File target (path (io/file target))]
    (Files/deleteIfExists link)
    (Files/createSymbolicLink link target)))

(defn sym-link?
  "Checks if file/directory is symbolic link."
  [^File file]
  (Files/isSymbolicLink (path (io/file file))))

(defn relative-path
  "Returns a relativized path between base and target."
  [base target]
  (path (.relativize (path base) (path path))))

(defn file-seq
  "Return a list of files from directory."
  [directory & {:keys [symlinks] :or {symlinks true}}]
  (when directory
    (tree-seq
      #(and (directory? %)
            (or symlinks (not (sym-link? %))))
      #(seq (list %))
      (io/file directory))))

(defn empty-directory?
  "Checks if directory is empty."
  [directory]
  (and (directory? f) (empty? (list f))))

(defn empty-directory!
  "Delete all files within directory recursively."
  [directory]
  (when (exists? directory)
    (let [file-seq #(file-seq % :symlinks false)]
      (mapv delete! (-> directory file-seq rest reverse)))))

(defn move
  "Move a file from source to destination."
  [^File src ^File dest]
  (let [opts [StandardCopyOption/ATOMIC_MOVE
              StandardCopyOption/REPLACE_EXISTING]]
    (Files/move (path src) (path dest) (into-array StandardCopyOption opts))))

(defn replace-path
  "Returns a file path by replacing source with destination directory."
  [source destination file]
  (path (io/file destination (relative-path (io/file source) (io/file file)))))

(defn replace-paths
  "Same as replace-path except operates on a collection of paths."
  [source destination paths]
  (map (partial replace-path source destination) paths))

(defn filter-files
  "Returns a list containing only files from directory."
  [directory]
  (map path (filter file? (file-seq directory))))

(defn map-file
  "Maps java.io.File to all paths."
  [paths]
  (map io/file paths))

(defn write-error
  "Throws an error when unable to write to file/directory."
  [target]
  (let [path (path target)
        msg  (format "Can't write to file or directory (%s)." path)]
    (throw (ex-info msg {:path path}))))

(defn copy
  "Copies a file from source to target directory, preserving last modified date."
  [source target & opts]
  (if-not (writeable? (io/file target))
    (write-error target)
    (let [copy! (partial io/copy source)]
      (if (:hard-link opts *hard-link*)
        (hard-link! source target)
        (let [date (last-modified source)]
          (doto target io/make-parents copy!
            (last-modified! date)))))))

(defn copy-files
  "Copy files from source directory to destination directory."
  [source destination]
  (when (exists? source)
    (let [in-files  (filter-files source)
          out-files (replace-paths source destination in-files)]
      (mapv copy (map-file in-files) (map-file out-files)))))

(defn copy-atomically
  "Copy file from source to destination, uses an atomic opperation."
  [^File source ^File target]
  (let [tmp (tmpfile (name target) nil (parent target))]
    (binding [*hard-link* false]
      (copy source tmp))
    (move tmp target)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:dynamic *ignore*      nil)
(def ^:dynamic *hard-link*   true)

(def windows? (boot.App/isWindows))

(def tmpfile-permissions
  (into-array FileAttribute
              (if windows?
                []
                [(PosixFilePermissions/asFileAttribute
                  (PosixFilePermissions/fromString "rw-------"))])))

(defn walk-file-tree
  "Wrap java.nio.Files/walkFileTree to easily toggle symlink-following behavior."
  [root visitor & {:keys [symlinks] :or {symlinks true}}]
  (let [opts (if symlinks #{FileVisitOption/FOLLOW_LINKS} #{})]
    (Files/walkFileTree root opts Integer/MAX_VALUE visitor)))

(defmacro guard [& exprs]
  `(try (do ~@exprs) (catch Throwable _#)))

(def print-ex
  (delay
    (require 'boot.util)
    (var-get (resolve 'boot.util/print-ex))))

(defn parent-seq
  "Return sequence of this file and all it's parent directories"
  [f]
  (->> f io/file (iterate parent) (take-while identity)))

(defn split-path
  "Return a sequence of directories to this file path."
  [p]
  (->> p parent-seq reverse (map (fn [^File f] (name f)))))

(defn parent? [parent child]
  (contains? (set (parent-seq child)) parent))

(defn ^File tmpfile
  ([prefix postfix]
   (let [path (Files/createTempFile prefix postfix tmpfile-permissions)]
     (doto (.toFile path) (.deleteOnExit))))
  ([prefix postfix ^File dir]
   (let [path (Files/createTempFile (.toPath dir) prefix postfix tmpfile-permissions)]
     (doto (.toFile path) (.deleteOnExit)))))

(defn ^File tmpdir
  ([prefix]
   (io/file (Files/createTempDirectory prefix (into-array FileAttribute []))))
  ([^File dir prefix]
   (io/file (Files/createTempDirectory (path dir) prefix (into-array FileAttribute [])))))

(defn tree-for [& dirs]
  (->> (for [dir dirs]
         (let [path  (-> (if (string? dir) dir (path ^File dir))
                         ((fn [^String s] (.replaceAll s "/$" ""))))
               snip  (count (str path "/"))]
           (->> (file-seq (io/file path))
                (reduce (fn [xs ^File f]
                          (if-not (.isFile f)
                            xs
                            (let [p  (path f)
                                  p' (subs p snip)
                                  r  #(re-find % p')]
                              (if (some r *ignore*)
                                xs
                                (-> (assoc-in xs [:file p'] f)
                                    (assoc-in [:time p'] (last-modified f)))))))
                        {}))))
       (reduce (partial merge-with into) {})))

(defn time-diff [before after]
  ((fn [[b a]] [(set/difference b a) a])
   (->> (data/diff (:time before) (:time after)) (take 2) (map (comp set keys)))))

(defmulti  patch-cp? (fn [pred a b] pred))
(defmethod patch-cp? :default [_ a b] true)
(defmethod patch-cp? :theirs  [_ a b] true)
(defmethod patch-cp? :hash    [_ a b] (not= (md5 a) (md5 b)))

(defn patch [pred before after]
  (let [[x cp] (time-diff before after)
        rm     (set/difference x cp)]
    (concat
      (for [x rm] [:rm x (get-in before [:file x])])
      (for [x cp :let [b (get-in before [:file x])
                       a (get-in after [:file x])]
            :when (patch-cp? pred a b)]
        [:cp x a]))))

(defn sync! [pred dest & srcs]
  (let [before (tree-for dest)
        after  (apply tree-for srcs)]
    (doseq [[op p x] (patch pred before after)]
      (case op
        :rm (delete! x)
        :cp (copy-with-lastmod x (io/file dest p))))))

(defn watcher! [pred & dirs]
  (let [state (atom nil)]
    (fn []
      (let [state' (apply tree-for dirs)
            patch' (patch pred @state state')]
        (reset! state state')
        patch'))))

(defn match-filter?
  [filters f]
  (let [normalize #(if-not windows? % (str/replace % #"\\" "/"))]
    ((apply some-fn (map (partial partial re-find) filters)) (normalize (path ^File f)))))

(defn keep-filters?
  [include exclude f]
  (and
    (or (empty? include) (match-filter? include f))
    (or (empty? exclude) (not (match-filter? exclude f)))))
