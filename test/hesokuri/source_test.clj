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
  (:require [clojure.test :refer :all]
            [hesokuri.source :refer :all]))

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
