(ns boot.deps
  (:refer-clojure :exclude [load])
  (:require
    [boot.core :as boot]
    [boot.pod :as pod]))

(defn load
  "Loads dependency specifications from a resource inside the given
   dependency or on the given path.  Keyword options can be provided
   after the dep-or-path.  The supported options are:
     :resource The resource name to use to read in the dependencies.
               If the resource is not provided, then the default
               \"dependencies.edn\" will be used.
     :xfn      A function used to transform the loaded dependencies.
               Takes a list of dependencies and returns a list of
               transformed dependencies.  Defaults to the identity
               function."
  [dep-or-path & {:keys [resource xfn]
                  :or   {xfn identity}}]
  (let [path (when (string? dep-or-path) dep-or-path)
        dep (if (vector? dep-or-path) [dep-or-path] [])]
    (-> (boot/get-env)
        (assoc :dependencies dep)
        pod/make-pod
        (pod/with-call-in (boot.deps-util/read-deps ~path ~resource))
        xfn)))
