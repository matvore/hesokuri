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
        [ring.adapter.jetty :only [run-jetty]]
        [ring.middleware.params :only [wrap-params]])
  (:require [hesokuri.dynamic-config :as dynamic-config]
            [hesokuri.heso :as heso]
            [hesokuri.web :as web])
  (:gen-class))

(defn- port []
  "Returns the port to serve the heso web UI."
  (Integer. (or (getenv "HESOPORT") "8080")))

(defn- config-file []
  (or (getenv "HESOCFG")
      (str (getenv "HOME") "/.hesocfg")))

(defn -main
  "Starts up hesokuri."
  [& args]
  ;; work around dangerous default behaviour in Clojure
  (alter-var-root #'*read-eval* (constantly false))
  (let [config-file (config-file)
        heso-agent (agent (heso/with-config []))

        dynamic-config-agent
        (->> (cb [heso-agent] [config]
                 (send heso-agent heso/update-config config))
             (dynamic-config/of config-file)
             agent)]
    (send dynamic-config-agent dynamic-config/start)
    (alter-var-root #'hesokuri.web/*web-heso* (constantly heso-agent)))
  (run-jetty (-> web/heso-web-routes
                 wrap-params)
             {:port (port) :join? false}))
