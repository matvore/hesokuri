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

(ns hesokuri.heartbeats
  "Gives functinoality to run a repeated task at regular intervals, and allows
  stopping all associated with a heartbeats object at once."
  (:use [hesokuri.util]
        clojure.tools.logging))

(defn- beat
  [{:keys [interval-millis action group] :as self} sleep-millis]
  {:pre [(identical? self @*agent*)]}
  (Thread/sleep sleep-millis)
  (if (not= (:orig group) @(:current group))
    (assoc self :state :stopped)
    (do (apply (:fn action) (:args action))
        (send-off *agent* beat interval-millis)
        self)))

(defn without-stopped-beats [beats]
  (-> #(not= (:state %) :stopped) (filter beats) vec))

(defn start
  [{:keys [group beats] :as self} interval-millis action-fn & action-args]
  (let [group (or group (atom nil))
        new-beat (agent {:interval-millis interval-millis
                         :action {:fn action-fn :args action-args}
                         :group {:orig @group :current group}
                         :state :started})
        filtered-beats (without-stopped-beats beats)]
    (send-off new-beat beat 0)
    (assoc self
      :group group
      :beats (conj filtered-beats new-beat))))

(defn stop-all
  "Stops all heartbeats begun on this object with the start function."
  [{:keys [group] :as self}]
  (swap! group (constantly (Object.)))
  (dissoc self :beats))
