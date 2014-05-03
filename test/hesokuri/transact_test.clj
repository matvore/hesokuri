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
  (:use clojure.test
        hesokuri.transact))

(deftest test-noop-transaction
  (is (= [42 []] (transact (fn [_] 42)))))

(deftest test-body-throws-exception
  (let [e (Exception.)]
    (is (= [nil [e]] (transact (fn [_] (throw e)))))))

(deftest test-has-error-and-terminates-normally
  (let [e (Exception.)]
    (is (= [42 [e]]
           (transact (fn [trans]
                       (swap! trans error e)
                       42))))))

(defn closeable [closed-atom index]
  (reify
    java.io.Closeable
    (close [_] (swap! closed-atom #(conj % index)))))

(deftest test-has-error-and-has-open-and-throws-exception
  (let [closed (atom #{})
        opened-1 (closeable closed 1)
        opened-2 (closeable closed 2)
        error-1 (Exception. "1")
        error-2 (Exception. "2")
        res (transact
             (fn [trans]
               (swap! trans error error-1)
               (swap! trans open opened-1)
               (swap! trans open opened-2)
               (swap! trans close opened-1)
               (throw error-2)))]
    (is (= [nil [error-1 error-2]] res))
    (is (= #{1 2} @closed))))

(deftest test-with-closeables-closes-after-invoke
  (let [closed (atom #{})
        opened-1 (closeable closed 1)
        opened-2 (closeable closed 2)
        res (transact
             (fn [trans]
               (with-closeables trans [opened-1 opened-2]
                 (fn [] [42 (:opened @trans)]))))]
    (is (= #{1 2} @closed))
    (is (= [[42 #{opened-1 opened-2}] []] res))))

(deftest test-close-after-finish
  (let [some-error (Exception. "error")
        opened (java.io.ByteArrayOutputStream.)
        results (fn [e]
                  (let [errors-promise (promise)
                        trans (-> {:opened #{}, :errors []}
                                  (open opened)
                                  ((if e #(error % e) identity))
                                  (finish errors-promise nil))]
                    [(boolean (realized? errors-promise))
                     (count (:opened trans))
                     (dissoc (close trans opened) :errors-promise)
                     @errors-promise]))]
    (are [e]
      (= [false
          1
          {:opened #{}, :errors (if e [e] [])}
          (if e [e] [])]
         (results e))
      some-error
      nil)))
