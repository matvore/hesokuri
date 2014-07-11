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

(ns hesokuri.ssh-test
  (:import [clojure.lang ExceptionInfo]
           [java.security PublicKey])
  (:require [clojure.test :refer :all]
            [clojure.tools.logging :refer :all]
            [hesokuri.ssh :refer :all]
            [hesokuri.testing.data :refer :all]
            [hesokuri.testing.ssh :refer :all]
            [hesokuri.util :refer :all]))

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

(deftest test-new-key-pair-is-pair-of-rsa-keys
  (let [pair (new-key-pair)]
    (is (= "RSA" (.getAlgorithm (.getPublic pair))))
    (is (= "RSA" (.getAlgorithm (.getPrivate pair))))))

(deftest test-public-key-coersion-string-round-trip
  (is (instance? PublicKey (public-key *key-str-a*)))
  (is (= *key-str-a* (public-key-str (public-key *key-str-a*)))))

(deftest test-public-key-coersion-from-key-pair
  (is (instance? PublicKey (public-key (new-key-pair)))))

(deftest test-public-key-coersion-from-private-throws-exception
  (is (thrown? ExceptionInfo (public-key (.getPrivate (new-key-pair))))))

(deftest connect-stdout-stderr
  (test-connection
   (fn [_ out err]
     (spit out "stdout from server\n")
     (spit err "stderr from server\n")
     0)
   (fn [_ out err _]
     (is (= "stdout from server" (read-line-stream out)))
     (is (= "stderr from server" (read-line-stream err)))
     [false :ok])))

(deftest connect-stdin
  (let [read-from-stdin (promise)]
    (test-connection
     (fn [in _ _]
       (deliver read-from-stdin (read-line-stream in))
       0)
     (fn [in _ _ _]
       (spit in "stdin from client\n")
       (is (= "stdin from client" @read-from-stdin))
       [false :ok]))))

(deftest connect-stdin-one-char
  (let [read-char (promise)]
    (test-connection
     (fn [in _ _]
       (deliver read-char (.read in))
       0)
     (fn [in _ _ _]
       (.write in 42)
       (.flush in)
       (is (= 42 @read-char))
       [false :ok]))))

(deftest connect-multiple-sessions
  (let [counter (atom 0)]
    (test-connection
     (fn [_ out _]
       (spit out (str (swap! counter inc) "\n"))
       0)
     (fn [_ out _ acc]
       (let [acc (conj acc (read-line-stream out))]
         (if (>= (count acc) 3)
           (do (is (= ["3" "2" "1"] acc))
               [false :ok])
           [true acc]))))))
