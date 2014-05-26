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
            [hesokuri.ssh :as ssh]
            [hesokuri.transact :as transact]
            [hesokuri.util :refer :all])
  (:use clojure.test
        hesokuri.hesobase
        hesokuri.testing.data
        hesokuri.testing.temp))

(deftest test-init
  (let [git-dir (cjio/file (create-temp-dir) "hesobase.git")
        port 1011
        author (git/author 42)
        commit-hash (init git-dir "machine-name" port *key-str-a* author)]
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
      (init git-dir "machine-name" 1011 *key-str-a* (git/author 42))
      (throw (ex-info "Should have thrown." {}))
      (catch ExceptionInfo e
        (is (not= (.indexOf (:err (ex-data e)) *first-commit-hash*) -1)
            e)))))

(deftest test-tree->config
  (let [alice "@l!c3"
        confidential-branch "//confidential|branch//"
        top-secret-source-name "top secret!"
        top-secret-source {:host-to-path {alice "/path"}
                           :live-edit-branches {:except #{confidential-branch}}
                           :unwanted-branches {confidential-branch [*hash-a*]}}
        mushrooms-source {:host-to-path {"bob" "C:\\mushrooms"
                                         "danielle" "/home/danielle/mushrooms"}
                          :live-edit-branches {:except #{"poisonous"}}}
        bacon-source {:host-to-path {"bob" "C:\\bacon"}
                      :live-edit-branches {:only #{"crisp" "hot"}}
                      :unwanted-branches {"used" [*hash-a* *hash-b*]}}]
    (are [tree config]
      (= config (tree->config tree))
      (->> (git/add-blob ["peer" "bob" "port"] "42")
           (git/add-blob ["peer" "bob" "key"] *key-str-a*)
           (git/add-blob ["peer" "conan" "port"] "43")
           (git/add-blob ["peer" "conan" "key"] *key-str-b*))
      {:sources []
       :host-to-key {"bob" (ssh/public-key *key-str-a*)
                     "conan" (ssh/public-key *key-str-b*)}
       :host-to-port {"bob" 42, "conan" 43}
       :source-name-map {}}

      (->> (git/add-blob ["peer" "bob" "port"] "42")
           (git/add-blob ["peer" "bob" "source" "mushrooms"] "C:\\mushrooms")
           (git/add-blob ["peer" "bob" "source" "bacon"] "C:\\bacon")
           (git/add-blob ["peer" "danielle" "source" "mushrooms"]
                         "/home/danielle/mushrooms")
           (git/add-blob ["source" "mushrooms" "live-edit" "except" "poisonous"]
                         "")
           (git/add-blob ["source" "bacon" "live-edit" "only" "crisp"] "")
           (git/add-blob ["source" "bacon" "live-edit" "only" "hot"] "")
           (git/add-blob ["source" "bacon" "unwanted" "used" *hash-a*] "")
           (git/add-blob ["source" "bacon" "unwanted" "used" *hash-b*] ""))
      {:sources [bacon-source mushrooms-source]
       :host-to-port {"bob" 42}
       :source-name-map {"bacon" bacon-source
                         "mushrooms" mushrooms-source}}

      (->> (git/add-blob ["peer" (%-encode alice) "port"] "1337")
           (git/add-blob ["peer" (%-encode alice) "key"] *key-str-a*)
           (git/add-blob ["peer" (%-encode alice) "source"
                          (%-encode top-secret-source-name)]
                         "/path")
           (git/add-blob ["source" (%-encode top-secret-source-name) "live-edit"
                          "except" (%-encode confidential-branch)]
                         "")
           (git/add-blob ["source" (%-encode top-secret-source-name) "unwanted"
                          (%-encode confidential-branch) *hash-a*]
                         ""))
      {:sources [top-secret-source]
       :host-to-port {alice 1337}
       :host-to-key {alice (ssh/public-key *key-str-a*)}
       :source-name-map {top-secret-source-name top-secret-source}})))
