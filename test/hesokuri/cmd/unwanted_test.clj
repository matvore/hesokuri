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

(ns hesokuri.cmd.unwanted-test
  (:require [clojure.java.io :as cjio]
            [clojure.string :as cstr]
            [clojure.test :refer :all]
            [hesokuri.cmd.unwanted :refer :all]
            [hesokuri.config :as config]
            [hesokuri.env :as env]
            [hesokuri.git :as git]
            [hesokuri.testing.data :refer :all]
            [hesokuri.testing.temp :refer :all]
            [hesokuri.util :refer :all]))

(deftest test-invoke-bad-cwd
  (let [cfg {:sources [{"me" "/bar"}]}]
    (with-redefs [env/heso-cfg-file (temp-file-containing (pretty-printed cfg))
                  env/local-identity (constantly "me")
                  env/startup-dir (cjio/file "/foo")]
      (is (= [(str "Current directory (/foo) is not in any source in the "
                   "configuration.\n")
              *err* 1]
             (invoke "branch-name"))))))

(deftest test-invoke-no-matching-branches
  (with-temp-repo [git-dir]
    (make-first-commit git-dir "refs/heads/branch")
    (let [cfg {:sources [{"me" (str git-dir), "other" "/foo-bar"}]}]
      (with-redefs [env/heso-cfg-file (temp-file-containing (pretty-printed cfg))
                    env/local-identity (constantly "me")
                    env/startup-dir (cjio/file git-dir "sub-dir")]
        (is (= ["No branches with name 'unmatching-name'\n"
                *err* 1]
               (invoke "unmatching-name")))))))

(defn check-invoke [git-dir cfg-file final-unwanted-shas]
  (make-first-commit git-dir "refs/heads/unmatch")
  (git/change git-dir
              "refs/heads/unmatch"
              #(git/add-blob ["unmatch"] "unmatch\n" %)
              *commit-tail*)

  (make-first-commit git-dir "refs/heads/branch")
  (make-first-commit git-dir "refs/heads/branch_hesokr_other")
  (git/change git-dir "refs/heads/branch_hesokr_other"
              #(git/add-blob ["foo"] "foo-text\n" %)
              *commit-tail*)
  (with-redefs [env/heso-cfg-file cfg-file
                env/local-identity (constantly "me")
                env/startup-dir (cjio/file git-dir "sub-dir")]
    (is (= ["" *out* 0] (invoke "branch")))
    (is (= final-unwanted-shas
           (->> [:sources 0 :unwanted-branches "branch"]
                (get-in (config/from-file env/heso-cfg-file))
                set)))))

(deftest test-invoke-no-existing-unwanted-commits
  (with-temp-repo [git-dir]
    (check-invoke
     git-dir
     (-> {:sources [{"me" (str git-dir), "other" "/foo-bar"}]}
         pretty-printed
         temp-file-containing)
     #{"09af13549fa508cda6d238da95376017ecf42ff3"
       *first-commit-hash*})))

(deftest test-invoke-has-existing-unwanted-commits
  (with-temp-repo [git-dir]
    (check-invoke
     git-dir
     (-> {:sources [{:host-to-path {"me" (str git-dir)
                                    "other" "/foo-bar"}
                     :unwanted-branches {"branch" [(thash abc)]}}]}
         pretty-printed
         temp-file-containing)
     #{"09af13549fa508cda6d238da95376017ecf42ff3"
       *first-commit-hash*
       (thash abc)})))

(deftest test-invoke-commits-new-config
  (with-temp-repo [git-dir]
    (with-temp-repo [cfg-dir _ true]
      (let [cfg-git-ctx {:hesokuri.git/git-dir (cjio/file cfg-dir ".git")
                         :hesokuri.git/work-tree cfg-dir}
            orig-cfg {:sources [{"me" (str git-dir), "other" "/foo-bar"}
                                {"me" (str cfg-dir), "other" "/hesocfg"}]}
            cfg-file (cjio/file cfg-dir "cfg")]
        (spit cfg-file (pretty-printed orig-cfg))
        (git/invoke+log
         cfg-git-ctx "add" [(str cfg-file)])
        (git/invoke+log
         cfg-git-ctx "commit" ["-m" "Commit original configuration"])
        (check-invoke
         git-dir
         cfg-file
         #{"09af13549fa508cda6d238da95376017ecf42ff3" *first-commit-hash*})
        (let [log-out (-> (git/invoke cfg-git-ctx "log" ["--oneline"])
                          :out
                          cstr/trim
                          (cstr/split #"\n"))]
          (is (= 2 (count log-out))
              log-out)
          (is (.endsWith (second log-out)
                         " Commit original configuration")
              log-out)
          (is (.endsWith (first log-out)
                         " Mark branch unwanted: branch")
              log-out))))))
