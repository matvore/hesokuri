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

(ns hesokuri.dynamic-config
  (:require [clojure.java.io :refer [file]]
            [hesokuri.config :as config]
            [hesokuri.log :as log]
            [hesokuri.util :refer :all]
            [hesokuri.watcher :as watcher]))

(defn- handle-change
  "Reads the configuration from the given file and calls on-change-cb with it.
  If the configuration cannot be read or it is not valid, an error is logged and
  this function does nothing."
  [config-file on-change-cb]
  (let [config (config/from-file config-file)]
    (if config
      (on-change-cb config)
      (.severe (log/ger)
               (str "Not activating configuration from file: " config-file)))))

(defn of
  "Creates an instance.
  config-file - where to read the configuration from, and write the
      configuration to if we automatically update it.
  on-change-cb - callback to invoke when the configuration changes. Called with
      the config object."
  [config-file on-change-cb]
  (letmap
   [config-file (file config-file)

    on-change-config-file
    (cb [config-file on-change-cb] []
        (handle-change config-file on-change-cb))]))

(defn start
  "Starts the given dynamic-config, and returns its new state. If the instance
  is already started, this function does nothing and returns the instance given
  it without change.

  'Starting' entails reading the current contents of the config file
  immediately, passing it to the cb passed to the of function, and watching the
  config file for further changes."
  [{:keys [config-file config-file-watcher on-change-config-file] :as self}]
  (cond
   config-file-watcher
   self

   :else
   (do (cbinvoke on-change-config-file)
       (assoc self
         :config-file-watcher
         (watcher/for-file config-file on-change-config-file)))))

