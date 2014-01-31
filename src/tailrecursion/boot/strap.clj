(ns tailrecursion.boot.strap
  (:require
   [clojure.walk    :as w]
   [clojure.string  :as s]
   [clojure.pprint  :as p]
   [clojure.java.io :as io]))

(def ^:dynamic ancient-loaded? (atom false))

(defn- latest-version [add-deps! dep]
  (when-not @ancient-loaded?
    (add-deps! '[[tailrecursion/ancient-clj "0.1.7"]])
    (swap! ancient-loaded? not))
  (require 'ancient-clj.core)
  (let [latest-version-string! (resolve 'ancient-clj.core/latest-version-string!)]
    (latest-version-string! {:snapshots? false} dep)))

(defn strap [add-deps!]
  (printf
    "#!/usr/bin/env boot\n\n#tailrecursion.boot.core/version %s\n"
    (pr-str (latest-version add-deps! 'tailrecursion/boot.core))))

(defn quote-sym   [x]   (if-not (symbol? x) x `'~x))
(defn quote-syms  [x]   (w/postwalk quote-sym x))
(defn tr-dep?     [x]   (= "tailrecursion" (namespace x)))
(defn version-for [k v] [k (if-not (tr-dep? k) v (latest-version nil k))])
(defn pp          [x]   (with-out-str (p/write x :dispatch p/code-dispatch)))

(defn fixup-dep [edn]
  (when-let [deps (:dependencies edn)]
    [:dependencies
     (mapv (fn [[k v & more]] (into (version-for k v) more)) deps)]))

(defn fixup-env [edn]
  (let [add #(if-not (contains? edn %2) %1 (into %1 [%2 (get edn %2)]))
        ks   [:project :version :description :url :license :repositories]
        env1 (quote-syms (reduce add [] [:project :version :description :url :license :repositories]))
        env2 (quote-syms (reduce add [] [:lein :src-paths]))
        deps (quote-syms (fixup-dep edn))
        out  (when-let [public (:public edn)] [:out-path public])]
    (when (some seq [env1 env2 deps out])
      `(~'set-env! ~@env1 ~@deps ~@env2 ~@out))))

(defn fixup-syn [edn]
  (when-let [static (:src-static edn)]
    `(~'add-sync! (~'get-env :out-path) ~static)))

(defn fixup-req [edn]
  (when-let [reqs (get edn :require-tasks)]
    `(~'require ~@(quote-syms reqs))))

(defn fixup [add-deps!]
  (assert (.exists (io/file "boot.edn")))
  (let [edn (read-string (slurp "boot.edn"))
        pre (s/trim (with-out-str (strap add-deps!)))
        env (when-let [x (fixup-env edn)] (pp x))
        req (when-let [x (fixup-req edn)] (pp x))
        syn (when-let [x (fixup-syn edn)] (pp x))]
    (println (s/join "\n\n" (keep identity [pre env syn req]))))) 
