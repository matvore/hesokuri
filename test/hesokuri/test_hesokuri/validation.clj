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

(ns hesokuri.test-hesokuri.validation
  (:use [clojure.string :only [split]]
        clojure.test
        hesokuri.validation))

(deftest test-combine
  (are [all-strings result]
       (is (= result (combine all-strings)))

       [] nil
       [nil nil] nil
       [nil nil "foo" nil] "foo"
       ["foo"] "foo"
       ["foo" nil "bar"] "foo\nbar"))

(defn- even-num-validation [n]
  (if (and (integer? n) (even? n)) nil (str "Expected an even integer: " n)))

(deftest test-for-vector
  (are [v error-count]
       (let [result (for-vector "even-num" even-num-validation v)]
         (if (zero? error-count)
           (is (nil? result))
           (is (= error-count (count (split result #"\n"))))))
       [] 0
       [2 4 0 2] 0
       [:foo] 1
       [{}] 1
       [13 13 15] 3
       [13 0 2 1] 2
       {} 1
       {:a 1, :b 2} 1))

(deftest test-conditions
  (is (= nil (conditions)))
  (is (= "error" (conditions
                  (= 1 1) ["okay"]
                  (= 2 2) ["okay"]
                  (= 3 3.14) ["error"])))
  (is (= "abcd" (conditions
                 false ["a" "b" "c" "d"])))
  (is (= nil (conditions
              (= 1 1) ["okay"]
              (= 2 2) ["okay"])))
  (is (= "" (conditions
             false [])))
  (is (= "evaluated" (conditions
                      false ["evaluated"]
                      false [(throw (RuntimeException. "not evaluated"))])))
  (is (= "not in a vector" (conditions false "not in a vector")))
  (is (= nil (conditions :passes nil, true "okay")))
  (is (= "fails" (conditions true "okay", :passes "fails"))))
