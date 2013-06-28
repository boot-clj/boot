(ns tailrecursion.boot.middleware.hoplon
  (:require
    [tailrecursion.boot.deps                :refer [deps]]
    [tailrecursion.boot.tmpregistry         :refer [mk mkdir exists? unmk]]
    [tailrecursion.hoplon.compiler.compiler :refer [install-deps compile-dir]]
    )
  )

(def dfl-opts
  {:source-dir  "src/html"
   :html-out    nil
   :cljs-out    nil
   :flib-out    nil
   :lib-out     nil
   :ext-out     nil
   :inc-out     nil})

(defn hoplon [handler]
  (fn [spec]
    (let [hspec (merge-with 
                  (comp (partial some identity) vector)
                  (merge dfl-opts (:hoplon spec)) 
                  {:html-out  (mkdir ::html)
                   :cljs-out  (mkdir ::cljs)
                   :flib-out  (mkdir ::flib)
                   :lib-out   (mkdir ::lib)
                   :ext-out   (mkdir ::ext)
                   :inc-out   (mkdir ::inc)})]
      (apply install-deps (deps) ((juxt :inc-out :ext-out :lib-out :flib-out) hspec))
      (apply compile-dir ((juxt :source-dir :cljs-out :html-out) hspec))
      (let [ret (handler (assoc spec :hoplon hspec))
            
            ]
        
        )
      
      )))
