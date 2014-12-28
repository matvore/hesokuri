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
  (Integer. (or (getenv "HESOPORT") "8080")))

(def heso-cfg-file
  "The configuration file for storing the Hesokuri configuration, which includes
things like the address of each peer machine, the paths of each source on each
machine, which branches are live-edit, and which branches or unwanted.

TODO(matvore): Consider removing support for this file once the hesobase is
functional."
  (cjio/file (or (getenv "HESOCFG")
                 (cjio/file env/home ".hesocfg"))))

(def hesoroot
  (cjio/file (or (getenv "HESOROOT") env/home)))

(def usage
  (str "Hesokuri: The distributed Git repo synchronization tool
Copyright (C) 2014 Google Inc.

USAGE
-----
lein run
  Starts Hesokuri process.

lein run help
  Shows this help.
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
  (cond
   (empty? args)
   ,(let [heso-agent (agent (heso/with-config []))
          dynamic-config-agent
           (->> (cb [heso-agent] [config]
                    (send heso-agent heso/update-config config))
                (dynamic-config/of heso-cfg-file)
                agent)]
      (send dynamic-config-agent dynamic-config/start)
      (alter-var-root #'hesokuri.web/*web-heso* (constantly heso-agent))
      (run-jetty (-> web/heso-web-routes
                     wrap-params)
                 {:port diag-ui-port :join? false}))

   (= ["help"] args) (exit usage *out* 0)
   :else (exit usage *err* 1)))
