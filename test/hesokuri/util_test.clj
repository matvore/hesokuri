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

(ns hesokuri.util-test
  (:use clojure.test
        hesokuri.util
        hesokuri.testing.mock))

(deftest test-cb
  (let [x 10
        result (cb [x] [y] (+ x y))]
    (is (= 110
           (cbinvoke result 100)
           (result 100))))
  (let [result (cb [] [x y] (* x y))]
    (is (= 121
           (cbinvoke result 11 11)
           (result 11 11)))))

(deftest test-read-until
  (let [space? #{(int \space)}]
    (are [src term? exp-str exp-term stream-rest]
        (let [src-stream (new java.io.ByteArrayInputStream
                              (.getBytes src "UTF-8"))
              [actual-str actual-term] (read-until src-stream term?)]
          (and (= exp-str actual-str)
               (integer? actual-term)
               (= (int exp-term) actual-term)
               (= [stream-rest -1] (read-until src-stream))))
      "foo " space? "foo" \space ""
      "foo bar" space? "foo" \space "bar"
      "" space? "" -1 ""
      "foo" space? "foo" -1 ""
      "foo bar" zero? "foo bar" -1 ""
      "foo\u0000bar" zero? "foo" 0 "bar")))

(deftest test-write-bytes
  (are [s by]
    (= (seq (concat (.getBytes s "UTF-8") by))
       (let [baos (new java.io.ByteArrayOutputStream)]
         (write-bytes baos s)
         (.write baos (byte-array by))
         (seq (.toByteArray baos))))
    "abc" []
    "abc" [1 2 3]
    "" []
    "" [4 5]))
