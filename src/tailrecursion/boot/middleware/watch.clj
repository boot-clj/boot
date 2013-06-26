(ns tailrecursion.boot.middleware.watch
  (:require
    [clojure.pprint   :refer [pprint]]
    [digest           :refer [md5]]
    [clojure.java.io  :refer [file]]
    [clojure.data     :refer [diff]]
    [clojure.set      :refer [difference intersection union]]))

(defn loop-msec [handler msec]
  (fn [spec]
    (handler spec)
    (Thread/sleep msec)
    (recur spec)))

(defn make-watcher [dir & exts]
  (let [prev (atom nil)]
    (fn []
      (let [ext?      (fn [x] (some #(.endsWith (.getPath x) %) exts))
            exts?     #(or (not exts) (ext? %))
            file?     #(.isFile %)
            only-file #(filter file? %)
            only-exts #(filter exts? %)
            make-info #(vector [% (.lastModified %)] [% (md5 %)])
            file-info #(mapcat make-info %)
            info      (->> dir file file-seq only-file only-exts file-info set)
            mods      (difference (union info @prev) (intersection info @prev))
            by        #(->> %2 (filter (comp %1 second)) (map first) set)]
        (reset! prev info)
        {:hash (by string? mods) :time (by number? mods)}))))

(defn watch [algo handler dirmap]
  (let [watchers (map (partial apply apply make-watcher) dirmap)]
    (fn [spec]
      (let [info (reduce (partial merge-with union) (map #(%) watchers))]
        (if-not (empty? (info algo))
          (handler (assoc spec :watch info)))))))

(defn watch-hash [handler dirmap]
  (-> (watch :hash handler dirmap) (loop-msec 100)))

(defn watch-time [handler dirmap]
  (-> (watch :time handler dirmap) (loop-msec 100)))
