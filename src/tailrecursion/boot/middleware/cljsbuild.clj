(ns tailrecursion.boot.middleware.cljsbuild
  (:require
    [cljs.closure :as cljs]
    [clojure.java.io                :refer [file]]
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

(defn cljsbuild [handler]
  (fn [spec]
    (let [cspec (merge-with
                  (comp (partial some identity) vector)
                  (-> dfl-opts
                    (merge (:cljsbuild spec))
                    (update-in [:output-to] file)
                    (update-in [:output-dir] file)) 
                  {:output-to   (mk ::js "main.js")
                   :output-dir  (mkdir ::out)})
          srcs  (SourcePaths. (:source-paths cspec)) 
          opts  (-> cspec
                  (update-in [:output-to] #(.getPath %))
                  (dissoc :source-paths))]
      (cljs/build srcs opts)
      (handler (assoc spec :cljsbuild cspec)))))
