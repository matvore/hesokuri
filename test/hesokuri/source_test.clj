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

(ns hesokuri.source-test
  (:require [clojure.java.io :as cjio]
            [clojure.test :refer :all]
            [hesokuri.git :as git]
            [hesokuri.repo :as repo]
            [hesokuri.source :refer :all]
            [hesokuri.testing.data :refer :all]
            [hesokuri.testing.temp :refer :all]))

(deftest test-branches-to-delete
  (are [branches unwanted-branches ff-pairs expected-result]
       (is (= expected-result
              (set
               (#'hesokuri.source/branches-to-delete
                {:branches branches
                 :source-def {:unwanted-branches unwanted-branches}}
                #(or (= %1 %2) (ff-pairs [%1 %2]))))))
       {{:name "foo"} "foo-local-hash"
        {:name "foo", :peer "p1"} "foo-remote-hash"}
       #{}
       #{["foo-local-hash" "foo-remote-hash"]}
       #{}

       {{:name "foo"} "foo-local-hash"
        {:name "foo", :peer "p1"} "foo-remote-hash"}
       #{}
       #{["foo-remote-hash" "foo-local-hash"]}
       #{{:name "foo", :peer "p1"}}

       {{:name "foo"} "foo-local-hash"
        {:name "foo", :peer "p1"} "foo-remote-hash"}
       #{"foo"}
       #{}
       #{{:name "foo"} {:name "foo", :peer "p1"}}

       {{:name "foo", :peer "p1"} "foo-remote-hash"
        {:name "bar"} "bar-local-hash"
        {:name "bar", :peer "p1"} "bar-remote-hash"}
       #{}
       #{["foo-remote-hash" "bar-local-hash"]
         ["bar-remote-hash" "bar-local-hash"]}
       #{{:name "bar", :peer "p1"}}))

(deftest test-push-for-peer-noop-if-repo-not-on-peer
  (with-redefs [clojure.core/send-off
                (fn [& args]
                  (throw (ex-info "Should not be called." {:args args})))]
    (#'hesokuri.source/do-push-for-peer
     {:source-def {"different-host" "/different/host/path"}
      :branches {{:name "branch"} "hash"}
      :peers {"the-host" (agent {})}}
     "the-host")))

(deftest test-refresh-bare-source
  (with-temp-repo [dir git-dir-flag]
    (make-first-commit dir "refs/heads/b1")
    (make-first-commit dir "refs/heads/b1_hesokr_peer")
    (let [orig {:repo (repo/init {:dir dir})
                :source-def {}}]
      (is (= (into orig
                   {:branches {{:name "b1"} *first-commit-hash*
                               {:name "b1" :peer "peer"} *first-commit-hash*}
                    :working-area-clean true
                    :checked-out-branch nil})
             (refresh orig))))))

(deftest test-refresh-branch-checked-out
  (with-temp-repo [dir git-dir-flag true]
    (let [git-dir (cjio/file dir ".git")]
      (make-first-commit git-dir "refs/heads/b1")
      (make-first-commit git-dir "refs/heads/b1_hesokr_peer")
      (git/invoke+log git-dir "checkout" ["b1_hesokr_peer"])
      (let [orig {:repo (repo/init {:dir dir})
                  :source-def {}}]
        (is (= (into orig
                     {:branches {{:name "b1"} *first-commit-hash*
                                 {:name "b1" :peer "peer"} *first-commit-hash*}
                      :working-area-clean true
                      :checked-out-branch {:name "b1" :peer "peer"}})
               (refresh orig)))))))

(deftest test-advance-custom-advance-fn
  (with-temp-repo [dir]
    (make-first-commit dir "refs/heads/master")
    (is (= {{:name "master"} *first-commit-hash*}
           (advance {:repo (repo/init {:dir dir})
                     :source-def {}
                     :advance-fn :branches})))))

(deftest test-advance-default-advance-a
  (with-temp-repo [dir]
    (make-first-commit dir "refs/heads/b1")
    (make-first-commit dir "refs/heads/b1_hesokr_peer")
    (git/change dir "refs/heads/b1_hesokr_peer"
                #(git/add-blob ["dir" "blob"] "foo\n" %)
                *commit-tail*)
    (git/invoke+log dir "checkout" ["b1"])
    (let [source {:repo (repo/init {:dir dir})
                  :source-def {:live-edit-branches {:only #{"b1"}}}}]
      (is (= {{:name "b1"} "12c7cbcab8e2ccf0c5cd77b8ff83ccf4a53f97dc"}
             (:branches (refresh (advance source))))))))

(deftest test-advance-default-advance-c
  (with-temp-repo [dir]
    (make-first-commit dir "refs/heads/delete_me")
    (make-first-commit dir "refs/heads/b2")
    (let [source {:repo (repo/init {:dir dir})
                  :source-def {:unwanted-branches #{"delete_me"}}}]
      (is (= {{:name "b2"} *first-commit-hash*}
             (:branches (refresh (advance source))))))))
