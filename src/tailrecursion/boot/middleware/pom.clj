(ns tailrecursion.boot.middleware.pom
  (:require
   [tailrecursion.boot.tmpregistry :refer [mk mkdir exists? unmk]]
   [clojure.java.io :as io])
  (:import
   [org.apache.maven.model Model Repository Dependency Exclusion]
   org.apache.maven.model.io.xpp3.MavenXpp3Writer))

(defn contains-every? [coll ks]
  (every? identity (map contains? (repeat coll) ks)))

(defmacro dotoseq [obj seq-exprs & body]
  `(let [o# ~obj]
     (doseq ~seq-exprs
       (doto o# ~@body))
     o#))

(defn ^Model set-repositories! [^Model model repositories]
  (dotoseq model [repo repositories]
    (.addRepository (doto (Repository.) (.setUrl repo)))))

(defn extract-ids [sym]
  (let [[group artifact] ((juxt namespace name) sym)]
    [(or group artifact) artifact]))

(defn ^Model set-dependencies! [^Model model dependencies]
  (dotoseq model
    [[project version & {:keys [exclusions]}] dependencies
     :let [[group artifact] (extract-ids project)]]
    (.addDependency (doto (Dependency.)
                      (.setGroupId group)
                      (.setArtifactId artifact)
                      (.setVersion version)
                      (.setExclusions
                       (for [e exclusions :let [[group artifact] (extract-ids e)]]
                         (doto (Exclusion.) (.setGroupId group) (.setArtifactId artifact))))))))

(defn ^Model build-model [boot pom]
  {:pre [(contains-every? pom [:project :version])]}
  (let [{:keys [repositories dependencies directories]} boot
        {:keys [project version description]} pom
        [group artifact] (extract-ids project)]
    (doto (Model.)
      (.setGroupId group)
      (.setArtifactId artifact)
      (.setVersion version)
      (set-repositories! repositories)
      (set-dependencies! dependencies))))

(defn wrap-pom [handler & pom-opts]
  (fn [request]
    (let [{:keys [boot pom]} (update-in request [:pom] merge (apply hash-map pom-opts))]
      (handler (if pom
                 (let [model (build-model boot pom)]
                   (update-in request [:pom] merge
                              {:model model
                               :pom.xml (with-out-str (.write (MavenXpp3Writer.) *out* model))}))
                 request)))))

(comment
  (def req
    {:boot {:repositories #{"http://foo.com/repo/"}
            :dependencies '{[alandipert/interpol8 "0.0.3" :exclusions [[org.clojure/clojure]]] nil}}
     :pom {:project 'foo/bar
           :version "1.0.0"}})

  (println (get-in ((pom identity) req) [:pom :pom.xml]))
  )
