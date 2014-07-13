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
            [hesokuri.testing.data :refer :all]
            [hesokuri.testing.ssh :refer :all]
            [hesokuri.testing.temp :refer :all]))

(defmacro def-pushing-test [name send-pack-args before-push after-push]
  `(deftest ~name
     (with-temp-repo [~'server-git-dir]
       (with-temp-repo [~'client-git-dir]
         ~@before-push
         (test-connection
          (fn [in# out# err#]
            (respond {"foosrc" ~'server-git-dir} in# out#))
          (fn [in# out# err# acc#]
            (let [pres# (push "foosrc" ~'client-git-dir
                              ~send-pack-args out# in#)]
              [false
               (if (zero? pres#)
                 :ok
                 [:not-ok pres#])])))
         ~@after-push))))

(def-pushing-test test-push-one-ref
  ["master:master_hesokr_client"]
  [(make-first-commit client-git-dir)]
  [(is (= {"master_hesokr_client" *first-commit-hash*}
          (git/branches server-git-dir)))])

(def-pushing-test test-push-two-refs
  ["master:master_copy"
   "master2:master2_copy"]
  [(make-first-commit client-git-dir)
   (git/invoke+throw client-git-dir "branch" ["master2"])
   (git/change client-git-dir "refs/heads/master2"
               #(git/add-blob ["dir" "blob"] "foo\n" %)
               *commit-tail*)]
  [(is (= {"master_copy" *first-commit-hash*
           "master2_copy" "12c7cbcab8e2ccf0c5cd77b8ff83ccf4a53f97dc"}
          (git/branches server-git-dir)))])
