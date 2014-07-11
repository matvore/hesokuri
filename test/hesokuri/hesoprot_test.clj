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

(ns hesokuri.hesoprot-test
  (:require [clojure.test :refer :all]
            [hesokuri.git :as git]
            [hesokuri.hesoprot :refer :all]
            [hesokuri.testing.ssh :refer :all]
            [hesokuri.testing.temp :refer :all]))

(deftest test-pushing
  (with-temp-repo [server-git-dir]
    (with-temp-repo [client-git-dir]
      (make-first-commit client-git-dir)
      (test-connection
       (fn [in out err]
         (respond {"foosrc" server-git-dir} in out))
       (fn [in out err acc]
         (let [pres (push "foosrc" client-git-dir
                          ["master:master_hesokr_client"] out in)]
           [false
            (if (zero? pres)
              :ok
              [:not-ok pres])])))
      (is (= {"master_hesokr_client" "807edf48041693d978ca374fe34ea761dd68df2e"}
             (git/branches server-git-dir))))))
