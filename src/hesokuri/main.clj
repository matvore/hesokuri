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
  (:require [clojure.java.io :as cjio]
            [hesokuri.dynamic-config :as dynamic-config]
            [hesokuri.heso :as heso]
            [hesokuri.web :as web])
  (:gen-class))

(def diag-ui-port
  "The port on which to serve the diagnostics web UI, which is implemented in
the hesokuri.web namespace."
  (Integer. (or (getenv "HESOPORT") "8080")))

(def home
  "The user's home directory. This is used as a default for some settings."
  (cjio/file (System/getProperty "user.home")))

(def heso-cfg-file
  "The configuration file for storing the Hesokuri configuration, which includes
things like the address of each peer machine, the paths of each source on each
machine, which branches are live-edit, and which branches or unwanted.

TODO(matvore): Consider removing support for this file once the hesobase is
functional."
  (cjio/file (or (getenv "HESOCFG")
                 (cjio/file home ".hesocfg"))))

(def hesobase-git-dir
  "Location of the hesobase .git directory."
  (cjio/file (or (getenv "HESOROOT") home)
             ".hesobase.git"))

(def default-prot-port
  "The default protocol port. This number is pronounced 'hesoku,' because 8
sounds like 'hay,' 50 looks like 'so', and 9 in Japanese is 'ku.'"
  8509)

(def usage
  (str "Hesokuri: The distributed Git repo sync daemon
Copyright (C) 2014 Google Inc.

USAGE
-----
lein run
  Starts Hesokuri process.

lein run help
  Shows this help.

Experimental features
---------------------
lein run init MACHINE-NAME [PORT]
  MACHINE-NAME is the name (and address) of this machine on the Hesokuri
  network. PORT is the local port to use for communicating with other machines
  on the network, or " default-prot-port " if omitted.
"))

(defn exit
  "Prints some text to the given stream and exits with the given exit code."
  [message out code]
  (cjio/copy message out)
  (.flush out)
  (System/exit code))

(defn -main
  "Starts up Hesokuri or performs some administration task."
  [& args]
  ;; work around dangerous default behaviour in Clojure
  (alter-var-root #'*read-eval* (constantly false))
  (cond
   (empty? args)
    (let [heso-agent (agent (heso/with-config []))
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
   (= "init" (first args))
    (let [[_ machine-name prot-port & extra-args] args
          prot-port (if prot-port
                      (try (Integer. prot-port)
                           (catch NumberFormatException _
                             (exit
                              (format "Invalid port number: '%s'\n" prot-port)
                              *err* 1)))
                      default-prot-port)]
      (when (or (seq extra-args)
                (not machine-name))
        (exit usage *err* 1))
      ;; TODO: actually do initialization logic here. We should create a new
      ;; repository for the hesobase and a key pair for this machine's
      ;; identity.
      )
   (= ["help"] args) (exit usage *out* 0)
   :else (exit usage *err* 1)))
