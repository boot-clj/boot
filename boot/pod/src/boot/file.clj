(ns boot.file
  (:require
   [clojure.java.io  :as io]
   [clojure.set      :as set]
   [clojure.data     :as data]
   [boot.from.digest :as digest])
  (:import
   [java.net URI]
   [java.io File]
   [java.nio.file.attribute FileAttribute]
   [java.nio.file Files StandardCopyOption]
   [java.lang.management ManagementFactory])
  (:refer-clojure :exclude [sync name file-seq]))

(set! *warn-on-reflection* true)

(def ^:dynamic *include*     nil)
(def ^:dynamic *exclude*     nil)
(def ^:dynamic *ignore*      nil)
(def ^:dynamic *sync-delete* true)
(def ^:dynamic *hard-link*   true)

(defn file? [f] (when (try (.isFile (io/file f)) (catch Throwable _)) f))
(defn dir? [f] (when (try (.isDirectory (io/file f)) (catch Throwable _)) f))
(defn exists? [f] (when (try (.exists (io/file f)) (catch Throwable _)) f))
(defn path [f] (.getPath (io/file f)))
(defn name [f] (.getName (io/file f)))
(defn parent [f] (.getParentFile (io/file f)))

(defn file-seq
  [dir & {:keys [follow-symlinks]
          :or   {follow-symlinks true}}]
  (when dir
    (tree-seq
      (fn [^java.io.File f]
        (and (.isDirectory f)
             (or follow-symlinks
                 (not (Files/isSymbolicLink (.toPath f))))))
      (fn [^java.io.File d] (seq (.listFiles d)))
      dir)))

