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

(ns hesokuri.test-hesokuri.ssh
  (:use clojure.test
        hesokuri.ssh))

(defn free-port []
  (let [socket (new java.net.ServerSocket 0)
        port (.getLocalPort socket)]
    (.close socket)
    port))

(defn read-line [input-stream]
  (let [result (new StringBuilder)]
    (loop []
      (let [c (.read input-stream)]
        (if (#{-1 (int \newline)} c)
          (str result)
          (do (.append result (char c))
              (recur)))))))

(deftest connect-std-streams
  (let [server-port (free-port)
        client-key-pair (new-key-pair)
        server-key-pair (new-key-pair)
        received-from-client (atom nil)

        new-connection-fn
        (fn [in out err]
          (let [in (read-line in)]
            (swap! received-from-client (constantly in)))
          (.close in)
          (spit out "stdout from server\n")
          (spit err "stderr from server\n"))

        server
        (listen-connect server-port server-key-pair
                        (partial = (.getPublic client-key-pair))
                        new-connection-fn)

        client-channel
        (connect-to "127.0.0.1" server-port client-key-pair
                    (partial = (.getPublic server-key-pair)))

        [client-in client-out client-err] (open-channel-pipes client-channel)]
    (spit client-in "stdin from client\n" :encoding "UTF-8")
    (is (= "stdout from server" (read-line client-out)))
    (is (= "stderr from server" (read-line client-err)))
    (.awaitUninterruptibly (.close client-channel true))
    (is (= "stdin from client" @received-from-client))
    (.stop server)))
