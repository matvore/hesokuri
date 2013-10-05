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

(ns hesokuri.test-hesokuri.repo
  (:use [clojure.java.io :only [file]]
        clojure.test
        hesokuri.repo
        hesokuri.test-hesokuri.mock
        hesokuri.test-hesokuri.temp
        hesokuri.util))

(defn ff? [src-dir from-hash to-hash when-equal]
  (fast-forward? {:dir src-dir :bare false :init true}
                 from-hash
                 to-hash
                 when-equal))

(def hash-a "a00000000000000000000000000000000000000a")
(def hash-b "b00000000000000000000000000000000000000b")
(def hash-c "c00000000000000000000000000000000000000c")
(def hash-d "d00000000000000000000000000000000000000d")
(def hash-e "e00000000000000000000000000000000000000e")
(def hash-f "f00000000000000000000000000000000000000f")
(def hash-g "0100000000000000000000000000000000000010")

(deftest test-fast-forward
  (let [sh-result (fn [output] (repeat 10 {:out output :exit 0}))
        sh (mock {["git" "merge-base" hash-a hash-b :dir "/srcdir"]
                  (sh-result hash-c)
                  ["git" "merge-base" hash-b hash-a :dir "/srcdir"]
                  (sh-result hash-c)
                  ["git" "merge-base" hash-d hash-e :dir "/srcdir"]
                  (sh-result hash-e)
                  ["git" "merge-base" hash-e hash-d :dir "/srcdir"]
                  (sh-result hash-e)
                  ["git" "merge-base" hash-f hash-g :dir "/srcdir"]
                  (sh-result hash-f)})]
    (binding [*sh* sh]
      (are [from-hash to-hash when-equal res]
           (= (boolean res)
              (boolean (ff? "/srcdir" from-hash to-hash when-equal)))
           hash-a hash-b nil false
           hash-b hash-a nil false
           hash-d hash-d true true
           hash-d hash-e nil false
           hash-e hash-d nil true))))

(deftest test-delete-branch
  (let [sh-invocations (atom [])
        sh (fn [& args]
             (swap! sh-invocations #(conj % args))
             nil)]
    (binding [*sh* sh]
      (delete-branch {:dir "repodir" :init true} "byebye")
      (delete-branch {:dir "repodir2" :init true} "ohnooo" true)
      (is (= [["git" "branch" "-d" "byebye" :dir "repodir"]
              ["git" "branch" "-D" "ohnooo" :dir "repodir2"]]
             @sh-invocations)))))

(deftest test-checked-out-branch
  (are [head-file result]
       (let [git-dir (create-temp-dir)
             repo {:init true :bare true :dir (file git-dir)}]
         (spit (file git-dir "HEAD") head-file)
         (is (= result (checked-out-branch repo))))
       "ref: refs/heads/foo" "foo"
       "ref: not-local-branch" nil
       "ref: refs/heads/bar" "bar"))

(deftest test-branch-and-hash-list
  (are [branch-output expected]
       (is (= expected (#'hesokuri.repo/branch-and-hash-list
                        (apply str branch-output))))
       [""] []
       ["\n"] []
       ["\n\n"] []

       ["* a abcabcababcabcababcabcababcabcababcabcab\n"
        "  b abcabcababcabcababcabcababcabcababcabcad"]
       [["a" "abcabcababcabcababcabcababcabcababcabcab"]
        ["b" "abcabcababcabcababcabcababcabcababcabcad"]]

       ["  maint-v1.1.x                     "
        "fd9d7ad30c8bff048c630e14851e751527c774f4 Correct docstrings\n"
        "  master                           "
        "62bf79ca2ca18159f26a84a5fc307a3416592ded Make start more testable\n"
        "* use-git-command-to-enum-branches "
        "62bf79ca2ca18159f26a84a5fc307a3416592ded Make start more testable\n"]
       [["maint-v1.1.x"
         "fd9d7ad30c8bff048c630e14851e751527c774f4"]
        ["master"
         "62bf79ca2ca18159f26a84a5fc307a3416592ded"]
        ["use-git-command-to-enum-branches"
         "62bf79ca2ca18159f26a84a5fc307a3416592ded"]]

       ["invalid-branch invalidhash desc\n"
        "valid-branch dddddddddddddddddddddddddddddddddddddddd"]
       [["valid-branch" "dddddddddddddddddddddddddddddddddddddddd"]]))
