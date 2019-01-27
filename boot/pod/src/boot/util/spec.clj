(ns boot.util.spec
  (:require
    [clojure.spec.alpha :as s]
    [boot.util :as util]))

(s/fdef util/dep-as-map
  :args (s/coll-of any?)
  :ret map?)
