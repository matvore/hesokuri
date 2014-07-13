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

(ns hesokuri.repo-test
  (:require [clojure.java.io :as cjio]
            [clojure.string :refer [trim]]
            [clojure.test :refer :all]
            [hesokuri.git :as git]
            [hesokuri.repo :refer :all]
            [hesokuri.testing.mock :refer :all]
            [hesokuri.testing.temp :refer :all]
            [hesokuri.util :refer :all]))

(def invoke-git git/invoke-with-summary)

(deftest test-init-existing-bare-repo
  (with-temp-repo [repo-dir git-dir-flag false]
    (let [repo (init {:dir repo-dir})]
      (is (= {:dir repo-dir
              :init true
              :bare true
              :hesokuri.git/git-dir repo-dir
              :hesokuri.git/work-tree nil}
             repo))
      (is (not (.isDirectory (cjio/file repo-dir ".git")))))))

(deftest test-init-create-bare-repo
  (let [repo-dir (create-temp-dir)
        repo (init {:dir repo-dir :bare true})]
    (is (= {:dir repo-dir
            :init true
            :bare true
            :hesokuri.git/git-dir repo-dir
            :hesokuri.git/work-tree nil} repo))
    (is (not (.isDirectory (cjio/file repo-dir ".git"))))
    (is (.isFile (cjio/file repo-dir "HEAD")))))

(deftest test-init-create-non-bare-repo
  (let [repo-dir (create-temp-dir)
        git-dir (cjio/file repo-dir ".git")
        repo (init {:dir repo-dir})]
    (is (= {:dir repo-dir
            :bare false
            :init true
            :hesokuri.git/git-dir git-dir
            :hesokuri.git/work-tree repo-dir}
           repo))
    (is (.isDirectory git-dir))
    (is (= {:exit 0 :out "" :err ""}
           (first (invoke-git repo "rev-parse" []))))))

(deftest test-invoke-git-and-fast-forward-real-repo
  (with-temp-repo [repo-dir git-dir-flag true]
    (let [repo (init {:dir repo-dir})]
      (is (= {:dir repo-dir
              :bare false
              :init true
              :hesokuri.git/work-tree repo-dir
              :hesokuri.git/git-dir (cjio/file repo-dir ".git")}
             repo))
      (is (= (cjio/file repo-dir ".git") (git/git-dir repo)))
      (is (.isDirectory (git/git-dir repo)))
      (spit (cjio/file repo-dir "file1") "contents 1")
      (let [[add-res-1] (invoke-git repo "add" [(str (cjio/file repo-dir "file1"))])
            [commit-res-1] (invoke-git repo "commit" ["-m" "first commit"])
            [rev-parse-res-1] (invoke-git repo "rev-parse" ["HEAD"])
            commit-1-hash (trim (:out rev-parse-res-1))]
        (is (= 0 (:exit add-res-1)))
        (is (= "" (:err add-res-1)))
        (is (= 0 (:exit commit-res-1)))
        (is (= 0 (:exit rev-parse-res-1)))
        (is (= "" (:err rev-parse-res-1)))
        (is (git/full-hash? commit-1-hash))
        (spit (cjio/file repo-dir "file2") "contents 2")
        (let [[add-res-2]
              ,(invoke-git repo "add" [(str (cjio/file repo-dir "file2"))])
              [commit-res-2]
              ,(invoke-git repo "commit" ["-m" "second commit"])
              [rev-parse-res-2]
              ,(invoke-git repo "rev-parse" ["HEAD"])
              commit-2-hash
              ,(trim (:out rev-parse-res-2))]
          (is (= 0 (:exit rev-parse-res-2)))
          (is (= "" (:err rev-parse-res-2)))
          (is (git/full-hash? commit-2-hash))
          (is (true? (git/fast-forward? repo commit-1-hash commit-2-hash
                                        :equal)))
          (is (false? (git/fast-forward? repo commit-2-hash commit-1-hash
                                         :equal))))))))

(deftest test-working-area-clean-bare
  (is (working-area-clean {:dir (cjio/file "/ignored") :bare true :init true})))

(deftest test-working-area-clean-real-repo
  (with-temp-repo [repo-dir git-dir-flag true]
    (let [repo (init {:dir repo-dir})]
      (is (working-area-clean repo))
      (spit (cjio/file repo-dir "file1") "contents 1")
      (is (not (working-area-clean repo)))
      (invoke-git repo "add" [(str (cjio/file repo-dir "file1"))])
      (is (not (working-area-clean repo)))
      (invoke-git repo "commit" ["-m" "commit"])
      (is (working-area-clean repo)))))

