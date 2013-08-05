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

(def ^:dynamic peer-repo (->PeerRepo "repohost" "/repopath"))

(def ^:dynamic peer)

(defn new-test-peer []
  (binding [*letmap-omitted-key* ::omitted]
    (new-peer)))

(defn peer-agent [] (-> peer ::omitted :self))

(defn snapshot [& args]
  (let [snapshot ((peer :snapshot))]
    (if args (apply snapshot args) snapshot)))

(defn push []
  ((peer :push)
   "/local-path"
   peer-repo
   "branch-name"
   "hash"
   [["push-branch" :push-arg]])
  (await-for 3000 (peer-agent))
  (is (nil? (agent-error (peer-agent)))))

(defn accessible-args []
  [(:host peer-repo) (snapshot :timeout-for-ping)])

(defn push-but-fail-ping []
  (binding [peer (new-test-peer)]
    (with-redefs [accessible (mock {(accessible-args) [false]})
                  current-time-millis (mock {[] [42 43 44 45]})]
      (doseq [_ (range 4)] (push))
      (is (= 42 (snapshot :last-fail-ping-time))))))

(deftest retrying-unresponsive-peer
  (binding [*letmap-omitted-key* ::omitted
            peer (new-test-peer)]
    (push-but-fail-ping)
    (with-redefs [accessible (mock {(accessible-args) [true]})

                  current-time-millis
                  (mock {[] [(+ 46 (snapshot :minimum-retry-interval))]})

                  sh-print (constantly 0)]
      (push)
      (is (= "hash"
             ((snapshot :pushed) ["/local-path" "branch-name"])))
      (is (nil? (snapshot :last-fail-ping-time))))))

(deftest clear-fail-ping-even-when-failing-push
  (binding [peer (new-test-peer)]
    (push-but-fail-ping)
    (with-redefs [accessible (mock {(accessible-args) [true]})

                  current-time-millis
                  (mock {[] [(+ 46 (snapshot :minimum-retry-interval))]})

                  sh-print (constantly 1)]
      (push)
      (is (= {} (snapshot :pushed)))
      (is (nil? (snapshot :last-fail-ping-time))))))
