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
        hesokuri.test-hesokuri.mock
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

(deftest test-new-heso-make-heartbeats
  (let [initial-heartbeats
        (fn []
          (binding [*letmap-omitted-key* ::omitted]
            (with-redefs
              [getenv {"HESOHOST" "peer3"
                       "HESOCFG" (str (temp-file-containing sources-eg))}]
              (get-in (new-heso) [::omitted :heartbeats]))))
        beats-1 (initial-heartbeats)
        beats-2 (initial-heartbeats)]
    (is (not= beats-1 beats-2))
    (is (not= (deref beats-1) (deref beats-2)))
    (is (nil? (deref (deref beats-1))))))

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
     new-peer (mock {[] [{:snapshot (constantly :peer-1)}
                         {:snapshot (constantly :peer-2)}
                         {:snapshot (constantly :peer-4)}]})]
    (is (= {:peer-info {"peer1" :peer-1
                        "peer2" :peer-2
                        "peer4" :peer-4}
            :source-info {"/peer3/baz" {:branches nil, :errors nil}
                          "/peer3/42" {:branches nil, :errors nil}}
            :config-file (getenv "HESOCFG")
            :sources [{"peer1" "/peer1/foo"
                       "peer2" "/peer2/foo"}
                      {"peer2" "/peer1/bar"}
                      {"peer1" "/peer1/baz"
                       "peer3" "/peer3/baz"}
                      {"peer1" "/peer1/42"
                       "peer3" "/peer3/42"
                       "peer4" "/peer4/42"}]
            :active false
            :local-identity "peer3"}
           (((new-heso) :snapshot))))))
