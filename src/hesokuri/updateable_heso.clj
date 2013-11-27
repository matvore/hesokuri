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

(ns hesokuri.updateable-heso
  (:use hesokuri.util)
  (:require [hesokuri.heso :as heso]
            [hesokuri.watcher :as watcher]))

(defn with-config-file
  "Creates a new updateable-heso object with the given config-file, which
  specifies the path of the file from which to read the heso configuration."
  [config-file]
  {:config-file config-file
   :heso (agent (heso/with-sources []))})

(defn start-autoupdate
  "Begins autoupdate, which uses a watcher object to monitor changes in the
  config file. This call is idempotent. Returns the new state of the
  updateable-heso object."
  [{:keys [heso config-file watcher] :as self}]
  (if watcher self (assoc self :watcher
    (watcher/for-file
     config-file
     (cb [heso config-file] []
         (send heso heso/update-from-config-file config-file))))))

(defn stop-autoupdate
  "Stops autoupdate if it is currently started. Returns the new state of the
  updateable-heso object."
  [{:keys [watcher] :as self}]
  (when watcher (watcher/stop watcher))
  (dissoc self :watcher))
