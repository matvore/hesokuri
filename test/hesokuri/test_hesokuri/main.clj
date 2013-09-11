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

(ns hesokuri.test-hesokuri.main
  (:use clojure.test
        hesokuri.util
        hesokuri.main))

(deftest test-config-file
  (are [mock-env expected-config-file]
       (with-redefs [getenv mock-env]
         (is (= expected-config-file (#'hesokuri.main/config-file))))
       {"HESOCFG" "foo"} "foo"
       {"HESOCFG" "foo", "HOME" "should be ignored"} "foo"
       {"HOME" "/home/fbar"} "/home/fbar/.hesocfg"))
