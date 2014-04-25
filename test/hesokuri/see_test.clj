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

(ns hesokuri.see-test
  (:use clojure.test
        hesokuri.see))

(deftest printable-fn-non-funcs
  (are [expr]
       (is (nil? (printable-fn expr)))
       nil
       1
       {3 4}))

(deftest test-shrink
  (are [expr expected-result]
       (is (= expected-result (shrink expr)))
       (repeat 3 (repeat 10 :x))
       [(repeat 10 :x) [:hesokuri.see/path 0] [:hesokuri.see/path 0]]

       (shrink [{} {}])
       [{} [:hesokuri.see/path 0]]

       (let [atm (atom [:foo])]
         [atm atm [:foo]])
       [[:hesokuri.see/atom [:foo]]
        [:hesokuri.see/path 0]
        [:hesokuri.see/path 0 :deref]]))

(deftest test-shrink-fn
  (let [x 42
        shrunk (shrink (fn [y] (+ x y)))]
    (is ((set (.values shrunk)) 42))
    (is (contains? shrunk :fn-class))))

(defn throw-exception [s e] (throw e) s)

(deftest test-shrink-agent
  (let [exception (RuntimeException. "test exception")
        agent-with-error (-> (agent :test-agent)
                             (send throw-exception exception))]
    (while (nil? (agent-error agent-with-error))
      (Thread/sleep 25))
    (is (= [:hesokuri.see/agent exception :test-agent]
           (shrink agent-with-error)))
    (is (= [:hesokuri.see/agent :test-agent-2]
           (shrink (agent :test-agent-2))))))

(deftest test-shrink-box-with-abbreviation
  (let [expr (shrink {:foo [1 2 3]
                      :bar (atom [1 2 3])})]
    (is (or (= {:foo [:hesokuri.see/path :bar :deref]
                :bar [:hesokuri.see/atom [1 2 3]]}
               expr)
            (= {:foo [1 2 3]
                :bar [:hesokuri.see/atom [:hesokuri.see/path :foo]]})))))