(deftest test-branches-and-delete-branch-real-repo
  (with-temp-repo [repo-dir git-dir-flag true]
    (let [repo (init {:dir repo-dir})]
      (is (= {} (git/branches repo)))
      (spit (cjio/file repo-dir "file1") "contents 1")
      (invoke-git repo "add" [(str (cjio/file repo-dir "file1"))])
      (is (= {} (git/branches repo)))
      (invoke-git repo "commit" ["-m" "commit"])
      (let [first-commit-hash
            ,(-> (invoke-git repo "rev-parse" ["HEAD"]) first :out trim)]
        (is (= {"master" first-commit-hash} (git/branches repo)))
        (invoke-git repo "checkout" ["-b" "work"])
        (is (= {"master" first-commit-hash
                "work" first-commit-hash}
               (git/branches repo)))
        (spit (cjio/file repo-dir "file2") "contents 2")
        (invoke-git repo "add" [(str (cjio/file repo-dir "file2"))])
        (invoke-git repo "commit" ["-m" "commit 2"])
        (let [second-commit-hash
              ,(-> (invoke-git repo "rev-parse" ["HEAD"]) first :out trim)]
          (is (= {"master" first-commit-hash
                  "work" second-commit-hash}
                 (git/branches repo)))
          (invoke-git repo "checkout" ["master"])
          (delete-branch repo "work")
          (is (= {"master" first-commit-hash
                  "work" second-commit-hash}
                 (git/branches repo)))
          (delete-branch repo "work" true)
          (is (= {"master" first-commit-hash}
                 (git/branches repo)))
          (invoke-git repo "checkout" ["-b" "work2"])
          (delete-branch repo "master")
          (is (= {"work2" first-commit-hash}
                 (git/branches repo))))))))

(deftest test-delete-branch
  (let [git-invocations (atom [])
        invoke-mock (fn [& args]
                      (swap! git-invocations #(conj % args))
                      1)
        repo-1 {:dir "repodir" :init true}
        repo-2 {:dir "repodir2" :init true}]
    (with-redefs [hesokuri.git/invoke+log invoke-mock]
      (delete-branch repo-1 "byebye")
      (delete-branch repo-2 "ohnooo" true)
      (is (= [[repo-1 "branch" ["-d" "byebye"]]
              [repo-2 "branch" ["-D" "ohnooo"]]]
             @git-invocations)))))

(deftest test-hard-reset
  (with-temp-repo [repo-dir git-dir-flag true]
    (let [repo (init {:dir repo-dir})
          created-file (cjio/file repo-dir "file1")]
      (spit created-file "contents 1")
      (invoke-git repo "add" [(str created-file)])
      (invoke-git repo "commit" ["-m" "commit"])
      (spit created-file "contents 2")
      (is (= 0 (hard-reset repo "HEAD")))
      (is (= "contents 1" (slurp created-file)))
      (is (= 0 (hard-reset repo "HEAD")))
      (is (= "contents 1" (slurp created-file))))))

(deftest test-checked-out-branch-and-rename-branch-real-repo
  (with-temp-repo [repo-dir git-dir-flag true]
    (let [repo (init {:dir repo-dir})]
      (spit (cjio/file repo-dir "file1") "contents 1")
      (invoke-git repo "add" [(str (cjio/file repo-dir "file1"))])
      (invoke-git repo "commit" ["-m" "commit"])
      (is (= "master" (checked-out-branch repo)))
      (invoke-git repo "checkout" ["-b" "work"])
      (is (= "work" (checked-out-branch repo)))
      (invoke-git repo "checkout" ["-b" "work2"])
      (is (= "work2" (checked-out-branch repo)))
      (is (= 0 (rename-branch repo "master" "new-master" false)))
      (is (= #{"new-master" "work" "work2"} (.keySet (git/branches repo))))
      (is (not= 0 (rename-branch repo "new-master" "work" false)))
      (is (= #{"new-master" "work" "work2"} (.keySet (git/branches repo))))
      (is (= 0 (rename-branch repo "new-master" "work" true)))
      (is (= #{"work" "work2"} (.keySet (git/branches repo)))))))

(deftest test-checked-out-branch-new-repo
  (with-temp-repo [repo-dir git-dir-flag true]
    (let [repo (init {:dir repo-dir})]
      (is (nil? (checked-out-branch repo))))))

(deftest test-checked-out-branch
  (are [git-invoke-result result]
       (let [repo {:init true :bare false :dir (cjio/file "/fake-repo")}
             invoke-mock
             ,(mock {[repo "rev-parse" ["--symbolic-full-name" "HEAD"]]
                    [[git-invoke-result "summary"]]})]
         (with-redefs [hesokuri.git/invoke-with-summary invoke-mock]
           (is (= result (checked-out-branch repo)))))
       {:out "refs/heads/foo\n" :err "" :exit 0} "foo"
       {:out "not-local-branch\n" :err "" :exit 0} nil
       {:out "refs/heads/bar\n" :err "" :exit 0} "bar"
       {:out "refs/heads/master\n" :err "" :exit 128} nil))

(deftest test-push-to-branch
  (let [work-tree (cjio/file "/srcdir")
        repo {:dir work-tree
              :init true
              :bare false
              :hesokuri.git/work-tree work-tree
              :hesokuri.git/git-dir (cjio/file work-tree ".git")}
        test-peer "test-peer-repo"
        local-ref "test-local-ref"
        remote-branch "test-remote-branch"
        from-to-ref (str local-ref ":refs/heads/" remote-branch)
        invoke-mock (mock {[repo "push" [test-peer from-to-ref "-f"]] [42]
                           [repo "push" [test-peer from-to-ref]] [44]})]
    (with-redefs [hesokuri.git/invoke+log invoke-mock]
      (is (= 42 (push-to-branch repo test-peer local-ref remote-branch true)))
      (is (= 44 (push-to-branch repo test-peer local-ref remote-branch
                                false))))))
