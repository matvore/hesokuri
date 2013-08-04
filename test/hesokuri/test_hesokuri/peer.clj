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

(ns hesokuri.test-hesokuri.peer
  (:use clojure.test
        hesokuri.peer
        hesokuri.test-hesokuri.mock
        hesokuri.util))

(def peer-repo-eg (->PeerRepo "repohost" "/repopath"))

(deftest test-retrying-unresponsive-peer
  (binding [*letmap-omitted-key* ::omitted]
    (let [peer (new-peer)
          snapshot (fn [] ((peer :snapshot)))
          peer-agent (-> peer ::omitted :self)
          accessible-args [(:host peer-repo-eg) ((snapshot) :timeout-for-ping)]]
      (with-redefs [accessible (mock {accessible-args [false]})

                    current-time-millis
                    (mock {[] [42 43 44 45]})]
        (doseq [_ (range 4)]
          ((peer :push)
           "/local-path" peer-repo-eg "branch-name" "hash" [:try1]))
        (await-for 3000 peer-agent)
        (is (= 42 ((snapshot) :last-fail-ping-time))))
      (with-redefs [accessible (mock {accessible-args [true]})

                    current-time-millis
                    (mock {[] [(+ 46 ((snapshot) :minimum-retry-interval))]})

                    sh-print (constantly 0)]
        ((peer :push)
         "/local-path"
         peer-repo-eg
         "branch-name"
         "hash"
         [["push-branch" :push-arg]])
        (await-for 3000 peer-agent)
        (is (nil? (agent-error peer-agent)))
        (is (= "hash"
               (((snapshot) :pushed) ["/local-path" "branch-name"])))))))
