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
  "Module that makes watching file systems for changes easier. This modules uses
  the Java API at java.nio.file internally. All watcher objects are maps having
  these keys:
  :watch-service - the underlying java.nio.file.WatchService object
  :path - a map in the form of {:dir DIR-PATH} or {:file FILE-PATH} indicating
      the directory or file being watched"
  (:import [java.nio.file
            ClosedWatchServiceException
            FileSystems
            Path
            Paths
            StandardWatchEventKinds])
  (:use [clojure.java.io :only [file]]
        hesokuri.util))

(defn- to-path
  [path]
  (cond
   (= Path (.getClass path)) path
   :else (Paths/get (str path) (into-array String []))))

(defn- service
  [path on-change-cb]
  (let [service (.newWatchService (FileSystems/getDefault))
        watch-path (to-path path)
        start
        (fn []
          (let [watch-key
                (try
                  (.take service)
                  (catch ClosedWatchServiceException _ nil))]
            (doseq [event (and watch-key (.pollEvents watch-key))
                    :let [changed-file (-> event .context str file)]]
              (cbinvoke on-change-cb changed-file))
            (and watch-key (.reset watch-key) (recur))))]
    (.register watch-path service
               (into-array
                [StandardWatchEventKinds/ENTRY_CREATE
                 StandardWatchEventKinds/ENTRY_MODIFY]))
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
