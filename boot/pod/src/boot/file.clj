(ns boot.file
  (:require
   [boot.from.digest :as d]
   [clojure.data     :as data]
   [clojure.java.io  :refer [copy file delete-file make-parents]]
   [clojure.set      :refer [union intersection difference]])
  (:import
   [java.nio.file Files]
   [java.lang.management ManagementFactory])
  (:refer-clojure :exclude [sync name file-seq]))

(def ^:dynamic *include*     nil)
(def ^:dynamic *exclude*     nil)
(def ^:dynamic *ignore*      nil)
(def ^:dynamic *sync-delete* true)

(defn file? [f] (when (try (.isFile (file f)) (catch Throwable _)) f))
(defn dir? [f] (when (try (.isDirectory (file f)) (catch Throwable _)) f))
(defn exists? [f] (when (try (.exists (file f)) (catch Throwable _)) f))
(defn path [f] (.getPath (file f)))
(defn name [f] (.getName (file f)))
(defn relative-to [base f] (.relativize (.toURI base) (.toURI f)))
(defn file-seq [dir] (when dir (clojure.core/file-seq dir)))

(defmacro guard [& exprs]
  `(try (do ~@exprs) (catch Throwable _#)))

(defn clean! [& files]
  (doseq [f files]
    (doall (->> f file file-seq (keep file?) (map #(delete-file % true))))))

(defn parent-seq [f]
  (->> f file (iterate #(.getParentFile %)) (take-while identity)))

(defn split-path [p]
  (->> p parent-seq reverse (map (memfn getName))))

(defn parent? [parent child]
  (some (partial = parent) (parent-seq child)))

(defn up-parents
  [f base & parts]
  (->> (file f)
    (relative-to (file base))
    (.getPath)
    parent-seq
    butlast
    (map (constantly ".."))
    (concat (reverse parts))
    reverse
    (apply file)
    (.getPath)))

(defn lockfile
  [f]
  (let [f (file f)]
    (when (.createNewFile f)
      (doto f
        .deleteOnExit
        (spit (name (ManagementFactory/getRuntimeMXBean)))))))

(defn tmpfile
  [prefix postfix]
  (doto (java.io.File/createTempFile prefix postfix) .deleteOnExit))

(defn srcdir->outdir
  [fname srcdir outdir]
  (.getPath (file outdir (.getPath (relative-to (file srcdir) (file fname))))))

(defn delete-all
  [dir]
  (if (exists? dir)
    (mapv #(.delete %) (reverse (rest (file-seq (file dir)))))))

(defn copy-with-lastmod
  [src-file dst-file]
  (make-parents dst-file)
  (when (.exists dst-file) (.delete dst-file))
  (Files/createLink (.toPath dst-file) (.toPath src-file)))

(defn copy-files
  [src dest]
  (if (exists? src)
    (let [files  (map #(.getPath %) (filter file? (file-seq (file src)))) 
          outs   (map #(srcdir->outdir % src dest) files)]
      (mapv copy-with-lastmod (map file files) (map file outs)))))

(defn select-keys-by [m pred?]
  (select-keys m (filter pred? (keys m))))

(defn dir-set 
  ([dir] 
   (let [info (juxt #(relative-to dir %) #(.lastModified %))
         mapf #(zipmap [:dir :abs :rel :mod] (list* dir % (info %)))]
     (set (mapv mapf (filter (memfn isFile) (file-seq dir))))))
  ([dir1 dir2 & dirs]
   (reduce union (map dir-set (list* dir1 dir2 dirs)))))

(defn dir-map
  [& dirs]
  (->>
    (apply dir-set (mapv file dirs))
    (mapv #(vector (.getPath (:rel %)) %))
    (into {})))

(defn dir-map-ext
  [exts & dirs]
  (let [ext  #(let [f (name (file %))] (subs f (.lastIndexOf f ".")))
        ext? #(contains? exts (ext %))]
    (select-keys-by (apply dir-map dirs) ext?)))

(defn what-changed
  ([dst-map src-map] (what-changed dst-map src-map :time))
  ([dst-map src-map algo] 
   (let [[created deleted modified]
         (data/diff (set (keys src-map)) (set (keys dst-map)))
         algos {:hash #(not= (d/md5 (:abs (src-map %)))
                             (d/md5 (:abs (dst-map %)))) 
                :time #(< (:mod (dst-map %)) (:mod (src-map %)))} 
         modified (set (filter (algos algo) modified))]
     [(union created modified) deleted])))

(defn diff
  [algo dst src & srcs]
  (let [d (dir-map (file dst))
        s (->> (cons src srcs)
            (map file)
            (remove #(and *ignore* (*ignore* %)))
            (apply dir-map))
        [to-cp to-rm] (what-changed d s algo)
        cp (map #(vector :cp (:abs (s %)) (file dst %)) to-cp) 
        rm (map #(vector :rm (file dst %)) to-rm)]
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
              :cp #(when (and (keep-filters? *include* *exclude* (nth % 2))
                           (or (not *ignore*) (not (*ignore* (nth % 1)))))
                     (copy-with-lastmod (nth % 1) (nth % 2)))}]
    (doseq [[op s d :as cmd] ops] ((opfn op) cmd))))

(defn sync
  [algo dst src & srcs]
  (sync* (apply diff algo dst src srcs)))

(defn make-watcher [dir]
  (let [prev (atom nil)]
    (fn []
      (let [only-file #(filter file? %)
            make-info #(guard (vector [% (.lastModified %)] [% (d/md5 %)])) 
            file-info #(remove nil? (mapcat make-info %)) 
            info      (->> dir file file-seq only-file file-info set)
            mods      (difference (union info @prev) (intersection info @prev))
            by        #(->> %2 (filter (comp %1 second)) (map first) set)]
        (reset! prev info)
        {:hash (by string? mods) :time (by number? mods)}))))

