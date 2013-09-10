(ns tailrecursion.boot.file
  (:require
    [digest           :as d]
    [clojure.data     :as data]
    [clojure.java.io  :refer [copy file delete-file make-parents]]
    [clojure.set      :refer [union intersection difference]])
  (:import
    java.lang.management.ManagementFactory)
  (:refer-clojure :exclude [sync name file-seq]))

(defn file? [f] (when (try (.isFile (file f)) (catch Throwable _)) f))
(defn dir? [f] (when (try (.isDirectory (file f)) (catch Throwable _)) f))
(defn exists? [f] (when (try (.exists (file f)) (catch Throwable _)) f))
(defn path [f] (.getPath (file f)))
(defn name [f] (.getName (file f)))
(defn relative-to [base f] (.relativize (.toURI base) (.toURI f)))
(defn file-seq [dir] (when dir (clojure.core/file-seq dir)))

(defn clean! [& files]
  (doseq [f files]
    (doall (->> f file file-seq (keep file?) (map #(delete-file % true))))))

(defn parent-seq [f]
  (->> f file (iterate #(.getParentFile %)) (take-while identity)))

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
  (copy src-file dst-file)
  (.setLastModified dst-file (.lastModified src-file)))

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
     (set (mapv mapf (filter file? (file-seq dir))))))
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
        s (apply dir-map (mapv file (cons src srcs)))
        [to-cp to-rm] (what-changed d s algo)
        cp (map #(vector :cp (:abs (s %)) (file dst %)) to-cp) 
        rm (map #(vector :rm (file dst %)) to-rm)]
    (concat cp rm)))

(defn sync
  [algo dst src & srcs]
  (let [op {:cp #(copy-with-lastmod (nth % 1) (nth % 2))
            :rm #(.delete (nth % 1))}]
    (doall (map #((op (first %)) %) (apply diff algo dst src srcs)))))

(defn make-watcher [dir]
  (let [prev (atom nil)]
    (fn []
      (let [only-file #(filter file? %)
            make-info #(vector [% (.lastModified %)] [% (d/md5 %)])
            file-info #(mapcat make-info %)
            info      (->> dir file file-seq only-file file-info set)
            mods      (difference (union info @prev) (intersection info @prev))
            by        #(->> %2 (filter (comp %1 second)) (map first) set)]
        (reset! prev info)
        {:hash (by string? mods) :time (by number? mods)}))))

