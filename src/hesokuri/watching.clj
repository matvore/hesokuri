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

(ns hesokuri.watching
  "Module that makes watching file systems for changes easier. This modules uses
  the Java API at java.nio.file internally. All watcher objects are maps having
  a :stopper key. The value for this key is a function that, when called, stops
  the watcher and causes it to free its resources."
  (:import [java.nio.file
            ClosedWatchServiceException
            FileSystems
            Path
            Paths
            StandardWatchEventKinds])
  (:use [clojure.java.io :only [file]]))

(defn- to-path
  [path]
  (cond
   (= Path (.getClass path)) path
   :else (Paths/get (str path) (into-array String []))))

(defn- stopper-for-watcher
  [path on-change]
  (let [service (.newWatchService (FileSystems/getDefault))
        watch-path (to-path path)
        start
        (fn []
          (let [watch-key
                (try
                  (.take service)
                  (catch ClosedWatchServiceException _ nil))]
            (doseq [event (and watch-key (.pollEvents watch-key))]
              (-> event .context str file on-change))
            (and watch-key (.reset watch-key) (recur))))]
    (.register watch-path service
               (into-array
                [StandardWatchEventKinds/ENTRY_CREATE
                 StandardWatchEventKinds/ENTRY_MODIFY]))
    (-> start Thread. .start)
    (fn [] (.close service))))

(defn watcher-for-dir
  "Watches a directory for modification or creation, and calls the given
  function when such an event occurs with a relative path to the affected file.
  path - a string or File that specifies the directory to watch
  on-change - a function to call when a file is created or modified in that
      directory."
  [path on-change]
  {:stopper (stopper-for-watcher path on-change)
   :dir (file path)})

(defn watcher-for-file
  "Same as watcher-for-dir, but works on files rather than directories. The
  given function is called (with no arguments) when the file is created or
  modified."
  [path on-change]
  (let [path (file path)]
    {:stopper (stopper-for-watcher
               (.getParent path)
               #(when (= (str %) (.getName path))
                  (on-change)))
     :file path}))
