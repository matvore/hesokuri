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
        [clojure.string :only [trim]]
        clojure.test
        hesokuri.repo
        hesokuri.testing.mock
        hesokuri.testing.temp
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

(deftest test-invoke-git-and-fast-forward-real-repo
  (with-temp-repo [repo-dir git-dir-flag true]
    (let [repo (init {:dir repo-dir})]
      (is (= {:dir repo-dir :bare false :init true} repo))
      (is (= (file repo-dir ".git") (git-dir repo)))
      (is (.isDirectory (git-dir repo)))
      (spit (file repo-dir "file1") "contents 1")
      (let [[add-res-1] (invoke-git repo ["add" (str (file repo-dir "file1"))])
            [commit-res-1] (invoke-git repo ["commit" "-m" "first commit"])
            [rev-parse-res-1] (invoke-git repo ["rev-parse" "HEAD"])
            commit-1-hash (trim (:out rev-parse-res-1))]
        (is (= 0 (:exit add-res-1)))
        (is (= "" (:err add-res-1)))
        (is (= 0 (:exit commit-res-1)))
        (is (= 0 (:exit rev-parse-res-1)))
        (is (= "" (:err rev-parse-res-1)))
        (is (full-hash? commit-1-hash))
        (spit (file repo-dir "file2") "contents 2")
        (let [[add-res-2]
              (invoke-git repo ["add" (str (file repo-dir "file2"))])
              [commit-res-2]
              (invoke-git repo ["commit" "-m" "second commit"])
              [rev-parse-res-2]
              (invoke-git repo ["rev-parse" "HEAD"])
              commit-2-hash
              (trim (:out rev-parse-res-2))]
          (is (= 0 (:exit rev-parse-res-2)))
          (is (= "" (:err rev-parse-res-2)))
          (is (full-hash? commit-2-hash))
          (is (true? (fast-forward? repo commit-1-hash commit-2-hash :equal)))
          (is (not
               (fast-forward? repo commit-2-hash commit-1-hash :equal))))))))

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

(deftest test-working-area-clean-bare
  (is (working-area-clean {:dir (file "/ignored") :bare true :init true})))

(deftest test-working-area-clean-real-repo
  (with-temp-repo [repo-dir git-dir-flag true]
    (let [repo (init {:dir repo-dir})]
      (is (working-area-clean repo))
      (spit (file repo-dir "file1") "contents 1")
      (is (not (working-area-clean repo)))
      (invoke-git repo ["add" (str (file repo-dir "file1"))])
      (is (not (working-area-clean repo)))
      (invoke-git repo ["commit" "-m" "commit"])
      (is (working-area-clean repo)))))

(deftest test-branches-and-delete-branch-real-repo
  (with-temp-repo [repo-dir git-dir-flag true]
    (let [repo (init {:dir repo-dir})]
      (is (= {} (branches repo)))
      (spit (file repo-dir "file1") "contents 1")
      (invoke-git repo ["add" (str (file repo-dir "file1"))])
      (is (= {} (branches repo)))
      (invoke-git repo ["commit" "-m" "commit"])
      (let [first-commit-hash
            (-> (invoke-git repo ["rev-parse" "HEAD"]) first :out trim)]
        (is (= {"master" first-commit-hash} (branches repo)))
        (invoke-git repo ["checkout" "-b" "work"])
        (is (= {"master" first-commit-hash
                "work" first-commit-hash}
               (branches repo)))
        (spit (file repo-dir "file2") "contents 2")
        (invoke-git repo ["add" (str (file repo-dir "file2"))])
        (invoke-git repo ["commit" "-m" "commit 2"])
        (let [second-commit-hash
              (-> (invoke-git repo ["rev-parse" "HEAD"]) first :out trim)]
          (is (= {"master" first-commit-hash
                  "work" second-commit-hash}
                 (branches repo)))
          (invoke-git repo ["checkout" "master"])
          (delete-branch repo "work")
          (is (= {"master" first-commit-hash
                  "work" second-commit-hash}
                 (branches repo)))
          (delete-branch repo "work" true)
          (is (= {"master" first-commit-hash}
                 (branches repo)))
          (invoke-git repo ["checkout" "-b" "work2"])
          (delete-branch repo "master")
          (is (= {"work2" first-commit-hash}
                 (branches repo))))))))

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

(deftest test-hard-reset
  (with-temp-repo [repo-dir git-dir-flag true]
    (let [repo (init {:dir repo-dir})
          created-file (file repo-dir "file1")]
      (spit created-file "contents 1")
      (invoke-git repo ["add" (str created-file)])
      (invoke-git repo ["commit" "-m" "commit"])
      (spit created-file "contents 2")
      (is (= 0 (hard-reset repo "HEAD")))
      (is (= "contents 1" (slurp created-file)))
      (is (= 0 (hard-reset repo "HEAD")))
      (is (= "contents 1" (slurp created-file))))))

(deftest test-checked-out-branch-and-rename-branch-real-repo
  (with-temp-repo [repo-dir git-dir-flag true]
    (let [repo (init {:dir repo-dir})]
      (spit (file repo-dir "file1") "contents 1")
      (invoke-git repo ["add" (str (file repo-dir "file1"))])
      (invoke-git repo ["commit" "-m" "commit"])
      (is (= "master" (checked-out-branch repo)))
      (invoke-git repo ["checkout" "-b" "work"])
      (is (= "work" (checked-out-branch repo)))
      (invoke-git repo ["checkout" "-b" "work2"])
      (is (= "work2" (checked-out-branch repo)))
      (is (= 0 (rename-branch repo "master" "new-master" false)))
      (is (= #{"new-master" "work" "work2"} (.keySet (branches repo))))
      (is (not= 0 (rename-branch repo "new-master" "work" false)))
      (is (= #{"new-master" "work" "work2"} (.keySet (branches repo))))
      (is (= 0 (rename-branch repo "new-master" "work" true)))
      (is (= #{"work" "work2"} (.keySet (branches repo)))))))

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
