(ns tailrecursion.boot.middleware.cljsbuild
  (:require
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

(defn tmpfile
  [prefix postfix]
  (doto (java.io.File/createTempFile prefix postfix) .deleteOnExit))

(defn cljsbuild [handler]
  (fn [spec]
    (let [cspec (merge dfl-opts (:cljsbuild spec)) 
          srcs  (SourcePaths. (:source-paths cspec)) 
          outf  (tmpfile "cljsbuild_" "_main.js")
          opts  (-> cspec
                  (assoc :output-to (.getPath outf))
                  (dissoc :source-paths))]
      (cljs/build srcs opts)
      (handler (assoc-in spec [:cljsbuild :output] outf)))))
