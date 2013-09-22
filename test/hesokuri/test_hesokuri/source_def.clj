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

(ns hesokuri.test-hesokuri.source-def
  (:use clojure.test
        hesokuri.source-def))

(def ^:dynamic *host-to-path* {"host" "/path"})

(deftest test-kind-fail-validity-check
  (are [bad-def]
       (thrown? AssertionError (#'hesokuri.source-def/kind bad-def))
       {}
       {"foo" "foo", :host-to-path *host-to-path*}
       {:missing-host-to-path []}))

(deftest test-kind
  (are [def result] (is (= result (#'hesokuri.source-def/kind def)))
       {:actually-valid 1 :host-to-path *host-to-path*} :extensible
       *host-to-path* :simple))

(deftest test-live-edit-branch-fail-validity-check
  (are [bad-def]
       (thrown? AssertionError (live-edit-branch? bad-def "branch-name"))
       {:host-to-path *host-to-path*, :live-edit-branches {}}
       {:host-to-path *host-to-path*, :live-edit-branches {:foo #{}}}
       {:host-to-path *host-to-path*,
        :live-edit-branches {:except #{}, :only #{}}}
       {:host-to-path *host-to-path*,
        :live-edit-branches {:except #{}, :foo #{}}}
       {:host-to-path *host-to-path*,
        :live-edit-branches {:only #{}, :foo #{}}}))

(deftest test-live-edit-branch
  (are [live-edit-branches branch-name result]
       (is (= (boolean result)
              (boolean (live-edit-branch?
                        {:host-to-path *host-to-path*
                         :live-edit-branches live-edit-branches}
                        branch-name))))
       {:except #{}} "foobar" true
       {:except #{"foobar"}} "foobar" false
       nil "foo" false
       nil "master" false
       nil "hesokuri" true
       {:only #{}} "hesokuri" false
       {:only #{"foobar"}} "foobar" true
       {:only #{"foo" "bar"}} "bar" true))
