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

(ns hesokuri.watcher
  "Module that makes watching file systems for changes easier. Internally, this
  modules uses barbarywatchservice for Macs, and java.nio.file otherwise. All
  watcher objects are maps having these keys:
  :watch-service - the underlying WatchService object (the WatchService class
      either in java.nio.file or the Barbary namespace)
  :dir - a java.io.File indicating the directory being watched. When watching a
      file, this indicates the containing directory
  :on-change-cb a cb that is invoked with the name of the changed file as a
      java.io.File object as the only argument"
  (:import [com.barbarysoftware.watchservice
            StandardWatchEventKind
            WatchableFile]
           [java.nio.file
            FileSystems
            Path
            Paths
            StandardWatchEventKinds])
  (:require [clojure.java.io :refer [file]]
            [hesokuri.util :refer :all]))

(defmulti ^:private register (fn [service _ _] (class service)))

(defmethod register java.nio.file.WatchService
  [service path events]
  (let [path (Paths/get (str path) (into-array String []))
        events (into-array (map {:create StandardWatchEventKinds/ENTRY_CREATE
                                 :modify StandardWatchEventKinds/ENTRY_MODIFY
                                 :delete StandardWatchEventKinds/ENTRY_DELETE}
                                events))]
    (.register path service events)))

(defmethod register com.barbarysoftware.watchservice.WatchService
  [service path events]
  (let [path (WatchableFile. (file path))
        events (into-array (map {:create StandardWatchEventKind/ENTRY_CREATE
                                 :modify StandardWatchEventKind/ENTRY_MODIFY
                                 :delete StandardWatchEventKind/ENTRY_DELETE}
                                events))]
    (.register path service events)))

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
  [path on-change-cb]
  (let [service (new-watch-service)
        path (file path)
        start
        (fn []
          (let [watch-key (take-watch-key service)]
            (doseq [event (and watch-key (.pollEvents watch-key))
                    :let [changed-file (-> event .context str file)]]
              (cbinvoke on-change-cb changed-file))
            (and watch-key (.reset watch-key) (recur))))]
    (register service path [:create :modify])
    (-> start Thread. .start)
    service))

(defn stop
  "Stops the given watcher. This is an idempotent operation."
  [{:keys [watch-service]}]
  (.close watch-service))

(defn for-dir
  "Watches a directory for modification or creation, and calls the given
  function when such an event occurs with a relative path to the affected file.
  path - a string or File that specifies the directory to watch
  on-change-cb - a cb to call when a file is created or modified in that
      directory. It will be passed the file that was changed."
  [path on-change-cb]
  {:watch-service (service path on-change-cb)
   :dir (file path)
   :on-change-cb on-change-cb})

(defn for-file
  "Similar to for-dir, but works on files rather than directories. The given
  function is called when the file is created or modified.
  on-change-cb - a cb to call when the file is created or modified. It does not
      get called with any arguments."
  [path on-change-cb]
  (let [path (file path)]
    (for-dir (.getParent path)
             (cb [path on-change-cb] [file]
                 (when (= (str file) (.getName path))
                   (cbinvoke on-change-cb))))))
