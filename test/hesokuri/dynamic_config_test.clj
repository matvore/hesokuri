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

(ns hesokuri.dynamic-config-test
  (:import [java.io File])
  (:require [clojure.test :refer :all]
            [hesokuri.dynamic-config :refer :all]
            [hesokuri.testing.waiting :refer :all]
            [hesokuri.util :refer :all]))

(def ^:dynamic *config-file* nil)

(def config-0 [])
(def config-1 [{"foo" "/foo-path", "bar" "/bar-path"}])
(def config-2 [{"foo" "/foo-path-2", "bar" "/bar-path-2"}])

(defmacro with-temp-config-file [& body]
  `(binding [*config-file* (File/createTempFile "dynamic-config-test" nil)]
     (let [~'handled (atom [])
           ~'initial (of *config-file*
                         (fn [config#]
                             (swap! ~'handled (fn [v#] (conj v# config#)))))]
       ~@body)))

(deftest start-and-update
  (with-temp-config-file
    (spit *config-file* config-1)
    (let [started-once (start initial)
          started-twice (start started-once)]
      ;; start should be idempotent
      (is (= started-once started-twice)
          (not= initial started-once)))
    (spit *config-file* config-2)
    (wait-for (fn [] (= @handled [config-1 config-2])))))

(deftest handles-empty-config
  (with-temp-config-file
    (spit *config-file* config-0)
    (let [started (start initial)]
      (spit *config-file* config-1)
      (wait-for (fn [] (= @handled [config-0 config-1])))
      (spit *config-file* config-0)
      (wait-for (fn [] (= @handled [config-0 config-1 config-0]))))))
