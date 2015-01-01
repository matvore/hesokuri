; Copyright (C) 2014 Google Inc.
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

(ns hesokuri.env
  "Contains environment settings, including environment variables, system
  properties, and values derived from them."
  (:require [clojure.java.io :as cjio]
            [clojure.java.shell :as cjshell]
            [clojure.string :as cstr]
            [hesokuri.util :refer :all]))

(def home
  "The user's home directory. This is used as a default for some settings."
  (cjio/file (System/getProperty "user.home")))

(def ssh-dir (cjio/file home ".ssh"))

(def known-hosts-file (cjio/file ssh-dir "known_hosts"))
(def authorized-keys-file (cjio/file ssh-dir "authorized_keys"))

(def startup-dir
  "The current working directory when Hesokuri started. This is used for
  interactive commands."
  (cjio/file (System/getProperty "user.dir")))

(def heso-cfg-file
  "The configuration file for storing the Hesokuri configuration, which includes
  things like the address of each peer machine, the paths of each source on each
  machine, which branches are live-edit, and which branches or unwanted."
  (cjio/file (or (System/getenv "HESOCFG")
                 (cjio/file home ".hesocfg"))))

(defn ips
  "Returns the IP addresses of all network interfaces as a sequence of strings."
  []
  (for [i (java.util.Collections/list (java.net.NetworkInterface/getNetworkInterfaces))
        addr (and i (.getInterfaceAddresses i))
        :when addr]
    (-> addr .getAddress .getHostAddress (cstr/split #"%") first)))

(defn local-identity
  "Determines the local identity, which is this machine's hostname as used in
  the configuration file. This uses the IP addresses for this machine to help
  guess in some cases."
  [hostname?]
  (or (System/getenv "HESOHOST")
      (some #(and (hostname? %) %) (ips))
      (-> "hostname" cjshell/sh :out cstr/trim)))
