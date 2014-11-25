(ns boot.file
  (:require
   [clojure.java.io  :as io]
   [clojure.set      :as set]
   [clojure.data     :as data]
   [boot.from.digest :as digest])
  (:import
   [java.nio.file Files]
   [java.lang.management ManagementFactory])
  (:refer-clojure :exclude [sync name file-seq]))

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
(defn relative-to [base f] (.relativize (.toURI base) (.toURI f)))
(defn file-seq [dir] (when dir (clojure.core/file-seq dir)))

(defmacro guard [& exprs]
  `(try (do ~@exprs) (catch Throwable _#)))

(defn clean! [& files]
  (doseq [f files]
    (doall (->> f io/file file-seq (keep file?) (map #(io/delete-file % true))))))

(defn empty-dir!
  [& dirs]
  (let [{files true dirs' false}
        (->> (map io/file dirs)
             (mapcat (comp rest file-seq))
             (group-by (memfn isFile)))
        to-rm (concat files (reverse dirs'))]
    (doseq [f to-rm] (io/delete-file f true))))

(defn delete-empty-subdirs!
  [dir]
  (let [empty-dir? #(and (.isDirectory %) (empty? (.list %)))
        subdirs    (->> dir io/file file-seq (filter (memfn isDirectory)))]
    (doseq [f (reverse subdirs)]
      (when (empty-dir? f) (io/delete-file f true)))))

(defn parent-seq [f]
  (->> f io/file (iterate #(.getParentFile %)) (take-while identity)))

(defn split-path [p]
  (->> p parent-seq reverse (map (memfn getName))))

(defn parent? [parent child]
  (contains? (set (parent-seq child)) parent))

(defn up-parents
  [f base & parts]
  (->> (io/file f)
    (relative-to (io/file base))
    (.getPath)
    parent-seq
    butlast
    (map (constantly ".."))
    (concat (reverse parts))
    reverse
    (apply io/file)
    (.getPath)))

(defn lockfile
  [f]
  (let [f (io/file f)]
    (when (.createNewFile f)
      (doto f
        .deleteOnExit
        (spit (name (ManagementFactory/getRuntimeMXBean)))))))

(defn tmpfile
  ([prefix postfix]
   (doto (java.io.File/createTempFile prefix postfix) .deleteOnExit))
  ([prefix postfix dir]
   (doto (java.io.File/createTempFile prefix postfix dir) .deleteOnExit)))

(defn srcdir->outdir
  [fname srcdir outdir]
  (.getPath (io/file outdir (.getPath (relative-to (io/file srcdir) (io/file fname))))))

(defn delete-all
  [dir]
  (if (exists? dir)
    (mapv #(.delete %) (reverse (rest (file-seq (io/file dir)))))))

(defn copy-with-lastmod
  [src-file dst-file]
  (let [last-mod (.lastModified src-file)]
    (io/make-parents dst-file)
    (when (.exists dst-file) (.delete dst-file))
    (if *hard-link*
      (Files/createLink (.toPath dst-file) (.toPath src-file))
      (doto dst-file ((partial io/copy src-file)) (.setLastModified last-mod)))))

(defn copy-files
  [src dest]
  (if (exists? src)
    (let [files  (map #(.getPath %) (filter file? (file-seq (io/file src))))
          outs   (map #(srcdir->outdir % src dest) files)]
      (mapv copy-with-lastmod (map io/file files) (map io/file outs)))))

(defn select-keys-by [m pred?]
  (select-keys m (filter pred? (keys m))))

(defn dir-set
  ([dir]
   (let [info (juxt #(relative-to dir %) #(.lastModified %))
         mapf #(zipmap [:dir :abs :rel :mod] (list* dir % (info %)))
         ign? #(and *ignore* (*ignore* %))]
     (set (mapv mapf (filter (memfn isFile) (remove ign? (file-seq dir)))))))
  ([dir1 dir2 & dirs]
   (reduce set/union (map dir-set (list* dir1 dir2 dirs)))))

(defn dir-map
  [& dirs]
  (->>
    (apply dir-set (mapv io/file dirs))
    (mapv #(vector (.getPath (:rel %)) %))
    (into {})))

(defn dir-map-ext
  [exts & dirs]
  (let [ext  #(let [f (name (io/file %))] (subs f (.lastIndexOf f ".")))
        ext? #(contains? exts (ext %))]
    (select-keys-by (apply dir-map dirs) ext?)))

(defn what-changed
  ([dst-map src-map] (what-changed dst-map src-map :time))
  ([dst-map src-map algo]
   (let [[created deleted modified]
         (data/diff (set (keys src-map)) (set (keys dst-map)))
         algos {:hash #(not= (digest/md5 (:abs (src-map %)))
                             (digest/md5 (:abs (dst-map %))))
                :time #(< (:mod (dst-map %)) (:mod (src-map %)))}
         modified (set (filter (algos algo) modified))]
     [(set/union created modified) deleted])))

(defn diff
  [algo dst src & srcs]
  (let [d (dir-map (io/file dst))
        s (->> (cons src srcs)
            (map io/file)
            (apply dir-map))
        [to-cp to-rm] (what-changed d s algo)
        cp (map #(vector :cp (:abs (s %)) (io/file dst %)) to-cp)
        rm (map #(vector :rm (io/file dst %)) to-rm)]
    (concat cp rm)))

(defn match-filter?
  [filters f]
  ((apply some-fn (map (partial partial re-find) filters)) (.getPath f)))

(defn keep-filters?
  [include exclude f]
  (and
    (or (empty? include) (match-filter? include f))
    (or (empty? exclude) (not (match-filter? exclude f)))))

(defn sync*
  [ops]
  (let [opfn {:rm #(when *sync-delete* (.delete (nth % 1)))
              :cp #(when (keep-filters? *include* *exclude* (nth % 2))
                     (copy-with-lastmod (nth % 1) (nth % 2)))}]
    (doseq [[op s d :as cmd] ops] ((opfn op) cmd))))

(defn sync
  [algo dst src & srcs]
  (sync* (apply diff algo dst src srcs)))

(defn make-watcher [dir]
  (let [prev (atom nil)]
    (fn []
      (let [only-file #(filter file? %)
            make-info #(guard (vector [% (.lastModified %)] [% (digest/md5 %)]))
            file-info #(remove nil? (mapcat make-info %))
            info      (->> dir io/file file-seq only-file file-info set)
            mods      (set/difference (set/union info @prev) (set/intersection info @prev))
            by        #(->> %2 (filter (comp %1 second)) (map first) set)]
        (reset! prev info)
        {:hash (by string? mods) :time (by number? mods)}))))

