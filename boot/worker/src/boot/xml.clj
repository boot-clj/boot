(ns boot.xml
  "DEPRECATED: Utility functions for working with XML."
  {:deprecated "2.0.0"}
  (:require
   [clojure.java.io              :as io]
   [clojure.xml                  :as xml]
   [clojure.data.xml             :as dxml]
   [alandipert.desiderata.invoke :as invoke])
  (:import
   [java.io ByteArrayInputStream]))

(def invalid-xml-chars
  "[^\u0009\r\n\u0020-\ud7ff\ue000-\ufffd\ud800\udc00-\udbfF\udfff]")

(defn clean-xml [x]
  (when (string? x) (.replaceAll x invalid-xml-chars "")))

(defn ppxml [xml]
  (let [in (javax.xml.transform.stream.StreamSource.
             (java.io.StringReader. xml))
        writer (java.io.StringWriter.)
        out (javax.xml.transform.stream.StreamResult. writer)
        transformer (.newTransformer
                      (javax.xml.transform.TransformerFactory/newInstance))]
    (.setOutputProperty transformer
      javax.xml.transform.OutputKeys/INDENT "yes")
    (.setOutputProperty transformer
      "{http://xml.apache.org/xslt}indent-amount" "2")
    (.setOutputProperty transformer
      javax.xml.transform.OutputKeys/METHOD "xml")
    (.transform transformer in out)
    (-> out .getWriter .toString)))

(defn- unsplice [kids]
  (let [spliced? #(or (vector? %) (seq? %))]
    (->> kids
      (mapcat #(cond (spliced? %) (unsplice %) (nil? %) [] :else [%])))))

;;;;

(defprotocol IHiccup
  (-hiccup [this]))

(extend-protocol IHiccup
  java.lang.String
  (-hiccup [this] this)
  java.lang.Long
  (-hiccup [this] (str this))
  java.lang.Double
  (-hiccup [this] (str this))
  java.lang.Integer
  (-hiccup [this] (str this))
  java.lang.Boolean
  (-hiccup [this] (str this))
  clojure.lang.Keyword
  (-hiccup [this] (name this))
  clojure.lang.Symbol
  (-hiccup [this] (name this)))

(defn hiccup [this]
  (-hiccup this))

;;;;

(defn parse-elem-args [[head & tail :as args]]
  (let [kw1? (comp keyword? first)
        mkkw #(->> (partition 2 %) (take-while kw1?) (map vec))
        drkw #(->> (partition 2 2 [] %) (drop-while kw1?) (mapcat identity))]
    (cond
      (map?     head) [head                  (unsplice tail)]
      (keyword? head) [(into {} (mkkw args)) (unsplice (drkw args))]
      :else           [{}                    (unsplice (concat () args))])))

(defmacro elem [bindings & body]
  `(fn [& args#]
     (let [~bindings (parse-elem-args args#)]
       ~@body)))

(defmacro defelem [sym & args]
  `(def ~sym (elem ~@args)))

(defmacro decelems [& symbols]
  `(doseq [sym# '~symbols]
     (intern (ns-name *ns*) sym# (elem [attrs# elems#] ((element (keyword sym#)) attrs# elems#)))))

(invoke/deftypefn XMLElement
  [tag attrs elems]
  (fn [this & args]
    (let [[attrs' elems'] (parse-elem-args args)
          attrs''         (merge attrs attrs')
          elems''         (concat elems (unsplice elems'))
          hollow?        #(or (and (coll? %) (empty? %)) (nil? %)) ]
      (XMLElement. tag attrs'' elems'')))
  IHiccup
  (-hiccup [this]
    (apply vector tag
      (into {} (map (fn [[k v]] [k (-hiccup v)]) attrs))
      (map #(-hiccup %) elems))))

(defmethod print-method XMLElement
  [this ^java.io.Writer w]
  (.write w (-> this
              hiccup
              dxml/sexp-as-element
              dxml/indent-str
              (.replaceFirst ">" ">\n"))))

(defn element [tag]
  (fn [& [attrs elems :as args]]
    (-> (XMLElement. tag {} ()) (apply args))))
