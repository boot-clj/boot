(ns tailrecursion.boot.dispatch)

(defmulti dispatch first)

(defmethod dispatch "pom" [args]
  (println "POM!"))

(defmethod dispatch "jar" [args]
  (println "JAR!"))

(defmethod dispatch :default [args]
  (let [[sym & args] (map read-string args)]
    (if-let [var (get (ns-publics 'user) sym)]
      (if (fn? @var) (apply var args)))))

(defn try-dispatch []
  (dispatch *command-line-args*))
