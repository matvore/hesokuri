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

(ns hesokuri.git-test
  (:require clojure.java.io)
  (:use clojure.test
        hesokuri.git
        hesokuri.testing.temp)
  (:import [hesokuri.git ArrayBackedHash]))

(defn cycle-bytes [b count] (byte-array (take count (cycle b))))
(defn cycle-bytes-hash [b] (new ArrayBackedHash (cycle-bytes b 20)))

(deftest test-hex-char-val
  (are [ch exp] (= exp (hex-char-val ch))
       \space nil
       0 nil
       \0 0
       \5 5
       \9 9
       \a 10
       \f 15))

(deftest test-full-hash
  (are [hash exp] (is (= exp (full-hash? hash)))
       "a00000000000000000000000000000000000000b" true
       "a00000000000000000000000000000000000000" false
       "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" false
       "                                        " false
       "1234123412341234123412341234123412341234" true))

(deftest test-ArrayBackedHash-toString
  (is (= "a00000000000000000000000000000000000000b"
         (-> (concat [0xa0] (repeat 18 0) [0x0b])
             byte-array
             ArrayBackedHash.
             .toString))))

(deftest test-ArrayBackedHash-hashCode
  (is (= 0xabcdabcd
         (-> (concat [0xab 0xcd 0xab 0xcd] (repeat 16 0xff))
             byte-array
             ArrayBackedHash.
             .hashCode
             (bit-and (long 0xffffffff))))))

(deftest test-ArrayBackedHash-equals
  (are [cyc1 cyc2 expect]
      (= expect (= (cycle-bytes-hash cyc1) (cycle-bytes-hash cyc2)))
    [0xa0 0xb0] [0xa0 0xb0] true
    [0x88 0x77] [0x88 0x77 0x88] false
    [0x88 0x77 0x88 0x77] [0x88 0x77 0x88 0x77 0x66] false))

(defn byte-stream [bytes]
  (new java.io.ByteArrayInputStream (byte-array bytes)))

(deftest test-read-hash
  (are [source-cycle source-count]
      (let [source-bytes (cycle-bytes source-cycle source-count)
            source-stream (byte-stream source-bytes)
            next-byte (or (get source-bytes 20) -1)
            [hash lack] (read-hash source-stream)

            expected-bytes
            (byte-array (take 20 (concat source-bytes (repeat 0))))]
        (is (= (new ArrayBackedHash expected-bytes) hash))
        (is (= (- 20 (min 20 (count source-bytes))) lack))
        ;; Make sure read-hash does not read more bytes than needed.
        (is (= next-byte (.read source-stream))))
    [0] 0
    [1] 20
    [2 3 4] 21
    [5 6 7] 19
    [8 9] 10))

(deftest test-parse-hash
  (let [chars (cycle [\a \0 \5 \f \b \9 \2 \8 \7])
        parsed-bytes (cycle [0xa0 0x5f 0xb9 0x28 0x7a 0x05 0xfb 0x92 0x87])]
    (are [length trailing res-bytes]
      (let [chars (map int (concat (take length chars) trailing))
            stream (byte-stream chars)
            used-chars (min 40 length)
            res (->> (repeat 0)
                     (concat (map int res-bytes))
                     (take 20)
                     (map unchecked-byte)
                     byte-array
                     ArrayBackedHash.)
            lacking (- 40 used-chars)
            term (nth chars used-chars -1)
            remaining (nth chars (+ 1 used-chars) -1)]
        (is (= [res lacking term] (parse-hash stream)))
        (is (= remaining (.read stream))))
      0 [\space] []
      1 [\space] [0xa0]
      10 [0 \a] (take 5 parsed-bytes)
      21 [\A \b] (concat (take 10 parsed-bytes) [0x50])
      30 [] (take 15 parsed-bytes)
      35 [\A \A] (concat (take 17 parsed-bytes) [0x80])
      40 [] (take 20 parsed-bytes)
      40 [\K] (take 20 parsed-bytes)
      41 [] (take 20 parsed-bytes))))

(defn tree-entry-bytes [entry-type filename hash-cycle-bytes]
  (concat (.getBytes (str entry-type " " filename "\u0000") "UTF-8")
          (cycle-bytes hash-cycle-bytes 20)))

(deftest test-read-tree-entry
  (let [entry-bytes (tree-entry-bytes "12345" "filename" [0x01 0x02])]
    (loop [source-len 0]
      (let [source (byte-stream (take source-len entry-bytes))]
        (if (< source-len (count entry-bytes))
          (do (is (nil? (read-tree-entry source))
                  (str "Read only first " source-len " bytes of entry"))
              (recur (inc source-len)))
          (is (= ["12345" "filename" (cycle-bytes-hash [1 2])]
                 (read-tree-entry source))))))))

(def entry-1-bytes (tree-entry-bytes "1" "file1" [0x01 0x10]))
(def entry-2-bytes (tree-entry-bytes "2" "file2" [0x02 0x20]))

(deftest test-read-tree
  (let [bytes (concat entry-1-bytes entry-2-bytes)
        entries [(read-tree-entry (byte-stream entry-1-bytes))
                 (read-tree-entry (byte-stream entry-2-bytes))]]
    (are [entry-count byte-count]
        (= (take entry-count entries)
           (read-tree (byte-stream (take byte-count bytes))))
      0 0
      0 20
      1 (count entry-1-bytes)
      1 (+ 20 (count entry-2-bytes))
      2 (+ (count entry-1-bytes) (count entry-2-bytes)))))

