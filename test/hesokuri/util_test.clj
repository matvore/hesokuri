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
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream
            ObjectInputStream])
  (:require [clojure.test :refer :all]
            [clojure.java.io :as cjio]
            [hesokuri.util :refer :all]
            [hesokuri.testing.mock :refer :all]))

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
        (let [src-stream (ByteArrayInputStream. (.getBytes src "UTF-8"))
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
    (= (seq (concat (.getBytes (str s) "UTF-8") by))
       (let [baos (ByteArrayOutputStream.)]
         (write-bytes baos s)
         (.write baos (byte-array by))
         (seq (.toByteArray baos))))
    "abc" []
    "abc" [1 2 3]
    "" []
    "" [4 5]
    1042 []
    1042 [1 2 3]))

(deftest test-serialize
  (are [obj]
    (= obj (-> (ByteArrayOutputStream.)
               (#(do (serialize % obj)
                     %))
               .toByteArray
               ByteArrayInputStream.
               ObjectInputStream.
               .readObject))
    42
    "a string"
    [10 12 14]))

(deftest test-conj-in
  (are [m ks v default-coll result]
    (= result (conj-in m ks v default-coll))
    {} [:a] 4 #{1 2 3} {:a #{1 2 3 4}}
    {:a {}} [:a :b] 4 [3 2 1 0] {:a {:b [3 2 1 0 4]}}
    [] [0 :a] 42 '(7 6) [{:a '(42 7 6)}]))

(deftest test-like
  (are [convert f args result]
    (= result (apply like convert f args))

    int + [\a 1] 98
    int - [\a 1] 96
    str vector [1 2 3 []] ["1" "2" "3" "[]"]))

(deftest test-inside?
  (are [dir f result]
    (= result
       (inside? dir f)
       (inside? (cjio/file dir) f)
       (inside? dir (cjio/file f)))

    "/foo/bar" "/foo/bar/baz" true
    "/foo/bar" "/foo/bar/baz/bang" true
    "/" "/etc" true
    "/etc" "/" false

    "/foo" "/foo" true))

(deftest test-pretty-printed
  (let [res (pretty-printed {:a :b
                             :c :d
                             :e :f
                             :g :h
                             ;; Make the string very long so we know it will
                             ;; have a newline.
                             :i (range 100)})]
    (is (.contains res ",")
        (str "pretty printed map should have comma: " res))
    (is (.contains res "\n")
        (str "pretty printed map should have newline: " res))))
