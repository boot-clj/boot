(ns boot.web
  (:require
   [clojure.java.io  :as io]
   [clojure.data.xml :as xml]
   [boot.pod         :as pod]
   [boot.util        :as util]))

(defn splice [xml-expr]
  (let [splice? #(and (seq? %) (not (vector? %)))]
    (if (vector? xml-expr)
      (let [[tag attr & kids] xml-expr]
        (into [tag attr] (mapcat (comp #(if (splice? %) % [%]) splice) kids)))
      xml-expr)))

(defn params* [ctor & kvs]
  (for [[k v] (partition 2 kvs) :when v]
    [ctor {} [:param-name {} k] [:param-value {} (str v)]]))

(defn web-xml [name desc serve create destroy context-create context-destroy]
  (-> [:web-app {:xmlns              "http://java.sun.com/xml/ns/javaee"
                 :xmlns:xsi          "http://www.w3.org/2001/XMLSchema-instance"
                 :xsi:schemaLocation "http://java.sun.com/xml/ns/javaee http://java.sun.com/xml/ns/javaee/web-app_3_0.xsd"
                 :version            "3.0"
                 :metadata-complete  "true"}
       [:display-name {} name]
       [:description {} desc]
       [:servlet {}
        [:servlet-name {} name]
        [:servlet-class {} "tailrecursion.ClojureAdapterServlet"]
        (params* :init-param
                 "create" create
                 "serve" serve
                 "destroy" destroy)]
       (if (or context-create context-destroy)
         [:listener {}
          [:listener-class {} "tailrecursion.ClojureAdapterServletContextListener"]])
       (params* :context-param
                "context-create" context-create
                "context-destroy" context-destroy)
       [:servlet-mapping {}
        [:servlet-name {} name]
        [:url-pattern {} [:url-pattern {} "/*"]]]]
      splice
      xml/sexp-as-element
      xml/indent-str))

(defn spit-web! [webxmlfile serve create destroy context-create context-destroy]
  (let [xmlfile  (io/file webxmlfile)]
    (spit
     (doto xmlfile io/make-parents)
     (web-xml "boot-webapp" "boot-webapp" serve create destroy context-create context-destroy))))
