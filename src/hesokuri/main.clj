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
  (:import [java.io FileOutputStream])
  (:require [clojure.java.io :as cjio]
            [hesokuri.cmd.config :as cmd.config]
            [hesokuri.cmd.unwanted :as cmd.unwanted]
            [hesokuri.config :as config]
            [hesokuri.dynamic-config :as dynamic-config]
            [hesokuri.env :as env]
            [hesokuri.git :as git]
            [hesokuri.heso :as heso]
            [hesokuri.util :refer :all]
            [hesokuri.web :as web]
            [ring.adapter.jetty :refer [run-jetty]]
            [ring.middleware.params :refer [wrap-params]])
  (:gen-class))

(def diag-ui-port
  "The port on which to serve the diagnostics web UI, which is implemented in
the hesokuri.web namespace."
  (Integer. (or (System/getenv "HESOPORT") "8080")))

(def usage
  (str "Hesokuri: Distributed Git repo synchronization tool
Copyright (C) 2014 Google Inc.

USAGE
-----
" env/heso-invocation "
  Starts Hesokuri process.

" env/heso-invocation " config
  Rewrites the configuration file in an equivalent, normalized format. This
  causes any subsequent automated edits to be cleaner, since automated edits
  always generate normalized configurations.

" env/heso-invocation " help
  Shows this help.

" env/heso-invocation " unwanted BRANCH_NAME
  Marks the given branch as unwanted in the configuration.

NOTES
-----
Commands that update the configuration will also commit the changed config file
if the file is inside one of the sources in the configuration.
"))

(defn exit
  "Prints some text to the given stream and exits with the given exit code."
  [message out code]
  (copy+ message out .flush)
  (System/exit code))

(defn -main
  "Starts up Hesokuri or performs some administration task."
  [& args]
  ;; work around dangerous default behaviour in Clojure
  (alter-var-root #'*read-eval* (constantly false))

  (if (empty? args)
    (let [heso-agent (agent (heso/with-config []))
          dynamic-config-agent
          ,(->> #(send heso-agent heso/update-config %)
                (dynamic-config/of env/heso-cfg-file)
                agent)]
      (send dynamic-config-agent dynamic-config/start)
      (alter-var-root #'hesokuri.web/*web-heso* (constantly heso-agent))
      (run-jetty (-> web/heso-web-routes
                     wrap-params)
                 {:port diag-ui-port :join? false}))

    (apply exit
           (cond
             (= "config" (first args)) (apply cmd.config/invoke (rest args))
             (= "unwanted" (first args)) (apply cmd.unwanted/invoke (rest args))
             (= ["help"] args) [usage *out* 0]
             true [usage *err* 1]))))
