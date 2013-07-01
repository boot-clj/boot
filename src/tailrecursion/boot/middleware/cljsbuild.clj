(ns tailrecursion.boot.middleware.cljsbuild
  (:require
    [cljs.closure :as cljs]
    [clojure.string                 :refer [split join]]
    [clojure.java.io                :refer [file make-parents]]
    [tailrecursion.boot.deps        :refer [deps]]  
    [tailrecursion.boot.tmpregistry :refer [mk mkdir exists? unmk]]))

(defrecord SourcePaths [paths]
  cljs/Compilable
  (-compile [this opts]
    (mapcat #(cljs/-compile % opts) paths)))

(def dfl-opts
  {:source-paths  ["src-cljs"]
   :output-to     nil
   :output-dir    nil
   :optimizations :whitespace
   :warnings      true
   :externs       []
   :libs          []
   :foreign-libs  []
   :pretty-print  true})

(let [last-counter (atom 0)]
  (def counter #(swap! last-counter inc)))

(defn install-deps [jars incs exts libs flibs]
  (let [name*   #(.getName (file %))
        match   #(last (re-find #"[^.]+\.([^.]+)\.js$" %))
        dirmap  {"inc" incs "ext" exts "lib" libs "flib" flibs}
        outfile #(file %1 (str (format "%010d" (counter)) "_" (name* %2)))
        write   #(if-let [d (dirmap (match %1))]
                   (spit (doto (outfile d %1) make-parents) (slurp %2)))]
    (doall (->> jars (map second) (mapcat identity) reverse (map (partial apply write))))))

(defn update-by-keys [m ks f] (reduce #(assoc %1 %2 (f (%1 %2))) m ks))

(defn cljsbuild [handler]
  (fn [spec]
    (let [{:keys [output-to flib-out lib-out ext-out inc-out] :as cspec}
          (merge-with
            (comp (partial some identity) vector)
            (-> dfl-opts
              (merge (:cljsbuild spec))
              (update-by-keys
                [:output-to :output-dir :flib-out :lib-out :ext-out :inc-out]
                file))
            {:output-to   (mk ::js "main.js")
             :output-dir  (mkdir ::out)
             :flib-out    (mkdir ::flib)
             :lib-out     (mkdir ::lib)
             :ext-out     (mkdir ::ext)
             :inc-out     (mkdir ::inc)})
          file? #(.isFile %)
          path* #(.getPath %)
          files #(filter file? (file-seq %))
          paths #(mapv path* (files %))
          cat   #(join "\n" (mapv slurp %)) 
          srcs  (SourcePaths. (:source-paths cspec)) 
          _     (install-deps (deps) inc-out ext-out lib-out flib-out) 
          exts  (paths ext-out)
          incs  (cat (sort (files inc-out)))
          opts  (-> cspec
                  (select-keys [:output-to    :output-dir   :optimizations
                                :warnings     :externs      :libs
                                :foreign-libs :pretty-print])
                  (update-in [:output-to] #(.getPath %))
                  (update-in [:externs] into exts))]
      (cljs/build srcs opts)
      (spit output-to (str incs "\n" (slurp output-to)))
      (handler (assoc spec :cljsbuild cspec)))))
