(ns boot.pedantic
  (:require
    [boot.pod :as pod]
    [clojure.set :as set]
    [clojure.string :as string]
    [boot.from.io.aviso.ansi :as ansi]))

(defn safe-read-string [s]
  (try (read-string s) (catch Throwable _ s)))

(defn sortable-version [ver]
  (map safe-read-string (string/split ver #"[.-]")))

(defn compare-version [a b]
  (let [[a b] (map sortable-version [a b])
        [p q] (map count [a b])
        [a b] (cond (= p q) [a b]
                    (< p q) [(concat a (repeat nil)) b]
                    (> p q) [a (concat b (repeat nil))])]
    (->> (map compare a b)
         (remove zero?)
         first
         ((fnil identity 0)))))

(defn dep-conflicts [env]
  (->> (for [[id & _ :as dep] (:dependencies env)]
         (->> (assoc env :dependencies [dep])
              pod/resolve-dependencies
              (map (comp (fn [[i v & _]] {:id i :ver v :via id}) :dep))))
       flatten
       (group-by :id)
       (map (fn [[k v]]
              [k (->> (map (fn [[k' v']] [k' (map :via v')])
                           (group-by :ver v))
                      (into (sorted-map-by compare-version)))]))
       (filter #(< 1 (count (second %))))
       (into (sorted-map))))

(defn resolved-versions [env]
  (->> env pod/resolve-dependencies (map #(vec (take 2 (:dep %)))) (into {})))

(defn prn-conflicts [env]
  (let [check-mark "\u2714"
        version-of (resolved-versions env)]
    (doseq [[dep versions] (dep-conflicts env)]
      (let [explicit? (contains? (reduce into #{} (vals versions)) dep)
            dep-mark  (if explicit? check-mark "-")]
        (print (ansi/bold-white (format "[%s] %s\n" dep-mark dep)))
        (doseq [[version vias] (reverse versions)]
          (let [chosen?  (= version (version-of dep))
                norm     (if chosen? ansi/green ansi/yellow)
                bold     (if chosen? ansi/bold-green ansi/bold-yellow)
                ver-mark (if chosen? dep-mark " ")]
            (print (bold (format "    [%s] %s\n" ver-mark version)))
            (doseq [via vias]
              (let [given?    (= dep via)
                    excluded? (not chosen?)
                    color     (if given? bold norm)
                    via-mark  (cond given?    check-mark
                                    excluded? " "
                                    :else     "-")]
                (print (color (format "        [%s] %s\n" via-mark via)))))))))))
