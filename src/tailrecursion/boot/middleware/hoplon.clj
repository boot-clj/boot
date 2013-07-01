(ns tailrecursion.boot.middleware.hoplon
  (:require
    [clojure.java.io                          :refer [file make-parents]]
    [tailrecursion.boot.tmpregistry           :refer [mk mkdir exists? unmk]]
    [tailrecursion.hoplon.compiler.compiler   :refer [compile-dir]]))

(def dfl-opts 
  {:source-dir  "src/html"
   :html-out    nil
   :cljs-out    nil})

(defn update-by-keys [m ks f] (reduce #(assoc %1 %2 (f (%1 %2))) m ks))

(defn hoplon [handler]
  (fn [spec]
    {:pre [(get-in spec [:hoplon :compiled-js])]}
    (let [{:keys [source-dir html-out cljs-out compiled-js] :as hspec}
          (merge-with 
            (comp (partial some identity) vector)
            (-> dfl-opts
              (merge (:hoplon spec))
              (update-by-keys [:source-dir :html-out :cljs-out] file)) 
            {:html-out (mkdir ::html)
             :cljs-out (mkdir ::cljs)})]
      (compile-dir compiled-js source-dir cljs-out html-out)
      (handler (assoc spec :hoplon hspec)))))
