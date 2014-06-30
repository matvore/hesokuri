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
            [clojure.test :refer :all]
            [hesokuri.git :as git]
            [hesokuri.hesobase :refer :all]
            [hesokuri.ssh :as ssh]
            [hesokuri.testing.data :refer :all]
            [hesokuri.testing.temp :refer :all]
            [hesokuri.transact :as transact]
            [hesokuri.util :refer :all]))

(deftest test-log-blob-path
  (are [timestamp cmd args result]
    (= (cons "log" result) (log-blob-path timestamp cmd args))
    0x123456789abcdef0 "do-stuff" [1 2 3]
    ,(concat (map str (seq "123456789abcdef0")) [(%-encode "[1 2 3]")])
    0x12345 "other-stuff" 456
    ,(concat (repeat 11 "0") (map str (seq "12345")) ["456"])))

(deftest test-source-name?-yes
  (are [n]
    (source-name? n)
    "a"
    "Aa"
    "aA"
    "zZ"
    "-a-"
    "-aa-"
    "-_"
    "-_-"))

(deftest test-source-name?-no
  (are [n]
    (not (source-name? n))
    ""
    1
    {}
    []
    (seq "a")
    "a "
    " "
    "/"
    "\\"
    "____ _____"))

(deftest test-peer-names
  (are [tree result]
    (= result (peer-names tree))

    [["40000" "peer" nil []]]
    []

    [["40000" "some-other-dir" nil []]
     ["100644" "some-other-file" nil "blob"]]
    []

    [["40000" "peer" nil [["40000" "asdf" nil []]]]]
    ["asdf"]

    [["40000" "peer" nil [["40000" "asdf" nil []] ["40000" "ffff" nil []]]]]
    ["asdf" "ffff"]

    [["40000" "peer" nil [["40000" "a" nil []] ["40000" "b" nil []]]]]
    ["a" "b"]))

(deftest test-cmd-map--add-peer
  (are [tree machine-name port key result]
    (= result ((cmd-map "add-peer") tree machine-name port key))

    [["40000" "peer" nil [["100644" "new-peer" nil "blob"]]]]
    ,"new-peer" "1042" *key-str-a* "There is already a machine named: new-peer"
    [["40000" "peer" nil [["100644" "old-peer" nil "blob"]]]]
    ,"new-peer" "1042" *key-str-a*
    ,[["40000" "peer" nil [["100644" "old-peer" nil "blob"]
                           ["40000" "new-peer" nil
                            [["100644" "port" nil "1042"]
                             ["100644" "key" nil *key-str-a*]]]]]]))

(deftest test-cmd-map--new-source
  (let [not-yet-added-tree [["40000" "peer" nil [["40000" "a" nil []]
                                                 ["40000" "b" nil []]]]]
        already-added-tree [["40000" "peer" nil
                             [["40000" "a" nil
                               [["40000" "source" nil
                                 [["100644" "sn" nil "sn"]]]]]
                              ["40000" "b" nil
                               [["40000" "source" nil
                                 [["100644" "sn" nil "sn"]]]]]]]]]
    (are [tree source-name result]
      (= result ((cmd-map "new-source") tree source-name))

      [] "a" "No peers to add source to: a"
      [["40000" "peer" nil []]] "a" "No peers to add source to: a"
      already-added-tree "sn" "No peers to add source to: sn"
      not-yet-added-tree "sn" already-added-tree

      [["40000" "other-dir" nil
        [["100644" "other-file" nil "blob"]]]]
      "a"
      "No peers to add source to: a"

      [["40000" "peer" nil [["40000" "a" nil []]]]]
      "**"
      (str "Not a valid source name. " source-name-spec " (**)"))))

(deftest test-cmd
  (let [timestamp-ms 4221955
        log-path (log-blob-path timestamp-ms
                                "add-peer"
                                ["new-peer" "9876" *key-str-a*])]
    (is (= [(->> (git/add-blob ["peer" "new-peer" "port"] "9876")
                 (git/add-blob ["peer" "new-peer" "key"] *key-str-a*)
                 (git/add-blob log-path ""))
            ""]
           (cmd "add-peer" timestamp-ms ["new-peer" "9876" *key-str-a*] [])))
    (is (= [(git/add-blob log-path "err-msg") "err-msg"]
           (cmd "err-msg" log-path [])))))

(deftest test-init
  (let [git-dir (cjio/file (create-temp-dir) "hesobase.git")
        port 1011
        author (git/author 42)
        commit-hash (init git-dir "machine-name" port *key-str-a* author 42)]
    (is (= "b69bac9339dd313065628bd09bd5f25f8bfe53aa" commit-hash))
    (transact/transact
     (fn [trans]
       (is (= [["tree" "c0563107ac6e15d670bd6214a2ee1545a328ca9f"]
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
      (init git-dir "machine-name" 1011 *key-str-a* (git/author 42) 42)
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
