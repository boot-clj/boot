(ns tailrecursion.boot.middleware.hoplon
  (:require
    [clojure.java.io                          :refer [file make-parents]]
    [tailrecursion.boot.deps                  :refer [deps]]
    [tailrecursion.boot.tmpregistry           :refer [mk mkdir exists? unmk]]
    [tailrecursion.hoplon.compiler.compiler   :refer [install-deps compile-dir]]))

(def dfl-opts     {:source-dir "src/html"})
(def update-vals  #(into {} (map (fn [[k v]] [k (apply %2 v %&)]) %1)))

(defn hoplon [handler]
  (fn [spec]
    (let [{:keys [source-dir  html-out  cljs-out
                  flib-out    lib-out   ext-out
                  inc-out] :as hspec}
          (merge-with 
            (comp (partial some identity) vector)
            (-> dfl-opts (merge (:hoplon spec)) (update-vals file)) 
            {:html-out  (mkdir ::html)
             :cljs-out  (mkdir ::cljs)
             :flib-out  (mkdir ::flib)
             :lib-out   (mkdir ::lib)
             :ext-out   (mkdir ::ext)
             :inc-out   (mkdir ::inc)})
          file?   #(.isFile %)
          name*   #(.getName %)
          path*   #(.getPath %)
          js-out  (doto (file html-out "main.js") make-parents)
          files   #(->> (file-seq %1) (filter file?))
          cat-str #(->> (map slurp %) (interpose "\n") (apply str))
          inc-str #(cat-str (sort (map path* (files inc-out))))
          cljsopt #(->>
                     {:source-paths  [(.getPath cljs-out)]
                      :externs       (mapv path* (files ext-out))
                      :libs          (mapv path* (files lib-out))}
                     (update-in spec [:cljsbuild] (partial merge-with into)))]
      (install-deps (deps) inc-out ext-out lib-out flib-out) 
      (compile-dir source-dir cljs-out html-out)
      (let [rspec (handler (assoc (cljsopt) :hoplon hspec))
            compiled-js (slurp (get-in rspec [:cljsbuild :output-to]))]
        (spit js-out (str (inc-str) "\n" compiled-js))
        rspec))))
