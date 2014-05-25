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

(ns hesokuri.source-def-test
  (:require [hesokuri.git :as git])
  (:use clojure.test
        hesokuri.source-def
        hesokuri.testing.data
        hesokuri.testing.temp))

(def ^:dynamic *host-to-path* {"host" "/path"})

(deftest test-validation
  (are [bad-def has-error]
    (= has-error (boolean (validation bad-def)))
    [[:host-to-path *host-to-path*]] true
    {:host-to-path *host-to-path*} false
    {} true
    {1 2, 3 4} true
    {"foo" "foo", :host-to-path *host-to-path*} true
    {:foo :foo, :host-to-path *host-to-path*} false
    {:host-to-path *host-to-path*, nil 42} true
    {:host-to-path *host-to-path*, false 42} true
    {"host" ""} true
    {"" "path"} true
    {"okay" "okay"} false
    {:missing-host-to-path []} true
    {:host-to-path *host-to-path* :live-edit-branches []} true
    {:host-to-path *host-to-path* :live-edit-branches {:only []}} true
    {:host-to-path *host-to-path* :live-edit-branches {:only #{}}} false
    {:host-to-path *host-to-path* :live-edit-branches {:only #{""}}} true
    {:host-to-path *host-to-path* :unwanted-branches ["foo"]} true
    {:host-to-path *host-to-path* :unwanted-branches #{}} false
    {:host-to-path *host-to-path* :unwanted-branches #{""}} true
    {:host-to-path *host-to-path* :unwanted-branches #{"foo"}} false
    {:host-to-path *host-to-path* :unwanted-branches {}} false
    {:host-to-path *host-to-path* :unwanted-branches {"foo" *hash-a*}} true
    {:host-to-path *host-to-path* :unwanted-branches {"foo" [*hash-a*]}} false
    {:host-to-path *host-to-path* :unwanted-branches {"foo" "bar"}} true))

(deftest test-kind-and-validation--on-valid-defs
  (are [def result]
       (do (is (nil? (validation def)))
           (is (= result (#'hesokuri.source-def/kind def))))
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

(deftest test-unwanted-branch
  (are [def branch-name branch-hash result]
    (= result (boolean (unwanted-branch? def branch-name branch-hash)))
    {"peer" "path"} "peer" *hash-a* false
    {"peer" "path"} "path" *hash-a* false
    {:host-to-path *host-to-path*} "foo" *hash-a* false
    {:unwanted-branches #{}} "foo" *hash-a* false
    {:unwanted-branches #{"foo"}} "foo" *hash-a* true
    {:unwanted-branches #{"a" "b" "c" "d"}} "c" *hash-a* true
    {:unwanted-branches {"foo" [*hash-a*]}} "master" *hash-a* false
    {:unwanted-branches {"master" [*hash-a*]}} "master" *hash-b* false
    {:unwanted-branches {"master" [*hash-a* *hash-b*]}} "master" *hash-a* true
    {:unwanted-branches {"master" [*hash-a* *hash-b*]}} "master" *hash-b* true))
