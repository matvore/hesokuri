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

(ns hesokuri.validation
  "Utilities for validating data structures. Validation is the process of
  checking some data structure - usually input by the user - for correctness,
  and returning a helpful error message when it is not valid.

  Functions that check for validation will return either a string, indicating
  the error, or nil - indicating validity. Such a value is called the validation
  result."
  (:require [clojure.string :refer [join]]))

(defn combine
  "Combines validation results. This takes a sequence of validation results and
  returns a single validation result. This function returns nil only if the
  sequence is comprised of zero or more nils."
  [all-results]
  (let [error-results (filter identity all-results)]
    (and (seq error-results) (join "\n" error-results))))

(defmacro conditions
  "Macro for creating a validation expression based on several checks to perform
  in sequence. Uses of the macro should take this form:

  (conditions
   COND-1 ERROR-1
   COND-2 ERROR-2
   ...
   COND-n ERROR-n)

  Each COND is a boolean expression which is checked in order. If the COND
  evaluates to true, the corresponding ERROR is ignored. If the COND evaluates
  to false, the corresponding ERROR is processed and used as the result. If all
  CONDs evaluate to true, the result is nil.

  ERROR-x is only evaluated if COND-x is false and all of the previous CONDs
  are true. COND-y is only evaluated if all of the previous CONDs are true.

  Each ERROR is a sequence. To convert it to a string, each element in the
  sequence is concatenated with the clojure.core/str function.

  Instead of COND-x and ERROR-x, you can put :passes and a validation
  expression, e.g.:

  COND-x ERROR-x
  :passes (separate-validation-function data)
  COND-y ERROR-y

  In this case, if the validation expression is nil, validation proceeds to the
  next condition. If the validation expression is a string, the conditions form
  evaluates to it."
  [& cond-error-s]
  `(or ~@(map (fn [[condition# error#]]
                (cond
                 (= :passes condition#)
                 error#

                 (keyword? condition#)
                 (throw (IllegalArgumentException.
                         (str "Unknown keyword: " condition#)))

                 :else
                 `(when-not ~condition# (str ~@error#))))
              (partition 2 cond-error-s))))

(defn for-vector
  "Performs validation for a vector, where the validation for each element uses
  the same logic. Combines the results of each one into a single validation
  result. This function returns an error result if the v argument is not a
  vector."
  [kind validation-fn v]
  (if (vector? v)
    (combine
     (for [el v
           :let [el-validation (validation-fn el)]
           :when el-validation]
       (str kind " '" el "' is invalid because: " el-validation)))
    (str "Expected a vector where each element is a " kind
         ", but got a " (class v))))
