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

(ns hesokuri.config-test
  (:require [hesokuri.ssh :as ssh])
  (:use clojure.test
        hesokuri.config
        hesokuri.testing.data
        hesokuri.testing.validation))

(deftest test-source-defs
  (are [config sources]
       (is (= sources (source-defs config)))

       {:sources "foo"} "foo"
       {:sources "bar", :comment "baz"} "bar"
       ["foo" "bar"] ["foo" "bar"]))

(deftest test-round-trip-validation-error
  (are [data okay substrings]
       (validation-is-correct
        (#'hesokuri.config/round-trip-validation-error data) okay substrings)

       ["ok"] true []
       [nil nil] true []
       [true false] true []
       {true :keyword, false 1.5} true []
       [#{} #{} #{}] true []
       [(list 'foo 'bar)] false ["(foo bar)"]
       ['(foo) "ok" '(bar)] false ["(foo)" "(bar)"]
       #{[] [1] ["two"]} true []

       {:a '(bad-value 1) :b '(bad-value 2)} false
       ["(bad-value 1)" "(bad-value 2)"]

       {'(bad-key 1) :a '(bad-key 2) :b} false ["(bad-key 1)" "(bad-key 2)"]
       {'(1) '(2) '(3) '(4)} false ["(1)" "(2)" "(3)" "(4)"]
       [(ssh/public-key *key-str-a*)] true []))

(deftest test-validation-error
  (are [config okay substrings]
       (validation-is-correct
        (validation config) okay substrings)

       *sources-eg* true []
       *config-eg* true []
       {:comment "missing sources"} false []
       {:comment "sources is wrong type" :sources #{}} false []
       {:comment ["not round-trippable" 'foo] :sources []} false ["foo"]
       {:comment ["no sources is okay"] :sources []} true []
       #{"must be a map or vector"} false ["PersistentHashSet"]
       {:host-to-key [] :sources []} false [":host-to-key must be a map"]
       {:host-to-key {"a" "b"} :sources []} false
       ,["must be a java.security.PublicKey"]
       {:host-to-key {"a" (ssh/public-key *key-str-a*)} :sources []} true []))
