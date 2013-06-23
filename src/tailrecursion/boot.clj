(ns tailrecursion.boot
  (:require [cemerick.pomegranate :as pom]
            [cemerick.pomegranate.aether :refer [maven-central]])
  (:import java.io.File))

(def installed-artifacts (atom #{}))

(defprotocol Artifact
  (install! [this]))

(def default-maven-repos
  (into (set (vals maven-central))
        ["http://clojars.org/repo"]))

(defn repo-map
  [repo-set]
  (apply hash-map (mapcat (partial repeat 2) repo-set)))

(defrecord MavenArtifact [coord repo-url]
  Artifact
  (install! [this]
    (pom/add-dependencies
     :coordinates [coord]
     :repositories (merge (repo-map default-maven-repos)
                          (when repo-url {repo-url repo-url})))
    (swap! installed-artifacts conj this)))

(defn install [coord & [repo-url]]
  (-> (MavenArtifact. coord repo-url) install!))

(def ^:dynamic *args*)

(defn -main [& args]
  (binding [*args* args]
    (load-file "boot.clj")))

