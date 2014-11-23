(ns boot.tmpdir
  (:require
    [clojure.java.io  :as io]
    [clojure.set      :as set]
    [boot.util        :as util]
    [boot.file        :as file]
    [boot.from.digest :as digest]))

(defprotocol ITmpFile
  (id   [this])
  (dir  [this])
  (path [this])
  (file [this]))

(defprotocol ITmpFileSet
  (ls      [this])
  (commit! [this])
  (rm!     [this paths])
  (add!    [this dest-dir src-dir])
  (cp!     [this src-file dest-tmpfile]))

(defrecord TmpFile [dir path id]
  ITmpFile
  (id   [this] id)
  (dir  [this] dir)
  (path [this] path)
  (file [this] (io/file dir path)))

(defrecord TmpDir [dir user input output]
  ITmpFile
  (id   [this] nil)
  (dir  [this] dir)
  (path [this] "")
  (file [this] dir))

(defn- dir->tree
  [dir]
  (let [file->rel-path #(file/relative-to dir %)
        file->kv       #(let [p (str (file->rel-path %))]
                          [p (TmpFile. dir p (digest/md5 %))])]
    (->> dir file-seq (filter (memfn isFile)) (map file->kv) (into {}))))

(defn- add-blob!
  [blob src hash]
  (let [out    (io/file blob hash)
        mod    #(.lastModified %)
        write! #(.setWritable %1 %2)
        mod!   #(.setLastModified %1 %2)]
    (if (.exists out)
      (when (< (mod out) (mod src))
        (doto out (write! true) (mod! (mod src)) (write! false)))
      (doto out (#(io/copy src %)) (mod! (mod src)) (write! false)))))

(defrecord TmpFileSet [dirs tree blob]
  ITmpFileSet
  (ls [this]
    (set (vals tree)))
  (commit! [this]
    (util/with-let [{:keys [dirs tree blob]} this]
      (apply file/empty-dir! (map file dirs))
      (doseq [[p tmpf] tree]
        (let [srcf (io/file blob (id tmpf))]
          (file/copy-with-lastmod srcf (file tmpf))))))
  (rm! [this tmpfiles]
    (let [{:keys [dirs tree blob]} this
          treefiles (set (vals tree))
          remove?   (->> tmpfiles set (set/difference treefiles) complement)]
      (assoc this :tree (reduce-kv #(if (remove? %3) %1 (assoc %1 %2 %3)) {} tree))))
  (add! [this dest-dir src-dir]
    (assert ((set (map file dirs)) dest-dir)
            (format "dest-dir not in dir set (%s)" dest-dir))
    (let [{:keys [dirs tree blob]} this
          src-tree (-> #(assoc %1 %2 (assoc %3 :dir dest-dir))
                       (reduce-kv {} (dir->tree src-dir)))]
      (doseq [[path tmpf] src-tree]
        (add-blob! blob (io/file src-dir path) (id tmpf)))
      (assoc this :tree (merge tree src-tree))))
  (cp! [this src-file dest-tmpfile]
    (let [hash (digest/md5 src-file)
          p'   (path dest-tmpfile)]
      (assert ((ls this) dest-tmpfile)
              (format "dest-tmpfile not in fileset (%s)" dest-tmpfile))
      (add-blob! blob src-file hash)
      (assoc this :tree (merge tree {p' (assoc dest-tmpfile :id hash)})))))

(comment

  (def ^:private masks
    {:user     {:user true}
     :input    {:input true}
     :output   {:output true}
     :cache    {:input nil :output nil}
     :asset    {:input nil :output true}
     :source   {:input true :output nil}
     :resource {:input true :output true}})

  (defn- get-dirs [this masks+]
    (let [dirs        (:dirs this)
          has-mask?   #(= %1 (select-keys %2 (keys %1)))
          filter-keys #(->> %1 (filter (partial has-mask? %2)))]
      (->> masks+ (map masks) (apply merge) (filter-keys dirs) (map file) set)))

  (defn- get-add-dir [this masks+]
    (let [user?  (contains? masks+ :user)
          u-dirs (when-not user? (get-dirs this #{:user}))]
      (-> this (get-dirs masks+) (set/difference u-dirs) first)))

  (defn- get-files [this masks+]
    (let [dirs (get-dirs this masks+)]
      (->> this ls (filter (comp dirs dir)) set)))

  (defn user-dirs     [this]     (get-dirs this #{:user}))
  (defn input-dirs    [this]     (get-dirs this #{:input}))
  (defn output-dirs   [this]     (get-dirs this #{:output}))
  (defn user-files    [this]     (get-files this #{:user}))
  (defn input-files   [this]     (get-files this #{:input}))
  (defn output-files  [this]     (get-files this #{:output}))
  (defn add-asset!    [this dir] (add! this (get-add-dir this #{:asset}) dir))
  (defn add-source!   [this dir] (add! this (get-add-dir this #{:source}) dir))
  (defn add-resource! [this dir] (add! this (get-add-dir this #{:resource}) dir))

  (defn tmp-dir
    [dir & masks+]
    (-> (->> masks+ (map masks) (apply merge)) (assoc :dir dir) map->TmpDir))

  (def t1 (tmp-dir (io/file "foop1") :source))
  (def t2 (tmp-dir (io/file "foop2") :resource))
  (def tf (TmpFileSet. #{t1 t2} {} (io/file "foop0")))
  (input-dirs tf)
  (output-dirs tf)
  (def tf (add-source! tf (io/file "foop3")))
  (def tf (commit! tf))
  (def tf (add-resource! tf (io/file "foop3")))
  (def tf (commit! tf))
  (def tf (add-source! tf (io/file "foop4")))
  (def tf (commit! tf))
  (file-seq (io/file "foop3/"))
  (identity tf)
  (ls tf)
  (def tf (rm! tf #{(first (ls tf))}))
  (def tf (commit! tf))
  (map path (ls tf))
  (map file (ls tf))
  (map dir (ls tf))
  (user-files tf)
  (->> tf input-files (map path))
  (input-files tf)
  (output-files tf)

  )

