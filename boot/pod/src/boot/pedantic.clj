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
  (let [version (resolved-versions env)]
    (doseq [[k v] (dep-conflicts env)]
      (println (ansi/bold-white (str "\u2022 " k)))
      (doseq [[k' v'] (reverse v)
              :let [v? (= k' (version k))
                    m  (if v? "\u2714" "\u2718")
                    c1 (if v? ansi/green ansi/yellow)
                    c2 (if v? ansi/bold-green ansi/bold-yellow)]]
        (->> (c1 (string/join "\n    " v'))
             (format "  %s %s\n    %s\n" (c2 m) (c2 k'))
             print)))))