(def file-seq-nofollow #(file-seq % :follow-symlinks false))

(defmacro guard [& exprs]
  `(try (do ~@exprs) (catch Throwable _#)))

(def print-ex
  (delay
    (require 'boot.util)
    (var-get (resolve 'boot.util/print-ex))))

(defn delete-file
  [f]
  (try (io/delete-file f)
       (catch Exception err (@print-ex err))))

(defn clean! [& files]
  (doseq [f files]
    (doall (->> f io/file file-seq-nofollow (keep file?) (map delete-file)))))

(defn empty-dir!
  [& dirs]
  (let [{files true dirs' false}
        (->> (map io/file dirs)
             (mapcat (comp rest file-seq-nofollow))
             (group-by (fn [^File f] (.isFile f))))
        to-rm (concat files (reverse dirs'))]
    (doseq [f to-rm] (delete-file f))))

(defn delete-empty-subdirs!
  [dir]
  (let [empty-dir? (fn [^File f] (and (.isDirectory f) (empty? (.list f))))
        subdirs    (->> dir io/file file-seq-nofollow (filter (fn [^File f] (.isDirectory f))))]
    (doseq [f (reverse subdirs)]
      (when (empty-dir? f) (delete-file f)))))

(defn- contains-files?
  [dir]
  (->> dir file-seq (filter (fn [^File f] (.isFile f))) seq))

(defn parent-seq
  "Return sequence of this file and all it's parent directories"
  [f]
  (->> f io/file (iterate parent) (take-while identity)))

(defn split-path
  "Return sequence of this file's and its parent directories names.

   e.g. public/js/main.js -> (\"public\" \"js\" \"main.js\")"
  [p]
  (->> p parent-seq reverse (map (fn [^File f] (.getName f)))))

(defn move
  [^File src ^File dest & {:keys [atomic replace]
                           :or {atomic  StandardCopyOption/ATOMIC_MOVE
                                replace StandardCopyOption/REPLACE_EXISTING}}]
  (let [opts (filter identity [atomic replace])
        opts-array (into-array StandardCopyOption opts)]
    (Files/move (.toPath src) (.toPath dest) opts-array)))

(defn parent? [parent child]
  (contains? (set (parent-seq child)) parent))

(defn ^File relative-to
  "Return relative path to f from directory base."
  [base f]
  {:pre [(not (nil? base)) (not (nil? f))]}
  (let [base-path (.toPath (io/file base))
        f-path (.toPath (io/file f))
        relpath (.relativize base-path f-path)]
    (.toFile relpath)))

(defn ^File lockfile
  [f]
  (let [f (io/file f)]
    (when (.createNewFile f)
      (doto f
        .deleteOnExit
        (spit (name (ManagementFactory/getRuntimeMXBean)))))))

(defn ^File tmpfile
  ([prefix postfix]
   (doto (java.io.File/createTempFile prefix postfix) .deleteOnExit))
  ([prefix postfix dir]
   (doto (java.io.File/createTempFile prefix postfix dir) .deleteOnExit)))

(defn ^File tmpdir
  ([prefix]
   (.toFile (Files/createTempDirectory prefix (into-array FileAttribute []))))
  ([^File dir prefix]
   (.toFile (Files/createTempDirectory (.toPath dir) prefix (into-array FileAttribute [])))))

(defn srcdir->outdir
  [fname srcdir outdir]
  (.getPath (io/file outdir (.getPath (relative-to (io/file srcdir) (io/file fname))))))

(defn delete-all
  [dir]
  (when (exists? dir)
    (mapv (fn [^File f] (.delete f))
          (-> dir io/file file-seq-nofollow rest reverse))))

(defn hard-link
  [^File existing-file ^File link-file]
  (Files/deleteIfExists (.toPath link-file))
  (Files/createLink (.toPath link-file) (.toPath existing-file)))

(defn sym-link
  [^File target-file ^File link-file]
  (Files/createSymbolicLink (.toPath link-file) (.toPath target-file) (make-array FileAttribute 0)))

(defn copy-with-lastmod
  [^File src-file ^File dst-file]
  (let [last-mod (.lastModified src-file)
        cp-src!  (partial io/copy src-file)
        dst-par  (.getParent dst-file)]
    (io/make-parents dst-file)
    (when-not (.canWrite (io/file dst-par))
      (throw (ex-info (format "Can't write to directory (%s)." dst-par) {:dir dst-par})))
    (when (.exists dst-file) (.delete dst-file))
    (if *hard-link*
      (hard-link src-file dst-file)
      (doto dst-file cp-src! (.setLastModified last-mod)))))

(defn copy-files
  [src dest]
  (if (exists? src)
    (let [files  (map #(.getPath ^File %) (filter file? (file-seq (io/file src))))
          outs   (map #(srcdir->outdir % src dest) files)]
      (mapv copy-with-lastmod (map io/file files) (map io/file outs)))))

(defn tree-for [& dirs]
  (->> (for [dir dirs]
         (let [path  (-> (if (string? dir) dir (.getPath ^File dir))
                         ((fn [^String s] (.replaceAll s "/$" ""))))
               snip  (count (str path "/"))]
           (->> (file-seq (io/file path))
                (filter (fn [^File f] (.isFile f)))
                (reduce #(let [p (subs (.getPath ^File %2) snip)]
                           (-> (assoc-in %1 [:file p] %2)
                               (assoc-in [:time p] (.lastModified ^File %2))))
                        {}))))
       (reduce (partial merge-with into) {})))

(defn time-diff [before after]
  ((fn [[b a]] [(set/difference b a) a])
   (->> (data/diff (:time before) (:time after)) (take 2) (map (comp set keys)))))

(defmulti  patch-cp? (fn [pred a b] pred))
(defmethod patch-cp? :default [_ a b] true)
(defmethod patch-cp? :theirs  [_ a b] true)
(defmethod patch-cp? :hash    [_ a b] (not= (digest/md5 a) (digest/md5 b)))

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
        :rm (delete-file x)
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
  ((apply some-fn (map (partial partial re-find) filters)) (.getPath ^File f)))

(defn keep-filters?
  [include exclude f]
  (and
    (or (empty? include) (match-filter? include f))
    (or (empty? exclude) (not (match-filter? exclude f)))))
