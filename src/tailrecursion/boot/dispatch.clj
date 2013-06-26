(ns tailrecursion.boot.dispatch)

(defn maybe-resolve [x]
  (or (and (symbol? x)
           (or (binding [*ns* 'user] (resolve x))
               (throw (RuntimeException. (str "unable to resolve symbol: " x)))))
      x))

(def maybe-deref #(if (var? %) @% %))

(def parse-args (partial map (comp maybe-deref maybe-resolve read-string)))

(defmulti dispatch (fn [env args] (first args)))

(defmethod dispatch "pom" [{:keys [boot pom] :as env} [_ & args]]
  (println pom))

(defmethod dispatch "jar" [env [_ & args]]
  (println "JAR!"))

(defmethod dispatch :default [env args]
  (let [[f & args] (parse-args args)]
    (apply f args)))

(defn try-dispatch [env]
  (dispatch env *command-line-args*))
