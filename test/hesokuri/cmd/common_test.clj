; Copyright (C) 2015 Google Inc.
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

(ns hesokuri.cmd.common-test
  (:require [clojure.java.io :as cjio]
            [clojure.string :as cstr]
            [clojure.test :refer :all]
            [hesokuri.cmd.common :refer :all]
            [hesokuri.config :as config]
            [hesokuri.env :as env]
            [hesokuri.git :as git]
            [hesokuri.heso :as heso]
            [hesokuri.testing.temp :refer :all]
            [hesokuri.util :refer :all]))

(defn check-update-config [cfg-file]
  (with-redefs [env/heso-cfg-file cfg-file
                env/local-identity (constantly "me")]
    (let [orig-config (config/from-file)
          new-config (assoc orig-config :comment :foo)
          heso (heso/with-config orig-config)]
      (update-config heso new-config "update config commit msg")
      (is (= new-config (config/from-file))))))

(deftest test-update-config-not-in-source
  (check-update-config
   (temp-file-containing (pretty-printed [{"foo" "/bar"}]))))

(deftest test-update-config-is-in-source
  (with-temp-repo [cfg-dir _ true cfg-git-ctx]
    (let [cfg-file (cjio/file cfg-dir "cfg")
          orig-cfg [{"me" (str cfg-dir)}]]
      (with-redefs [env/heso-cfg-file cfg-file]
        (spit cfg-file (pretty-printed orig-cfg))
        (git/invoke+throw cfg-git-ctx "add" [(str cfg-file)])
        (git/invoke+throw cfg-git-ctx "commit" ["-m" "initial commit"])
        (check-update-config cfg-file)

        (is (= "" (:out (git/invoke cfg-git-ctx "diff" []))))
        (is (= "" (:out (git/invoke cfg-git-ctx "diff" ["--cached"]))))

        (is (= 2 (-> (git/invoke cfg-git-ctx "log" ["--oneline"])
                     :out
                     (cstr/split #"\n")
                     count)))))))
