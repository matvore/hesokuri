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

(ns hesokuri.main
  (:use hesokuri.util
        hesokuri.web)
  (:require [noir.server :as server]
            [hesokuri.heso :as heso]
            [hesokuri.updateable-heso :as updateable-heso])
  (:gen-class))

(defn- port []
  "Returns the port to serve the heso web UI."
  (Integer. (or (getenv "HESOPORT") "8080")))

(defn- config-file []
  (or (getenv "HESOCFG")
      (str (getenv "HOME") "/.hesocfg")))

(server/load-views-ns 'hesokuri.web)

(defn -main
  "Starts up hesokuri."
  [& args]
  ;; work around dangerous default behaviour in Clojure
  (alter-var-root #'*read-eval* (constantly false))
  (let [config-file (config-file)
        heso-agent (-> config-file
                       updateable-heso/with-config-file
                       updateable-heso/start-autoupdate
                       :heso)]
    (send heso-agent heso/update-from-config-file config-file)
    (alter-var-root #'hesokuri.web/*web-heso* (constantly heso-agent)))
  (server/start (port) {:mode :dev, :ns 'hesokuri}))
