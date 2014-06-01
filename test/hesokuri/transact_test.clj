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

(ns hesokuri.transact-test
  (:require [clojure.test :refer :all]
            [hesokuri.transact :refer :all]))

(deftest test-noop-transaction
  (is (= 42 (transact (fn [_] 42)))))

(deftest test-body-throws-exception
  (is (thrown? Exception (transact (fn [_] (throw (Exception.)))))))

(defn closeable [closed-atom index]
  (reify
    java.io.Closeable
    (close [_] (swap! closed-atom #(conj % index)))))

(deftest test-transact-closes-pending-upon-completion
  (let [closed (atom #{})
        opened-1 (closeable closed 1)
        opened-2 (closeable closed 2)
        make-trans-fn
        (fn [lastly]
          (fn [trans]
            (swap! trans open opened-1)
            (swap! trans open opened-2)
            (lastly)))]
    (are [lastly return]
      (let [do-trans #(transact (make-trans-fn lastly))]
        (swap! closed (constantly #{}))
        (if return
          (is (= return (do-trans)))
          (is (thrown? Exception (do-trans))))
        (is (= #{1 2} @closed)))
      #(throw (Exception.)) nil
      (constantly 42) 42)))

