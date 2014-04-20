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
        clojure.tools.logging
        hesokuri.ssh))

(defn free-port []
  (let [socket (new java.net.ServerSocket 0)
        port (.getLocalPort socket)]
    (.close socket)
    port))

(defn read-line-stream
  "Reads a line from the given java.io.InputStream and returns a String without
the trailing newline."
  [input-stream]
  (let [result (new StringBuilder)]
    (loop []
      (let [c (.read input-stream)]
        (if (#{-1 (int \newline)} c)
          (str result)
          (do (.append result (char c))
              (recur)))))))

(defmacro with-connection [[new-connection-fn] & body]
  `(let [new-connection-fn# ~new-connection-fn
         server-port# (free-port)
         client-key-pair# (new-key-pair)
         server-key-pair# (new-key-pair)

         server#
         (listen-connect server-port# server-key-pair#
                         (partial = (.getPublic client-key-pair#))
                         new-connection-fn#)

         client-channel#
         (connect-to "127.0.0.1" server-port# client-key-pair#
                     (partial = (.getPublic server-key-pair#)))

         [~'client-in ~'client-out ~'client-err]
         (channel-streams client-channel#)]
     (try
       ~@body
       (finally
         (.close client-channel# false)
         (.stop server#)))))

(deftest connect-stdout-stderr
  (with-connection
    [(fn [_ out err]
       (spit out "stdout from server\n")
       (spit err "stderr from server\n")
       0)]
    (is (= "stdout from server" (read-line-stream client-out)))
    (is (= "stderr from server" (read-line-stream client-err)))))

(deftest connect-stdin
  (let [read-from-stdin (promise)]
    (with-connection
      [(fn [in _ _]
         (deliver read-from-stdin (read-line-stream in))
         0)]
      (spit client-in "stdin from client\n")
      (is (= "stdin from client" @read-from-stdin)))))

(deftest connect-stdin-one-char
  (let [read-char (promise)]
    (with-connection
      [(fn [in _ _]
         (info "read character...")
         (deliver read-char (.read in))
         (info "done in server")
         0)]
      (.write client-in 42)
      (.flush client-in)
      (info "wait on promise...")
      (is (= 42 @read-char))
      (info "done in client"))))
