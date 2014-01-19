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

(ns hesokuri.test-hesokuri.discovery-dir-def
  (:use clojure.test
        hesokuri.discovery-dir-def
        hesokuri.testing.validation))

(deftest test-peers-entry-validation
  (are [peer okay substrings]
       (validation-is-correct
        (#'hesokuri.discovery-dir-def/peers-entry-validation peer)
        okay
        substrings)

       [] false ["Expected a map"]
       42 false ["Expected a map"]
       {:hosts 100, :path "/valid-path"} false [":hosts entry of type vector"]
       {:hosts [], :path ["invalid path"]} false [":path entry of type string"]
       {:hosts ["host-1" "host-2" 42], :path "/valid-path"}
           false ["every entry to be a string"]
       {:hosts [], :path "/valid-path"} true []
       {:hosts ["x" "y"] :path "/valid-path"} true []))

(deftest test-validation
  (are [def okay substrings]
       (validation-is-correct (validation def) okay substrings)

       [] false ["Expected a map"]
       {} false ["Expected a :peers entry"]
       {:peers []} true []
       {:peers [42]} false []
       {:peers [{:hosts [], :path "/valid-path"}]} true []))
