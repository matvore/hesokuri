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

(deftest connect-std-streams
  (let [server-port (free-port)
        client-key-pair (new-key-pair)
        server-key-pair (new-key-pair)
        received-from-client (atom nil)

        new-connection-fn
        (fn [in out err]
          (let [in (slurp in)]
            (swap! received-from-client (constantly in)))
          (spit out "stdout from server")
          (.close out)
          (spit err "stderr from server")
          (.close err))

        server
        (listen-connect server-port server-key-pair
                        #{(.getPublic client-key-pair)}
                        new-connection-fn)

        client-channel
        (connect-to "127.0.0.1" server-port client-key-pair
                    #{(.getPublic server-key-pair)})

        [client-in client-out client-err] (open-channel-pipes client-channel)]
    (spit client-in "stdin from client" :encoding "UTF-8")
    (is (= "stdout from server" (slurp client-out :encoding "UTF-8")))
    (is (= "stderr from server" (slurp client-err :encoding "UTF-8")))
    (.awaitUninterruptibly (.close client-channel))
    (is (= "stdin from client" @received-from-client))
    (.stop server)))
