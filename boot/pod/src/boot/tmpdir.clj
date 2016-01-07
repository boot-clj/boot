(ns boot.tmpdir
  (:refer-clojure :exclude [time hash])
  (:require
    [clojure.java.io        :as io]
    [clojure.set            :as set]
    [clojure.data           :as data]
    [boot.filesystem        :as fs]
    [boot.filesystem.patch  :as fsp]
    [boot.pod               :as pod]
    [boot.util              :as util]
    [boot.file              :as file]
    [boot.from.digest       :as digest])
  (:import
    [java.io File]
    [java.util Properties]))

(set! *warn-on-reflection* true)

(def CACHE_VERSION "1.0.0")
(def state         (atom {:prev {} :cache {}}))

;; records and protocols ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

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

;; helper functions ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- file-stat
  [^File f]
  (let [h (digest/md5 f)
        t (.lastModified f)]
    {:id (str h "." t) :hash h :time t}))

(defn- scratch-dir!
  [^File scratch]
  (file/tmpdir scratch "boot-scratch"))

(def ^:dynamic *hard-link* nil)

(defn- add-blob!
  [^File blob ^File src id]
  (let [out (io/file blob id)]
    (when-not (.exists out)
      (if *hard-link*
        (do (.setReadOnly src)
            (file/hard-link src out))
        (let [tmp (File/createTempFile (.getName out) nil blob)]
          (io/copy src tmp)
          (.setReadOnly tmp)
          (file/move tmp out))))))

(defn- dir->tree!
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
  (io/file (cache-dir cache-key) "manifest.properties"))

(defn- read-manifest
  [^File manifile ^File bdir]
  (with-open [r (io/input-stream manifile)]
    (let [p (doto (Properties.) (.load r))]
      (-> #(let [id   (.getProperty p %2)
                 hash (subs id 0 32)
                 time (Long/parseLong (subs id 33))
                 m    {:id id :path %2 :hash hash :time time :bdir bdir}]
             (->> m map->TmpFile (assoc %1 %2)))
          (reduce {} (enumeration-seq (.propertyNames p)))))))

(defn- write-manifest!
  [^File manifile manifest]
  (with-open [w (io/output-stream manifile)]
    (let [p (Properties.)]
      (doseq [[path {:keys [id]}] manifest]
        (.setProperty p path id))
      (.store p w nil))))

