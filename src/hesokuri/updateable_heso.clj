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

    :omit dead-heso
    (letmap
     [:keep config-file
      :omit noop (constantly nil)

      sources []
      local-identity "localhost"
      restart-peer noop
      restart-source noop

      snapshot
      (fn []
        (letmap
         [:keep [config-file sources local-identity]
          active false
          source-info {}
          peer-info {}]))

      stop noop
      push-sources-for-peer noop
      start noop])

    :omit on-change
    (fn [] (send self (fn [{:keys [heso] :as self}]
      (when heso (maybe "Stop heso" (heso :stop)))
      (let [heso (maybe (str "Create new heso from " config-file)
                        new-heso config-file)]
        (when heso ((heso :start)))
        (assoc self :heso heso)))))

    ;; Returns the current heso object associated with this object. Returns a
    ;; default heso object that does nothing if one is not available.
    heso (fn [] (or (@self :heso) dead-heso))

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
