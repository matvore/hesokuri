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
  (:use hesokuri.core
        hesokuri.util
        hesokuri.watching))

(defn new-updateable-heso
  "Creates a new updateable-heso object."
  []
  (letmap
   [:omit self (agent {})

    config-file (config-file)

    :omit on-change
    (fn [] (send self (fn [{:keys [heso] :as self}]
      (when heso (maybe "Stop heso" (heso :stop)))
      (let [heso (maybe (str "Create new heso from " config-file)
                        new-heso config-file)]
        (when heso ((heso :start)))
        (assoc self :heso heso)))))

    snapshot
    (fn [] (let [self @self]
      (if (self :heso) ((heso :snapshot)) {})))

    start
    (fn [] (send self (fn [{:keys [watcher] :as self}]
      (if watcher self
          (do
            (on-change)
            (letmap [watcher (watcher-for-file config-file on-change)]))))))

    stop
    (fn [] (send self (fn [{:keys [heso watcher] :as self}]
      (when heso ((heso :stop)))
      (when watcher ((watcher :stopper)))
      {})))]))
