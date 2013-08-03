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

(ns hesokuri.test-hesokuri.core
  (:use clojure.test
        hesokuri.core
        hesokuri.peer
        hesokuri.test-hesokuri.temp
        hesokuri.util))

(def sources-eg
  [{"peer1" "/peer1/foo"
    "peer2" "/peer2/foo"}
   {"peer2" "/peer1/bar"}
   {"peer1" "/peer1/baz"
    "peer3" "/peer3/baz"}
   {"peer1" "/peer1/42"
    "peer3" "/peer3/42"
    "peer4" "/peer4/42"}])

(deftest test-config-file
  (are [mock-env expected-config-file]
       (with-redefs [getenv mock-env]
         (is (= expected-config-file
                (#'hesokuri.core/config-file))))
       {"HESOCFG" "foo"} "foo"
       {"HESOCFG" "foo", "HOME" "should be ignored"} "foo"
       {"HOME" "/home/fbar"} "/home/fbar/.hesocfg"))

(deftest test-make-initial-heso
  (let [heso-1 (deref (#'hesokuri.core/make-initial-heso))
        heso-2 (deref (#'hesokuri.core/make-initial-heso))]
    (is (not= (:heartbeats heso-1) (:heartbeats heso-2)))
    (is (not= (deref (:heartbeats heso-1)) (deref (:heartbeats heso-2))))
    (is (nil? (deref (deref (:heartbeats heso-1)))))))

(deftest test-common-sources
  (are [sources peer-names source-indexes]
       (is (= (map sources-eg source-indexes)
              (apply #'hesokuri.core/common-sources sources peer-names)))
       [] ["peer1"] []
       [] ["peer1" "peer2"] []
       sources-eg ["peer1" "peer2"] [0]
       sources-eg ["peer2"] [0 1]
       sources-eg ["peer1" "peer3"] [2 3]))

(deftest test-snapshot
  (with-redefs
    [getenv {"HESOHOST" "peer3"
             "HESOCFG" (str (temp-file-containing sources-eg))}
     new-peer {:new-peer nil}
     send (fn [& rest] (apply list "disabling send" rest))]
    (let [heso (refresh-heso {})]
      (is (= {:peer-info {"peer1" {:errors nil, :new-peer nil},
                          "peer2" {:errors nil, :new-peer nil},
                          "peer4" {:errors nil, :new-peer nil}},
              :source-info {"/peer3/baz" {:branches nil, :errors nil},
                            "/peer3/42" {:branches nil, :errors nil}},
              :config-file (getenv "HESOCFG"),
              :sources [{"peer1" "/peer1/foo", "peer2" "/peer2/foo"}
                        {"peer2" "/peer1/bar"}
                        {"peer1" "/peer1/baz", "peer3" "/peer3/baz"}
                        {"peer1" "/peer1/42",
                         "peer3" "/peer3/42",
                         "peer4" "/peer4/42"}],
              :local-identity "peer3"}
             ((heso :snapshot)))))))
