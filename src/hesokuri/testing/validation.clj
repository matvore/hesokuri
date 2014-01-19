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

(ns hesokuri.testing.validation
  "Utility methods for testing validation logic."
  (:use clojure.test))

(defn validation-is-correct
  "Asserts that some validation expression is as expected.
  error-string - the validation expression
  okay - whether the validation expression indicates valid (true) or invalid
      (false)
  substrings - substrings that are expected in the validation expression"
  [error-string okay substrings]
  (is (= okay (= error-string nil)))
  (doseq [substring substrings]
    (is (not= -1 (.indexOf error-string substring))))
  true)
