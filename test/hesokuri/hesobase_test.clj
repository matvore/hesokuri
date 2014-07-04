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
  (are [timestamp cmd args path]
    (= [(cons "log" path)
        [timestamp cmd args]
        [timestamp cmd args]]
       [(log-blob-path timestamp cmd args)
        (log-blob-time+cmd+args path)
        (log-blob-time+cmd+args (cons "log" path))])

    0x123456789abcdef0 "do-stuff" [1 2 3]
    ,(concat (map str (seq "123456789abcdef0"))
             ["do-stuff" (%-encode "[1 2 3]")])
    0x12345 "other-stuff" 456
    ,(concat (repeat 11 "0")
             (map str (seq "12345"))
             ["other-stuff" "456"])))

(deftest command-names-do-not-need-%-encoding
  (let [names (keys cmd-map)]
    (is (= (map %-encode names) names))))

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
    (is (= "8194030113dcab557f9455cfe3ce516a7562eb0d" commit-hash))
    (transact/transact
     (fn [trans]
       (is (= [["tree" "367b7cfeff7d0a855b3478c130fbdc36855d0269"]
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
           (git/add-blob (log-blob-path 42 "should-be-ignored" ["1" "2" "3"])
                         "")
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
           (git/add-blob ["source" "bacon" "unwanted" "used" *hash-b*] "")
           (git/add-blob (log-blob-path 42 "should-be-ignored" ["1" "2" "3"])
                         ""))
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

(deftest test-apply-log-blob
  (let [cmd-map
        ,{"add-blob-1" (fn [tree dir] (git/add-blob [dir] "blob-1" tree))
          "add-blob-2" (fn [tree dir] (git/add-blob [dir] "blob-2" tree))}]
    (are [tree blob-path blob-data result]
      (= result (apply-log-blob tree [blob-path nil blob-data] cmd-map))

      [] (log-blob-path 42 "add-blob-1" ["foo"]) ""
      (->> (git/add-blob ["foo"] "blob-1")
           (git/add-blob (log-blob-path 42 "add-blob-1" ["foo"]) ""))

      [["100644" "blob" nil "blob"]] (log-blob-path 43 "add-blob-2" ["foo"]) ""
      (->> [["100644" "blob" nil "blob"]]
           (git/add-blob ["foo"] "blob-2")
           (git/add-blob (log-blob-path 43 "add-blob-2" ["foo"]) ""))

      [] (log-blob-path 44 "add-blob-1" ["dir"]) "bam!"
      (git/add-blob (log-blob-path 44 "add-blob-1" ["dir"]) "bam!"))))

(deftest test-log-tree
  (are [tree result]
    (= result (log-tree tree))
    [] nil
    (git/add-blob ["log" "foo"] "blob") (git/add-blob ["foo"] "blob")))

(deftest test-merge-trees
  (let [fcmd (comp first cmd)
        merge-base (->> (fcmd "add-peer" 42 ["m1" "5555" *key-str-a*] [])
                        (fcmd "add-peer" 43 ["m2" "5556" *key-str-b*]))]
    (are [tree1 tree2 result]
      (= result (merge-trees tree1 tree2 merge-base))

      (fcmd "add-peer" 45 ["m3" "5557" *key-str-c*] merge-base)
      (fcmd "new-source" 44 ["the-source"] merge-base)
      (->> merge-base
           (fcmd "new-source" 44 ["the-source"])
           (fcmd "add-peer" 45 ["m3" "5557" *key-str-c*]))

      merge-base merge-base merge-base

      (fcmd "new-source" 44 ["the-source"] merge-base)
      merge-base
      (fcmd "new-source" 44 ["the-source"] merge-base)

      (->> merge-base
           (fcmd "add-peer" 44 ["m3" "5557" *key-str-c*])
           (fcmd "new-source" 46 ["the-source-1"]))
      (->> merge-base
           (fcmd "new-source" 45 ["the-source-2"])
           (fcmd "add-peer" 47 ["m4" "5557" *key-str-d*]))
      (->> merge-base
           (fcmd "add-peer" 44 ["m3" "5557" *key-str-c*])
           (fcmd "new-source" 45 ["the-source-2"])
           (fcmd "new-source" 46 ["the-source-1"])
           (fcmd "add-peer" 47 ["m4" "5557" *key-str-d*])))))