(deftest test-write-tree-entry
  (are [expected-bytes entry]
       (let [baos (new java.io.ByteArrayOutputStream)]
         (write-tree-entry baos entry)
         (is (= expected-bytes (into [] (.toByteArray baos)))))
       entry-1-bytes ["1" "file1" (cycle-bytes-hash [0x01 0x10])]
       entry-2-bytes ["2" "file2" (cycle-bytes-hash [0x02 0x20])]))

(def person "John Doe <jdoe@google.com> 1398561813 -0700")

(deftest test-read-commit
  (let [hash-1 (cycle-bytes-hash [1 2 3])
        hash-2 (cycle-bytes-hash [4 5 6])
        msg "heading\n\ndetails"]
    (are [result commit-text]
      (is (= result (-> (apply str commit-text)
                        (.getBytes "UTF-8")
                        java.io.ByteArrayInputStream.
                        read-commit)))
      [["tree" hash-1]
       ["parent" hash-2]
       ["author" person]
       ["committer" person]
       [:msg msg]]
      ["tree " hash-1 "\n"
       "parent " hash-2 "\n"
       "author " person "\n"
       "committer " person "\n\n"
       msg]

      [["tree" hash-1]]
      ["tree " hash-1 "\n"
       "parent " "01234"]

      [["tree" hash-1]]
      ["tree " hash-1 "\n"
       "parent"]

      []
      [])))

(defn commit-entry-string [e]
  (let [baos (new java.io.ByteArrayOutputStream)]
    (write-commit-entry baos e)
    (new String (.toByteArray baos) "UTF-8")))

(deftest test-write-commit-entry
  (let [hash-1 (cycle-bytes-hash [1 2 3])]
    (is (= "\nmsg title\n\ndetails"
           (commit-entry-string [:msg "msg title\n\ndetails"])))
    (is (= (str "parent " hash-1 "\n")
           (commit-entry-string ["parent" hash-1])))
    (is (= (str "author " person "\n")
           (commit-entry-string ["author" person])))))

(deftest test-default-git (is (git? default-git)))

(deftest test-git-false
  (are [x] (not (git? x))
       42
       "git"
       {}
       {:not-path "git"}
       {:path 100}
       {:path ""}
       {:path "git" :extra-key "not allowed"}))

(deftest test-git-true
  (are [x] (git? x)
       {:path "git"}
       {:path "/home/jdoe/bin/my-git"}))

(deftest test-invoke-result-false
  (are [x] (not (invoke-result? x))
       ""
       #{}
       {}
       {}
       {:exit 0 :out "a"}
       {:out "a" :err "a"}
       {:out "a" :err "a"}
       {:exit 0 :out "a" :err 42}
       {:exit 0 :out "a" :err "a" :extra-key "not allowed"}))

(deftest test-invoke-result-true
  (are [x] (invoke-result? x)
       {:exit 0 :out "a" :err "b"}
       {:exit -1 :out "" :err " "}))

(deftest test-args?-false
  (are [x] (not (args? x))
       0
       {}
       {"a" "b"}
       [:a :b]
       [" " nil]))

(deftest test-args?-true
  (are [x] (args? x)
       '()
       (lazy-seq ["a" "b" "c"])
       [""]
       ["init"]
       ["rev-parse"]
       '("checkout" "branch")))

(deftest test-args
  (is (= ["--git-dir=foodir" "a" "b" "c"]
         (args "foodir" ["a" "b" "c"]))))

(deftest test-invoke
  (with-temp-repo [repo-dir git-dir-flag]
    (let [rev-parse-result (invoke default-git [git-dir-flag "rev-parse"])]
      (is (= rev-parse-result {:err "" :out "" :exit 0}))
      (is (invoke-result? rev-parse-result)))))

(deftest test-invoke-streams-empty-err
  (with-temp-repo [repo-dir git-dir-flag]
    (let [[in out result]
          (invoke-streams
           default-git [git-dir-flag "hash-object" "-w" "--stdin"])]
      (spit in "hello\n")
      (is (not (realized? result)))
      (.close in)
      (is (= "ce013625030ba8dba906f756967f9e9ca394464a\n" (slurp out)))
      (is (= {:err "" :exit 0}) @result))))

(deftest test-invoke-streams-err
  (with-temp-repo [repo-dir git-dir-flag]
    (let [[_ _ result]
          (invoke-streams
           default-git [git-dir-flag "cat-file" "-t" "1234567"])]
      (is (= {:err "fatal: Not a valid object name 1234567\n" :exit 128}
             @result)))))

(deftest test-summary
  (let [err "[stderr contents]"
        out "[stdout contents]"
        exit 96
        result (summary ["arg1!" "?arg2"] {:err err :out out :exit exit})
        expected-substrs [err out (str exit) "git arg1! ?arg2"]]
    (doseq [substr expected-substrs]
      (is (not= -1 (.indexOf result substr)) substr))))

(deftest test-invoke-with-summary
  (let [result (invoke-with-summary default-git ["--version"])]
    (is (invoke-result? (first result)))
    (is (string? (second result)))
    (is (= 2 (count result)))))
