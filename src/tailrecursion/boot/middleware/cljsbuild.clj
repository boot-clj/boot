(ns tailrecursion.boot.middleware.cljsbuild
  (:require
    [tailrecursion.boot.tmpregistry :refer [mk mkdir exists? unmk]]
    [cljs.closure :as cljs]))

(defrecord SourcePaths [paths]
  cljs/Compilable
  (-compile [this opts]
    (mapcat #(cljs/-compile % opts) paths)))

(def dfl-opts
  {:source-paths  ["src-cljs"]
   :optimizations :whitespace
   :warnings      true
   :externs       []
   :libs          []
   :foreign-libs  []
   :pretty-print  true})

(defn cljsbuild [handler]
  (fn [spec]
    (let [cspec (merge dfl-opts (:cljsbuild spec)) 
          outf  (or (:output-to cspec) (mk ::js "main.js"))
          outd  (or (:output-dir cspec) (mkdir ::out))
          srcs  (SourcePaths. (:source-paths cspec)) 
          opts  (-> cspec
                  (assoc :output-to (.getPath outf))
                  (dissoc :source-paths))]
      (cljs/build srcs opts)
      (handler (assoc-in spec [:cljsbuild :output] outf)))))
