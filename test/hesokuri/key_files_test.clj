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

(ns hesokuri.key-files-test
  (:require [clojure.string :as cstr]
            [clojure.test :refer :all]
            [hesokuri.key-files :refer :all]
            [hesokuri.testing.temp :refer :all]))

(deftest test-refresh
  (are [start-file lines new-file]
    (= new-file
       (let [temp (temp-file-containing start-file)]
         (refresh temp lines)
         (slurp temp)))

    "" [] ""
    "a" ["a"] (str "a\n"
                   "a" line-suffix "\n")

    (str "a\n"
         "b" line-suffix "\n")
    ["a"]
    (str "a\n"
         "a" line-suffix "\n")

    (str "a\n"
         "b" line-suffix "\n")
    ["b"]
    (str "a\n"
         "b" line-suffix "\n")

    (str "b" line-suffix "\n"
         "a\n")
    ["b"
     "c"]
    (str "b" line-suffix "\n"
         "a\n"
         "c" line-suffix "\n")

    (str "b" line-suffix "\n"
         "a\n")
    ["c"
     "b"]
    (str "b" line-suffix "\n"
         "a\n"
         "c" line-suffix "\n")))
