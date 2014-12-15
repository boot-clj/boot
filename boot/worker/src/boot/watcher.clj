; Copyright (C) 2013 Google Inc.
;
; Licensed under the Apache License, Version 2.0 (the "License");
; you may not use this file except in compliance with the License.
; You may obtain a copy of the License at
;
;    http://www.apache.org/licenses/LICENSE-2.0
;
; Unless required by applicable law or agreed to in writing, software
; distributed under the License is distributed on an "AS IS" BASIS,
; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
; See the License for the specific language governing permissions and
; limitations under the License.

(ns boot.watcher
  {:boot/from :google/hesokuri
   :doc "This file was modified by micha.niskin@gmail.com."}
  (:import
    [java.nio.file FileSystems Path Paths StandardWatchEventKinds]
    [com.barbarysoftware.watchservice StandardWatchEventKind WatchableFile])
  (:require
    [clojure.java.io :as io]
    [boot.util       :as util]))

(defprotocol IRegister
  (register [this path events])
  (enum->kw [this x]))

(extend-type java.nio.file.WatchService
  IRegister
  (register [service path events]
    (let [path (Paths/get (str path) (into-array String []))
          events (into-array (map {:create StandardWatchEventKinds/ENTRY_CREATE
                                   :modify StandardWatchEventKinds/ENTRY_MODIFY
                                   :delete StandardWatchEventKinds/ENTRY_DELETE}
                               events))]
      (.register path service events)))
  (enum->kw [this x]
    (-> {StandardWatchEventKinds/ENTRY_CREATE :create
         StandardWatchEventKinds/ENTRY_MODIFY :modify
         StandardWatchEventKinds/ENTRY_DELETE :delete}
      (get x))))

(extend-type com.barbarysoftware.watchservice.WatchService
  IRegister
  (register [service path events]
    (let [path (WatchableFile. (io/file path))
          events (into-array (map {:create StandardWatchEventKind/ENTRY_CREATE
                                   :modify StandardWatchEventKind/ENTRY_MODIFY
                                   :delete StandardWatchEventKind/ENTRY_DELETE}
                               events))]
      (.register path service events)))
  (enum->kw [this x]
    (-> {StandardWatchEventKind/ENTRY_CREATE :create
         StandardWatchEventKind/ENTRY_MODIFY :modify
         StandardWatchEventKind/ENTRY_DELETE :delete}
      (get x))))

(defn- register-recursive
  [service path events]
  (register service path events)
  (doseq [dir (.listFiles (io/file path))]
    (when (.isDirectory dir)
      (register-recursive service dir events))))

(defn- new-watch-service []
  (if (= "Mac OS X" (System/getProperty "os.name"))
    ;; Use barbarywatchservice library for Mac OS X so we can avoid polling.
    (com.barbarysoftware.watchservice.WatchService/newWatchService)

    ;; Use java.nio file system watcher
    (.newWatchService (FileSystems/getDefault))))

(defn- take-watch-key [service]
  (try
    (.take service)
    (catch java.nio.file.ClosedWatchServiceException _ nil)
    (catch com.barbarysoftware.watchservice.ClosedWatchServiceException _ nil)))

(defn- service
  [queue paths]
  (let [service (new-watch-service)
        doreg   #(register-recursive %1 %2 [:create :modify])]
    (doseq [path paths] (doreg service (io/file path)))
    (-> #(let [watch-key (take-watch-key service)]
           (when watch-key
             (if-not (.isValid watch-key)
               (do (util/dbug "invalid watch key %s\n" (.watchable watch-key))
                   (Thread/sleep 500)
                   (recur))
               (do (doseq [event (.pollEvents watch-key)]
                     (let [dir     (.toFile (.watchable watch-key))
                           changed (io/file dir (str (.context event)))
                           etype   (enum->kw service (.kind event))
                           dir?    (.isDirectory changed)]
                       (cond
                         (and dir? (= :create etype)) (doreg service changed)
                         (not dir?) (.offer queue (.getPath changed)))))
                   (and watch-key (.reset watch-key) (recur))))))
      Thread. .start)
    service))

(def ^:private watchers (atom {}))

(defn stop-watcher
  [k]
  (when-let [w (@watchers k)] (.close w)))

(defn make-watcher
  [queue paths]
  (let [k  (str (gensym))
        s  (service queue paths)
        fs (->> paths (mapcat (comp file-seq io/file)) (filter (memfn isFile)))]
    (swap! watchers assoc k s)
    (doseq [f fs] (.offer queue (.getPath f)))
    k))
