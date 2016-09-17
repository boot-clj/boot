(ns boot.deps-util
  (:require
    [clojure.java.io :as io]
    [clojure.edn :as edn]
    [boot.pod :as pod]
    [boot.util :as util]))

(defn- maybe-strip-scope
  "Strip the generated scope field unless it was explicitly defined."
  [dep dep-map]
  (if (some #{:scope} dep)
    dep-map
    (dissoc dep-map :scope)))

(defn- dep-key
  [{:keys [project extension classifier]
    :or   {extension "jar"}}]
  (if classifier
    [project extension classifier]
    [project extension]))

(defn- managed-deps-map
  "Accepts a list of dependency specifications and returns a
   map where the values are the map representation of the dependency
   and the key is the value of :project in the dependency."
  [managed-deps]
  (->> managed-deps
       (map util/dep-as-map)
       (remove nil?)
       (map (juxt dep-key identity))
       (into {})))

(defn- complete-dep
  "Completes the information in the given dependency with the information
   from the managed dependency map."
  [managed-deps-map dep]
  (let [dep-map (util/dep-as-map dep)
        k (dep-key dep-map)]
    (if-let [managed-dep-map (get managed-deps-map k)]
      (->> dep-map
           (maybe-strip-scope dep)
           (filter second)
           (into {})
           (merge managed-dep-map)
           util/map-as-dep)
      dep)))

(defn- complete-deps
  "Iterates over the list of dependencies, completing values
   from the defaults for each dependency."
  [managed-deps-map deps]
  (vec (map (partial complete-dep managed-deps-map) deps)))

(defn merge-deps [deps managed-deps]
  (complete-deps (managed-deps-map managed-deps) deps))

(defn read-deps
  "Reads dependencies from the resource with the given resource-name. If
   dir is not nil, that directory will be added to the classpath. It should
   be a file or string. If the resource name is not provided it defaults
   to \"dependencies.edn\". If the resource is not found on the classpath,
   then nil is returned. Exceptions will be thrown if there are reading or
   parsing errors.  Note: this function modifies the classpath if \"dir\"
   is provided."
  [dir resource-name]
  (when dir (pod/add-classpath dir))
  (some-> (or resource-name "dependencies.edn")
          io/resource
          slurp
          edn/read-string))