(defn- apply-mergers!
  [mergers ^File old-file path ^File new-file ^File merged-file]
  (when-let [merger (some (fn [[re v]] (when (re-find re path) v)) mergers)]
    (util/dbug* "Merging duplicate entry (%s)\n" path)
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

(defn- get-cached!
  [cache-key seedfn scratch]
  (util/dbug* "Adding cached fileset %s...\n" cache-key)
  (or (get-in @state [:cache cache-key])
      (let [cache-dir (cache-dir cache-key)
            manifile  (manifest-file cache-key)
            store!    #(util/with-let [m %]
                         (swap! state assoc-in [:cache cache-key] m))]
        (or (and (.exists manifile)
                 (store! (read-manifest manifile cache-dir)))
            (let [tmp-dir (scratch-dir! scratch)]
              (util/dbug* "Not found in cache: %s...\n" cache-key)
              (.mkdirs cache-dir)
              (seedfn tmp-dir)
              (binding [*hard-link* true]
                (let [m (dir->tree! tmp-dir cache-dir)]
                  (write-manifest! manifile m)
                  (store! (read-manifest manifile cache-dir)))))))))

(defn- merge-trees!
  [old new mergers scratch]
  (util/with-let [tmp (scratch-dir! scratch)]
    (doseq [[path newtmp] new]
      (when-let [oldtmp (get old path)]
        (util/dbug* "Merging %s...\n" path)
        (let [newf   (io/file (bdir newtmp) (id newtmp))
              oldf   (io/file (bdir oldtmp) (id oldtmp))
              mergef (doto (io/file tmp path) io/make-parents)]
          (apply-mergers! mergers oldf path newf mergef))))))

(defn- comp-res
  [regexes]
  (when-let [res (seq regexes)]
    (->> (map #(partial re-find %) res) (apply some-fn))))

(defn- filter-tree
  [tree include exclude]
  (let [ex  (comp-res exclude)
        in  (when-let [in (comp-res include)] (complement in))
        rm? (or (and in ex #(or (in %) (ex %))) in ex)]
    (if-not rm? tree (reduce-kv #(if (rm? %2) %1 (assoc %1 %2 %3)) {} tree))))

(defn- index
  [key tree]
  (reduce-kv #(assoc %1 (get %3 key) %3) {} tree))

(defn- diff-tree
  [tree props]
  (let [->map #(select-keys % props)]
    (reduce-kv #(assoc %1 %2 (->map %3)) {} tree)))

(defn- diff*
  [{t1 :tree :as before} {t2 :tree :as after} props]
  (if-not before
    {:added   after
     :removed (assoc after :tree {})
     :changed (assoc after :tree {})}
    (let [props   (or (seq props) [:id])
          d1      (diff-tree t1 props)
          d2      (diff-tree t2 props)
          [x y _] (map (comp set keys) (data/diff d1 d2))]
      {:added   (->> (set/difference   y x) (select-keys t2) (assoc after :tree))
       :removed (->> (set/difference   x y) (select-keys t1) (assoc after :tree))
       :changed (->> (set/intersection x y) (select-keys t2) (assoc after :tree))})))

(defn- fatal-conflict?
  [^File dest]
  (if (.isDirectory dest)
    (let [tree (->> dest file-seq reverse)]
      (or (not (every? #(.isDirectory ^File %) tree))
          (doseq [^File f tree] (.delete f))))
    (not (let [d (.getParentFile dest)]
           (or (.isDirectory d) (.mkdirs d))))))

;; fileset implementation ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defrecord TmpFileSet [dirs tree blob scratch]
  ITmpFileSet

  (ls [this]
    (set (vals tree)))

  (commit! [this]
    (let [{:keys [dirs tree blob]} this
          prev (get-in @state [:prev dirs])
          {:keys [added removed changed]} (diff* prev this [:id :dir])]
      (util/dbug* "Committing fileset...\n")
      (doseq [tmpf (set/union (ls removed) (ls changed))
              :let [prev (get-in prev [:tree (path tmpf)])
                    exists? (.exists ^File (file prev))
                    op (if exists? "removing" "no-op")]]
        (util/dbug* "Commit: %-8s %s %s...\n" op (id prev) (path prev))
        (when exists? (file/delete-file (file prev))))
      (let [this (loop [this this
                        [tmpf & tmpfs]
                        (->> (set/union (ls added) (ls changed))
                             (sort-by (comp count path) >))]
                   (or (and (not tmpf) this)
                       (let [p    (path tmpf)
                             dst  (file tmpf)
                             src  (io/file (bdir tmpf) (id tmpf))
                             err? (fatal-conflict? dst)
                             this (or (and (not err?) this)
                                      (update-in this [:tree] dissoc p))]
                         (if err? 
                           (util/warn "Merge conflict: not adding %s\n" p)
                           (do (util/dbug* "Commit: adding   %s %s...\n" (id tmpf) p)
                               (file/hard-link src dst)))
                         (recur this tmpfs))))]
        (util/with-let [_ this]
          (swap! state assoc-in [:prev dirs] this)
          (util/dbug* "Commit complete.\n")))))

  (rm [this tmpfiles]
    (let [{:keys [dirs tree blob]} this
          treefiles (set (vals tree))
          remove?   (->> tmpfiles set (set/difference treefiles) complement)]
      (assoc this :tree (reduce-kv #(if (remove? %3) %1 (assoc %1 %2 %3)) {} tree))))

  (add [this dest-dir src-dir opts]
    (assert ((set (map file dirs)) dest-dir)
            (format "dest-dir not in dir set (%s)" dest-dir))
    (let [{:keys [dirs tree blob scratch]} this
          {:keys [mergers include exclude]} opts
          ->tree   #(set-dir (dir->tree! % blob) dest-dir)
          new-tree (-> (->tree src-dir) (filter-tree include exclude))
          mrg-tree (when mergers
                     (->tree (merge-trees! tree new-tree mergers scratch)))]
      (assoc this :tree (merge tree new-tree mrg-tree))))

  (add-cached [this dest-dir cache-key cache-fn opts]
    (assert ((set (map file dirs)) dest-dir)
            (format "dest-dir not in dir set (%s)" dest-dir))
    (let [{:keys [dirs tree blob scratch]} this
          {:keys [mergers include exclude]} opts
          new-tree (let [cached (get-cached! cache-key cache-fn scratch)]
                     (-> (set-dir cached dest-dir)
                         (filter-tree include exclude)))
          mrg-tree (when mergers
                     (let [merged (merge-trees! tree new-tree mergers scratch)]
                       (set-dir (dir->tree! merged blob) dest-dir)))]
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

;; additional api functions ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn tmpfile?
  [x]
  (instance? TmpFile x))

(defn tmpfileset?
  [x]
  (instance? TmpFileSet x))

(defn diff
  [before after & props]
  (let [{:keys [added changed]}
        (diff* before after props)]
    (update-in added [:tree] merge (:tree changed))))

(defn fileset-patch
  [before after link]
  (let [{:keys [added removed changed]}
        (diff* before after [:hash :time])
        ->p     #(fs/path->segs (fs/->path %))
        link    (#{:tmp :all} link) ; only nil, :tmp, or :all are valid
        link?   #(and link (or (= :all link) (= (:blob after) (:bdir %))))]
    (-> (for [x (->> removed :tree vals)] [:delete (->p (path x))])
        (into (for [x (->> added :tree vals)]
                (let [p (.toPath ^File (file x))]
                  [(if (link? x) :link :write) (->p (path x)) p (time x)])))
        (into (for [x (->> changed :tree vals)]
                (let [p  (.toPath ^File (file x))
                      x' (get-in before [:tree (path x)])]
                  (cond (= (hash x) (hash x')) [:touch (->p (path x)) (time x)]
                        (link? x)              [:link  (->p (path x)) p (time x)]
                        :else                  [:write (->p (path x)) p (time x)])))))))

(defmethod fsp/patch TmpFileSet
  [before after link]
  (fileset-patch before after link))

(defn removed
  [before after & props]
  (:removed (diff* before after props)))

(defn added
  [before after & props]
  (:added (diff* before after props)))

(defn changed
  [before after & props]
  (:changed (diff* before after props)))

(defn restrict-dirs
  [fileset allowed-dirs]
  (let [dirs    (set allowed-dirs)
        reducer (fn [xs k {:keys [dir] :as v}]
                  (if-not (dirs dir) xs (assoc xs k v)))]
    (update-in fileset [:tree] (partial reduce-kv reducer {}))))
