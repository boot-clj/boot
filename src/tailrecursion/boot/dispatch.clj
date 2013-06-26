(ns tailrecursion.boot.dispatch)

(defn maybe-resolve [x]
  (if (symbol? x)
    (if-let [var (ns-resolve 'user x)]
      (deref var)
      (throw (IllegalArgumentException.
              (format "'%s' is not a public var in the user namespace." x))))))

(defmulti dispatch first)

(defmethod dispatch "pom" [args]
  (println "POM!"))

(defmethod dispatch "jar" [args]
  (println "JAR!"))

(defmethod dispatch :default [args]
  (let [[f & args] (map (comp maybe-resolve read-string) args)]
    (if (fn? f) (apply f args))))

(defn try-dispatch []
  (dispatch *command-line-args*))
