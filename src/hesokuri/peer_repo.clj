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

(ns hesokuri.peer-repo
  "Functions for dealing with peer repos. A peer-repo object describes repos
  that can be pushed to. It is a map with the following keys:
  :host - the host holding the repo
  :path - the path of the repo on the host
  This indicates a peer that is pushed to using ssh."
  (:import (java.net ConnectException InetAddress UnknownHostException)))

(defn accessible
  "Checks if the host of the given repo is accessible, waiting for the specified
  timeout for a respose.
  peer-repo - repo whose host to check for accessibility
  timeout - the number of millis to wait for a response"
  [peer-repo timeout]
  (-> (:host peer-repo)
      InetAddress/getByName
      (.isReachable timeout)
      (try (catch UnknownHostException _ false)
           ;; ConnectException happens when "Host is down" which is
           ;; not "exceptional"
           (catch ConnectException _ false))))


(defn push-str
  "Returns the <repository> argument to pass to git-push when pushing to this
  repository."
  [{:keys [host path]}]
  (str "ssh://" host path))
