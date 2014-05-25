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

(ns hesokuri.hesobase-test
  (:import [clojure.lang ExceptionInfo])
  (:require [clojure.java.io :as cjio]
            [hesokuri.git :as git]
            [hesokuri.transact :as transact])
  (:use clojure.test
        hesokuri.hesobase
        hesokuri.testing.data
        hesokuri.testing.temp))

(deftest test-init
  (let [git-dir (cjio/file (create-temp-dir) "hesobase.git")
        port 1011
        author (git/author 42)
        commit-hash (init git-dir "machine-name" port *key-str* author)]
    (is (= "328a2fa32a1260180969fed77ca13ab76ebf476e" commit-hash))
    (transact/transact
     (fn [trans]
       (is (= [["tree" "aa056ab7f8a70cee3d56350483cc3c92bd92523a"]
               ["author" author]
               ["committer" author]
               [:msg "executing hesobase/init\n"]]
              (map #(take 2 %) (git/read-commit git-dir commit-hash trans))))))
    (let [branch-args (git/args git-dir ["branch" "-v" "--no-abbrev"])]
      (is (= [["master" commit-hash]]
             (git/branch-and-hash-list
              (:out (git/invoke "git" branch-args))))))))

(deftest test-init-master-branch-already-exists
  (with-temp-repo [git-dir]
    (make-first-commit git-dir)
    (try
      (init git-dir "machine-name" 1011 *key-str* (git/author 42))
      (throw (ex-info "Should have thrown." {}))
      (catch ExceptionInfo e
        (is (not= (.indexOf (:err (ex-data e)) *first-commit-hash*) -1)
            e)))))
