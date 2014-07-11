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

(ns hesokuri.testing.ssh
  "Utilities for creating SSH connections during tests."
  (:require [clojure.test :refer :all]
            [hesokuri.ssh :as ssh]
            [hesokuri.util :refer :all]))

(defn free-port []
  (let [socket (java.net.ServerSocket. 0)
        port (.getLocalPort socket)]
    (.close socket)
    port))

(def client-key-pair (ssh/new-key-pair))
(def server-key-pair (ssh/new-key-pair))

(defmacro test-connection [server-connection-fn client-connection-fn]
  `(let-try [server-connection-fn# ~server-connection-fn
             client-connection-fn# ~client-connection-fn
             server-port# (free-port)
             server#
             ,(ssh/listen-connect server-port# server-key-pair
                                  (partial = (.getPublic client-key-pair))
                                  server-connection-fn#)]
     (is (= :ok (ssh/connect-to "127.0.0.1" server-port# client-key-pair
                                (partial = (.getPublic server-key-pair))
                                client-connection-fn#
                                nil)))
     (finally (.stop server#))))
