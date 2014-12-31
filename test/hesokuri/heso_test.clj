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

(ns hesokuri.heso-test
  (:require [clojure.java.io :refer [file]]
            [clojure.test :refer :all]
            [hesokuri.heso :refer :all]
            [hesokuri.peer :as peer]
            [hesokuri.repo :as repo]
            [hesokuri.source :as source]
            [hesokuri.source-def :as source-def]
            [hesokuri.testing.data :refer :all]
            [hesokuri.testing.mock :refer :all]
            [hesokuri.testing.temp :refer :all]
            [hesokuri.util :refer :all]))

(deftest test-new-heso-make-heartbeats
  (let [initial-heartbeats #(-> *sources-eg* with-config :heartbeats)
        beats-1 (initial-heartbeats)
        beats-2 (initial-heartbeats)]
    (is (not= beats-1 beats-2))
    (is (= {} (deref beats-1)))))

(deftest test-common-sources
  (are [sources peer-names source-indexes]
       (is (= (map #(-> % *sources-eg* source-def/host-to-path) source-indexes)
              (apply #'hesokuri.heso/common-sources sources peer-names)))
       [] ["peer1"] []
       [] ["peer1" "peer2"] []
       *sources-eg* ["peer1" "peer2"] [0]
       *sources-eg* ["peer2"] [0 1]
       *sources-eg* ["peer1" "peer3"] [2 3]))

(defn- de-agentify
  "Converts agents in a structure so they can be easily compared to an expected
  value in a test case."
  [o]
  (cond
   (map? o) (into {} (for [[k v] o] [(de-agentify k) (de-agentify v)]))
   (vector? o) (into [] (for [v o] (de-agentify v)))

   (= clojure.lang.Agent (class o))
   (assoc (de-agentify @o) ::error (agent-error o))

   :else o))

(deftest test-with-simple-sources
  (with-redefs [getenv {"HESOHOST" "peer3"}]
    (let [result
          (-> *sources-eg* with-config (dissoc :heartbeats) de-agentify)

          new-peer (assoc peer/default ::error nil)
          peers {"peer1" new-peer, "peer2" new-peer, "peer4" new-peer}

          source-agents
          (into {}
                (for [[source-def-index source] (map-indexed list *sources-eg*)
                      :let [host-to-path (source-def/host-to-path source)
                            source-dir (host-to-path "peer3")]
                      :when source-dir]
                  [source-dir {:repo (repo/with-dir source-dir)
                               :source-def source
                               :peers peers
                               :local-identity "peer3"
                               ::error nil
                               :hesokuri.heso/source-def-index
                               ,source-def-index}]))]
      (is (= result
             {:config *sources-eg*
              :active false
              :local-identity "peer3"
              :peer-hostnames #{"peer1" "peer2" "peer4"}
              :peers peers
              :source-agents source-agents})))))

(deftest test-send-args-to-start-sources
  (is (= [] (#'hesokuri.heso/send-args-to-start-sources {:source-agents {}}))
      (= [[:a source/init-repo] [:a source/advance] [:a source/start-watching]
          [:b source/init-repo] [:b source/advance] [:b source/start-watching]]
         (#'hesokuri.heso/send-args-to-start-sources
          {:source-agents {"a" :a, "b" :b}}))))
