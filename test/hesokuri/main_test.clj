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

(ns hesokuri.main-test
  (:import [java.io FileInputStream ObjectInputStream])
  (:require [clojure.java.io :as cjio]
            [clojure.test :refer :all]
            [hesokuri.git :as git]
            [hesokuri.main :refer :all]
            [hesokuri.testing.data :refer :all]
            [hesokuri.testing.temp :refer :all]))

(defn do-cmd-init [prot-port-str hesoroot]
  (cmd-init "machine-name"
            prot-port-str
            (cjio/file hesoroot "hesobase-git-dir")
            (cjio/file hesoroot "ssh-key-file")
            "not actually a key!"
            (git/author 1234)))

(deftest test-cmd-init-bad-port-number
  (is (= ["Invalid port number: 'forty-two'\n" *err* 1]
         (do-cmd-init "forty-two" (create-temp-dir)))))

(deftest test-cmd-init-already-exists
  (are [existing-file]
    (let [[msg & result-rest]
          ,(do (let [hesoroot (create-temp-dir)]
                 (spit (cjio/file hesoroot existing-file)
                       "dummy file that causes an error")
                 (do-cmd-init "42" hesoroot)))]
      (and (= [*err* 1] result-rest)
           (.startsWith msg "File already exists")
           (not= -1 (.indexOf msg (str existing-file)))))
    "hesobase-git-dir"
    "ssh-key-file"))

(deftest test-cmd-successful
  (let [hesoroot (create-temp-dir)
        [msg & result-rest] (do-cmd-init 1011 hesoroot)]
     (is (not= -1 (.indexOf msg "Initialized hesobase in: ")))
     (is (not= -1 (.indexOf msg "hesobase-git-dir")))
     (is (not= -1 (.indexOf msg (str hesoroot))))
     (is (= [*out* 0] result-rest))

     ;; Make sure the hesobase repo was created and has a commit.
     (is (= {:out "* master\n" :err "" :exit 0}
            (git/invoke "git" (git/args (cjio/file hesoroot "hesobase-git-dir")
                                        ["branch"]))))
     ;; Make sure key pair was saved.
     (is (= "not actually a key!"
            (-> (cjio/file hesoroot "ssh-key-file")
                FileInputStream.
                ObjectInputStream.
                .readObject)))))
