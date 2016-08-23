(ns boot.xform
  (:require
    [boot.util :as util])
  (:import
    [java.lang.reflect Modifier]))

(def ^:dynamic *for-eval* nil)
(def ^:dynamic *from-pod* nil)
(def ^:dynamic *to-pod*   nil)

(declare ->clj*)

(defn- log-unhandled-type
  "Use to debug strange new types that aren't transformed correctly."
  [x]
  (util/dbug* "Xform: unhandled type: %s\n" (.. x getClass getName)))

(defn- static-field?
  "Is this Field static?"
  [x]
  (pos? (bit-and Modifier/STATIC (.getModifiers x))))

(defn- reflect-record?
  "Is this object a Clojure record from another pod?"
  [x]
  (->> x .getClass .getInterfaces (some #(= "clojure.lang.IRecord" (.getName %)))))

(defn- reflect-fn?
  "Is this object a Clojure function from another pod?"
  [x]
  (#{"clojure.lang.AFunction" "clojure.lang.RestFn"} (.. x getClass getSuperclass getName)))

(defn- reflect-record-args
  "Extract the constructor args for this Clojure record from another pod and
  translate them into data compatible with this pod."
  [x]
  (->> x .getClass .getDeclaredFields
       (reduce #(if (static-field? %2) %1 (conj %1 (->clj* (.get %2 x)))) [])))

(defn- reflect-impl-class
  "Returns Class x or the first superclass of x that implements the interface
  named iface-name, or nil if the interface is not implemented by x or any of
  its superclasses."
  [x iface-name]
  (loop [c (.getClass x)]
    (when c
      (let [ifs (map #(.getName %) (.getInterfaces c))]
        (if ((set ifs) iface-name) c (recur (.getSuperclass c)))))))

(defn- meta->clj
  "Extract the metadata on x and translate it to be compatible with this pod."
  [x]
  (->clj* (-> (reflect-impl-class x "clojure.lang.IObj")
              (.getMethod "meta" (into-array Class []))
              (.invoke x (into-array Object [])))))

(defn- record->clj
  "Create a new record in this pod that is equivalent to x from another pod."
  [x]
  (eval (list* (-> x .getClass .getName (str ".") symbol) (reflect-record-args x))))

(defn- <-clj
  "Perform a reverse translation, translating data compatible with this pod to
  data compatible with another pod. Must be called in a future if there is any
  chance that *from-pod* is the pod in which this code is run."
  [x]
  (-> *from-pod* .get (.invoke "boot.xform/->clj" *to-pod* *from-pod* x)))

(defn- fn->clj
  "Wrap the function x from another pod in a function from this pod that will
  do all necessary translation of arguments and use reflection to call the
  original function implementation. The result is translated to be compatible
  with this pod."
  [x]
  (bound-fn [& args]
    (let [param (into-array Class (repeat (count args) Object))
          args  (into-array Object @(future (<-clj args)))]
      (-> x .getClass (.getMethod "invoke" param) (.invoke x args) ->clj*))))

(defn- ->clj*
  "Translate x to data compatible with this pod. Assumes the following dynamic
  vars are correctly bound: *from-pod*, *to-pod*, and *for-eval*."
  [x]
  (when-not (nil? x)
    (case (.. x getClass getName)
      ;; EDN types (can be passed to eval)
      "clojure.lang.MapEntry"
      (into [] (map ->clj* x))
      "clojure.lang.PersistentVector"
      (with-meta (into [] (map ->clj* x)) (meta->clj x))
      ("clojure.lang.PersistentHashMap"
        "clojure.lang.PersistentArrayMap")
      (with-meta (into {} (map ->clj* x)) (meta->clj x))
      ("clojure.lang.Cons"
        "clojure.lang.LazySeq"
        "clojure.lang.ArraySeq"
        "clojure.lang.ChunkedCons"
        "clojure.lang.PersistentList"
        "clojure.lang.APersistentMap$KeySeq")
      (with-meta (doall (map ->clj* x)) (meta->clj x))
      "clojure.lang.PersistentList$EmptyList"
      (with-meta () (meta->clj x))
      "clojure.lang.PersistentHashSet"
      (with-meta (into #{} (map ->clj* x)) (meta->clj x))
      "clojure.lang.Keyword"
      (-> x str (subs 1) keyword)
      "clojure.lang.Symbol"
      (with-meta (-> x str symbol) (meta->clj x))
      "clojure.lang.Ratio"
      (let [c (.getClass x)
            n (.. c (getDeclaredField "numerator") (get x))
            d (.. c (getDeclaredField "denominator") (get x))]
        (clojure.lang.Ratio. n d))
      "clojure.lang.BigInt"
      (let [c (.getClass x)
            l (.. c (getDeclaredField "lpart") (get x))
            b (.. c (getDeclaredField "bipart") (get x))
            C (-> c .getDeclaredConstructors (aget 0) (doto (.setAccessible true)))]
        (.newInstance C (into-array Object [l b])))
      ("java.lang.Boolean"
        "java.lang.String"
        "java.lang.Long"
        "java.lang.Double"
        "java.lang.Integer"
        "java.lang.Character"
        "java.util.regex.Pattern")
      x
      ;; Non-EDN types (cannot be passed to eval without stashing)
      (let [x (cond (reflect-fn? x)     (with-meta (fn->clj x) (meta->clj x))
                    (reflect-record? x) (record->clj x)
                    :else               (doto x (log-unhandled-type)))]
        ;; Stash if result will be sent to eval
        (if-not *for-eval* x `(boot.App/getStash ~(boot.App/setStash x)))))))

(defn ->clj
  [from-pod to-pod x & {:keys [for-eval]}]
  (binding [*from-pod* from-pod
            *to-pod*   to-pod
            *for-eval* for-eval]
    (->clj* x)))

