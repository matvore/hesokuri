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
            [hesokuri.hesobase :as hesobase]
            [hesokuri.ssh :as ssh]
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

(def hesobase-git-dir
  "Location of the hesobase .git directory."
  (cjio/file hesoroot ".hesobase.git"))

(def ssh-key-file
  (cjio/file hesoroot ".hesoid"))

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
  Prepares this machine to be a Hesokuri peer. This includes creating a hesobase
  to store configuration information, and a key pair for the identity of the
  peer.

  The key pair and hesobase will be stored in the hesoroot directory. By
  default, hesoroot is the current user's home directory, but this can be
  overridden by setting the HESOROOT environment variable.

  MACHINE-NAME is the name (and address) of this machine on the Hesokuri
  network. PORT is the local port to use for communicating with other machines
  on the network, or " default-prot-port " if omitted.
"))

(defn exit
  "Prints some text to the given stream and exits with the given exit code."
  [message out code]
  (copy+ message out .flush)
  (System/exit code))

(defn cmd-init
  "Implementation of the 'init' command. Returns a vector representing the
  arguments to pass to exit.

  machine-name, prot-port-str - correspond to the command-line arguments.
      prot-port-str is a String but can be nil if the argument was omitted on
      the command line.
  hesobase-git-dir - .git directory of the hesobase configuration repo to
      create. After this function returns, it will be a bare git repository with
      a single commit on the 'master' branch.
  ssh-key-file - path to store the key pair
  key-pair - the key pair representing this machine. See
      hesokuri.ssh/new-key-pair.
  timestamp-ms - value returned by System/currentTimeMillis."
  [machine-name prot-port-str hesobase-git-dir ssh-key-file key-pair
   timestamp-ms]
  (let [prot-port (if prot-port-str
                    (try (Integer. prot-port-str)
                         (catch NumberFormatException _ nil))
                    default-prot-port)
        already-exists (->> [hesobase-git-dir ssh-key-file]
                            (filter #(.exists (cjio/file %)))
                            first)
        author (git/author (quot timestamp-ms 1000))]
    (cond
     (nil? prot-port) [(str "Invalid port number: '" prot-port-str "'\n")
                       *err* 1]
     already-exists [(str "File already exists at " already-exists "\n"
                          "Delete it or set HESOROOT to a new location.\n")
                     *err* 1]
     :else
     ,(do (with-open [key-stream (FileOutputStream. ssh-key-file)]
            (serialize key-stream key-pair))
          ;; Only let the owner read the key file
          (doto (cjio/file ssh-key-file)
            (.setReadOnly)
            (.setReadable false false)
            (.setReadable true true))
          (hesobase/init hesobase-git-dir machine-name prot-port key-pair
                         author timestamp-ms)
          [(str "Initialized hesobase in: " hesobase-git-dir "\n"
                "Wrote this machine's key to: " ssh-key-file "\n")
           *out* 0]))))

(def startup-ms
  "When the Hesokuri process started, as the value returned by
  System/currentTimeMillis."
  (System/currentTimeMillis))

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
   (= "init" (first args))
   ,(let [[machine-name prot-port :as cmd-args] (rest args)]
      (apply exit
             (if-not (<= 1 (count cmd-args) 2)
               [usage *err* 1]
               (do
                 (.mkdirs (cjio/file hesoroot))
                 (cmd-init machine-name prot-port hesobase-git-dir ssh-key-file
                           (ssh/new-key-pair) startup-ms)))))
   (= ["help"] args) (exit usage *out* 0)
   :else (exit usage *err* 1)))
