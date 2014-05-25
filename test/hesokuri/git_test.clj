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
  (:require [clojure.java.io :as cjio]
            [hesokuri.transact :as transact])
  (:use [clojure.string :only [trim]]
        clojure.test
        hesokuri.git
        hesokuri.testing.data
        hesokuri.testing.mock
        hesokuri.testing.temp)
  (:import [clojure.lang ExceptionInfo]
           [java.io ByteArrayOutputStream]
           [hesokuri.git ArrayBackedHash]))

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
       "1234123412341234123412341234123412341234" true
       *hash-a* true
       *hash-b* true
       *hash-c* true))

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
  (cjio/input-stream (byte-array bytes)))

(deftest test-read-hash
  (are [source-cycle source-count]
      (let [source-bytes (cycle-bytes source-cycle source-count)
            source-stream (byte-stream source-bytes)
            next-byte (or (get source-bytes 20) -1)
            do-read #(read-hash source-stream)

            expected-hash
            #(ArrayBackedHash.
              (byte-array (take 20 (concat source-bytes (repeat 0)))))]
        (if (< source-count 20)
          (is (thrown? ExceptionInfo (do-read)))
          (is (= (expected-hash) (do-read))))
        ;; Make sure read-hash does not read more bytes than needed.
        (is (= next-byte (.read source-stream))))
    [0] 0
    [1] 20
    [2 3 4] 21
    [5 6 7] 19
    [8 9] 10))

(deftest test-String-Hash-write-binary-hash
  (let [chars (cycle [\a \0 \5 \f \b \9 \2 \8 \7])
        parsed-bytes (cycle [0xa0 0x5f 0xb9 0x28 0x7a 0x05 0xfb 0x92 0x87])]
    (are [length]
      (let [expected (map unchecked-byte (take (quot length 2) parsed-bytes))
            hash-str (apply str (take length chars))
            baos (ByteArrayOutputStream.)
            actual (do (write-binary-hash hash-str baos)
                       (into [] (.toByteArray baos)))]
        (= expected actual))
      0
      1
      10
      21
      30
      35
      40
      41)))

(deftest read-blob-error
  (with-temp-repo [git-dir]
    (is (full-hash? *hash-a*))
    (is (thrown? ExceptionInfo (read-blob git-dir *hash-a*)))))

(deftest read-blob-success
  (with-temp-repo [git-dir]
    (let [[stdin stdout finish]
          ,(invoke-streams git-dir "hash-object" ["-w" "--stdin"])
          hash (do (spit stdin "hello Git")
                   (clojure.string/trim (slurp stdout)))]
      (is (= {:exit 0 :err ""} @finish))
      (is (= "hello Git" (read-blob git-dir hash))))))

(deftest read-blob-custom-stream-fn
  (with-temp-repo [git-dir]
    (let [blob-hash (write-blob git-dir "A")
          result (read-blob git-dir blob-hash #(.read %))]
      (is (= (int \A) result)))))

(deftest test-write-blob-success
  (with-temp-repo [git-dir]
    (let [blob-hash (write-blob git-dir "asdf")]
      (is (= "5e40c0877058c504203932e5136051cf3cd3519b" blob-hash))
      (is (= "asdf" (read-blob git-dir blob-hash))))))

(deftest test-write-blob-failure
  (try
    (write-blob (create-temp-dir) "")
    (throw (ex-info "should have thrown" {}))
    (catch clojure.lang.ExceptionInfo e
      (is (not= -1 (.indexOf (.getMessage e) "hash-object -w --stdin")))
      (is (not= -1 (.indexOf ((ex-data e) :err) "Not a git repository"))))))

(deftest test-write-blob-data-is-fn
  (with-temp-repo [git-dir]
    (let [data-fn #(.write % (.getBytes "foo" "UTF-8"))
          blob-hash (write-blob git-dir data-fn)]
      (is (= "19102815663d23f8b75a47e7a01965dcdc96468c" blob-hash))
      (is (= "foo" (read-blob git-dir blob-hash))))))

(defn tree-entry-bytes [entry-type filename hash-cycle-bytes]
  (concat (.getBytes (str entry-type " " filename "\u0000") "UTF-8")
          (cycle-bytes hash-cycle-bytes 20)))

(deftest test-read-tree-entry
  (let [entry-bytes (tree-entry-bytes "12345" "filename" [0x01 0x02])]
    (loop [source-len 0]
      (let [do-read
            #(read-tree-entry (byte-stream (take source-len entry-bytes)))]
        (cond
         (zero? source-len) (is (nil? (do-read)))

         (< source-len (count entry-bytes))
         (do (is (thrown? ExceptionInfo (do-read))
                 (str "Read only first " source-len " bytes of entry"))
             (recur (inc source-len)))

         :else (is (= ["12345" "filename" (cycle-bytes-hash [1 2])]
                      (do-read))))))))

(def entry-1-bytes (tree-entry-bytes "1" "file1" [0x01 0x10]))
(def entry-2-bytes (tree-entry-bytes "2" "file2" [0x02 0x20]))

(deftest test-read-tree
  (let [bytes (concat entry-1-bytes entry-2-bytes)
        entries [(read-tree-entry (byte-stream entry-1-bytes))
                 (read-tree-entry (byte-stream entry-2-bytes))]
        do-read #(read-tree (byte-stream (take % bytes)))]
    (are [entry-count byte-count]
      (= (take entry-count entries) (do-read byte-count))
      0 0
      1 (count entry-1-bytes)
      2 (+ (count entry-1-bytes) (count entry-2-bytes)))
    (are [ex-info-map byte-count]
      (thrown? ExceptionInfo (doall (do-read byte-count)))
      20
      (+ 20 (count entry-1-bytes)))))

(defn binary-hash [hash]
  (let [baos (ByteArrayOutputStream.)]
    (write-binary-hash hash baos)
    (ArrayBackedHash. (.toByteArray baos))))

(deftest test-read-tree
  (with-temp-repo [git-dir]
    (let [blob-1-hash (write-blob git-dir "blob-1")
          blob-2-hash (write-blob git-dir "blob-2")
          hash-args (args git-dir ["hash-object" "-w" "--stdin" "-t" "tree"])
          [subt-in subt-out :as hash-subt] (invoke-streams "git" hash-args)
          [tree-in tree-out :as hash-tree] (invoke-streams "git" hash-args)]
      (future
        (write-tree-entry subt-in ["100644" "subfoo" blob-1-hash])
        (write-tree-entry subt-in ["100644" "subbar" blob-2-hash])
        (.close subt-in))
      (write-tree-entry tree-in ["100644" "foo" blob-1-hash])
      (write-tree-entry tree-in ["100644" "bar" blob-2-hash])
      (let [subt-hash (trim (slurp subt-out))]
        (write-tree-entry tree-in ["40000" "subt" subt-hash])
        (.close tree-in)
        (let [tree-hash (trim (slurp tree-out))]
          (throw-if-error hash-subt)
          (throw-if-error hash-tree)
          (transact/transact
           (fn [trans]
             (is (= [["100644" "foo" (binary-hash blob-1-hash)]
                     ["100644" "bar" (binary-hash blob-2-hash)]
                     ["40000" "subt" (binary-hash subt-hash)
                      [["100644" "subfoo" (binary-hash blob-1-hash)]
                       ["100644" "subbar" (binary-hash blob-2-hash)]]]]
                    (read-tree git-dir tree-hash trans))))))))))

(deftest test-write-tree-entry
  (are [expected-bytes entry]
       (let [baos (ByteArrayOutputStream.)]
         (write-tree-entry baos entry)
         (is (= expected-bytes (into [] (.toByteArray baos)))))
       entry-1-bytes ["1" "file1" (cycle-bytes-hash [0x01 0x10])]
       entry-2-bytes ["2" "file2" (cycle-bytes-hash [0x02 0x20])]))

(deftest test-write-tree
  (with-temp-repo [git-dir]
    (transact/transact
     (fn [trans]
       (let [hash-1 (binary-hash "582390cd4d97e31fa8749fd8b2748a7faaca0adc")
             hash-2 (binary-hash "d63488fe511f63c7c746c15447ac3e08c31cc023")
             hash-3 (binary-hash "d2cebd4f0a9e97a48a6139d09cafdb513ad8fee3")
             hash (write-tree git-dir
                              [["100644" "foo" nil "at top dir\n"]
                               ["40000" "bar" nil [["100644" "file" nil
                                                     "in dir\n"]]]])]
         (is (= "1fd6436bd031da537877d1531ac474a939e907fb" hash))
         (is (= [["100644" "foo" hash-1]
                 ["40000" "bar" hash-2
                  [["100644" "file" hash-3]]]]
                (read-tree git-dir hash trans))))
       (let [hash (write-tree git-dir [])]
         (is (= "4b825dc642cb6eb9a060e54bf8d69288fbee4904" hash))
         (is (= [] (read-tree git-dir hash trans))))))))

(deftest test-add-blob-blob-exists
  (try
    ;; Convert result to string to defeat laziness
    (str (add-blob ["foo" "bar"] "asdf"
                   [["40000" "foo" nil
                     [["100644" "other-file" nil "asdf"]
                      ["100644" "bar" nil "asdf"]]]]))
    (throw (ex-info "Should have thrown." {}))
    (catch ExceptionInfo e
      (is (= (str "Cannot add blob or tree with name bar because a tree "
                  "or blob with that name already exists.")
             (.getMessage e)))
      (is (= {:path ["bar"]
              :blob-data "asdf"
              :tree [["100644" "bar" nil "asdf"]]}
             (ex-data e))))))

(deftest test-add-blob-directory-exists-in-place-of-blob
  (let [existing-tree ["40000" "bar" nil [["100644" "other-file" nil "asdf"]]]]
    (try
      ;; Convert result to string to defeat laziness
      (str (add-blob ["foo" "bar"] "asdf"
                     [["40000" "foo" nil
                       [["100644" "other-file" nil "asdf"]
                        existing-tree]]]))
      (throw (ex-info "Should have thrown." {}))
      (catch ExceptionInfo e
        (is (= (str "Cannot add blob or tree with name bar because a tree "
                    "or blob with that name already exists.")
               (.getMessage e)))
        (is (= {:path ["bar"]
                :blob-data "asdf"
                :tree [existing-tree]}
               (ex-data e)))))))

(deftest test-add-blob-no-tree
  (is (= [["40000" "foo" nil [["100644" "bar" nil "new blob data"]]]]
         (add-blob ["foo" "bar"] "new blob data"))))

(deftest test-add-blob-into-existing-tree
  (is (= [["40000" "foo" nil [["100644" "other-file" nil "existing blob"]
                              ["100644" "bar" nil "new blob data"]]]]
         (add-blob ["foo" "bar"] "new blob data"
                   [["40000" "foo" nil
                     [["100644" "other-file" nil "existing blob"]]]]))))

(deftest test-add-blob-two-blobs-into-same-tree
  (is (= [["40000" "a" nil [["100644" "b" nil "blob1"]
                            ["100644" "c" nil "blob2"]]]]
         (->> []
              (add-blob ["a" "b"] "blob1")
              (add-blob ["a" "c"] "blob2")))))

(deftest test-remove-entry
  (let [foo-tree ["40000" "foo" *hash-d*
                  [["100644" "bar" *hash-e* "blob content"]]]]
    (is (thrown? ExceptionInfo (into [] (remove-entry ["fake"] [foo-tree]))))
    (are [in-tree path out-tree]
      (= out-tree (remove-entry path in-tree))

      [["40000" "foo" nil
        [["100644" "bar" nil "blob content"]]]]
      ["foo"]
      []

      [foo-tree]
      ["foo" "bar"]
      []

      [["100644" "README.md" nil "docs"]
       foo-tree]
      ["foo" "bar"]
      [["100644" "README.md" nil "docs"]]

      [["100644" "README.md" nil "docs"]
       foo-tree]
      ["foo"]
      [["100644" "README.md" nil "docs"]]

      [["100644" "README.md" nil "docs"]
       foo-tree]
      ["README.md"]
      [foo-tree]

      [foo-tree]
      []
      []

      [["100644" "LICENSE" *hash-a*]
       ["40000" "should_lose_hash" *hash-b*
        [foo-tree
         ["100644" "README.md" *hash-c*]]]]
      ["should_lose_hash" "README.md"]
      [["100644" "LICENSE" *hash-a*]
       ["40000" "should_lose_hash" nil
        [foo-tree]]])))

(deftest test-get-entry
  (let [tree (->> []
                  (add-blob ["topdir" "subdir" "blob1"] "blob1-data")
                  (add-blob ["topdir" "subdir" "blob2"] "blob2-data")
                  (add-blob ["topdir" "blob3"] "blob3-data")
                  (add-blob ["topdir2" "blob4"] "blob4-data"))
        subdir (-> tree (nth 0) (nth 3) (nth 0))]
    (are [path result]
      (= result (get-entry path tree))
      [] [[] nil]
      ["non-matching1" "non-matching2"] [["non-matching1" "non-matching2"] nil]
      ["topdir"] [[] (first tree)]
      ["topdir" "unmatching"] [["unmatching"] (first tree)]
      ["topdir" "blob3" "unmatching"]
       [["unmatching"] ["100644" "blob3" nil "blob3-data"]]
      ["topdir" "subdir" "blobx"]
       [["blobx"] subdir])))

(def person "John Doe <jdoe@google.com> 1398561813 -0700")

(deftest test-read-commit
  (let [hash-1 (str (cycle-bytes-hash [1 2 3]))
        hash-2 (str (cycle-bytes-hash [4 5 6]))
        msg "heading\n\ndetails"
        do-read #(-> (apply str %)
                     (.getBytes "UTF-8")
                     cjio/input-stream
                     read-commit
                     doall)]
    (are [result commit-text]
      (= result (do-read commit-text))
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

      []
      [])
    (are [commit-text]
      (thrown? ExceptionInfo (do-read commit-text))
      ["tree " hash-1 "\n"
       "parent 01234"]

      ["tree " hash-1 "\n"
       "parent"])))

(deftest test-read-commit-transact
  (with-temp-repo [git-dir]
    (transact/transact
     (fn [trans]
       (is (= 0 (count @trans)))
       (let [c (read-commit git-dir *hash-a* trans)
             err-substr (str "cat-file commit " *hash-a*)]
         (is (= 1 (count @trans)))
         (try
           (doseq [e c] nil)
           (throw (ex-info "should have thrown" {}))
           (catch ExceptionInfo e
             (is (not= -1 (.indexOf (.getMessage e) err-substr))))))))))

(defn commit-entry-string [e]
  (let [baos (ByteArrayOutputStream.)]
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

(deftest test-author
  (let [first-time (quot (System/currentTimeMillis) 1000)
        [_ timestamp]
         (re-matches #"^Google Hesokuri <hesokuri@localhost> ([0-9]+) \+0000$"
                     (author))
        last-time (quot (System/currentTimeMillis) 1000)]
    (is (<= first-time (Long/parseLong timestamp) last-time))))

(deftest test-commit-fixes-message
  (let [a (author 1000)
        c (author 1001)]
    (are [in-msg out-msg]
      (=
       [["tree" *hash-a*]
        ["parent" *hash-b*]
        ["parent" *hash-c*]
        ["author" a]
        ["committer" c]
        [:msg out-msg]]
       (commit *hash-a* [*hash-b* *hash-c*] in-msg a c))
      "a" "a\n"
      " b" " b\n"
      "c\nd" "c\nd\n"
      "e\n\nf\n" "e\n\nf\n"
      nil "Automated commit\n"
      " \n " "Automated commit\n")))

(deftest test-write-and-read-commit-recursion
  (with-temp-repo [git-dir]
    (let [blob-hash "12f00e90b6ef79117ce6e650416b8cf517099b78"
          tree-hash-1 "a3d0b72057c8dc7f1d1c5e453ae354812b2c8465"
          hash-1 (write-commit git-dir *first-commit*)
          hash-2 "34891e130dfaf78bccad115aa9cfe18f773f4337"]
      (is (= *first-commit-hash* hash-1))
      (transact/transact
       #(is (= [["100644" "some-file" (binary-hash blob-hash)]]
               (read-tree git-dir tree-hash-1 %))))
      (let [hash-2a (write-commit
                     git-dir
                     [["tree" nil [["100644" "some-file" nil "new contents\n"]]]
                      ["parent" hash-1]
                      ["author" (author 1001)]
                      ["committer" (author 1001)]
                      [:msg "commit msg 2\n"]])
            hash-2b (write-commit
                     git-dir
                     [["tree" nil [["100644" "some-file" nil "new contents\n"]]]
                      ["parent" nil *first-commit*]
                      ["author" (author 1001)]
                      ["committer" (author 1001)]
                      [:msg "commit msg 2\n"]])]
        (is (= hash-2 hash-2a hash-2b)))
      (let [hash-3 (write-commit git-dir [["tree" tree-hash-1]
                                          ["author" (author 1002)]
                                          ["committer" (author 1002)]
                                          [:msg "commit msg 3\n"]])]
        (is (= "887282b23f1ba38df4d7a256eb4e865e5a37df5e" hash-3)))
      (transact/transact
       #(is (= [["tree" "6a3a88444b0f4519764853af5c548bec66e28639"
                 [["100644" "some-file"
                   (binary-hash "014fd71bde5393ad8b79305d1af6ec907557f828")]]]
                ["parent" hash-1
                 [["tree" tree-hash-1
                   [["100644" "some-file" (binary-hash blob-hash)]]]
                  ["author" (author 1000)]
                  ["committer" (author 1000)]
                  [:msg "commit msg\n"]]]
                ["author" (author 1001)]
                ["committer" (author 1001)]
                [:msg "commit msg 2\n"]]
               (read-commit git-dir hash-2 %)))))))

(deftest test-change
  (with-temp-repo [git-dir]
    (let [first-com-hash
           (write-commit git-dir [["tree" nil
                                   [["100644" "first_file" nil "foo"]]]
                                  ["author" (author 1)]
                                  ["committer" (author 1)]])
          set-branch-args (args git-dir ["update-ref"
                                         "refs/heads/master"
                                         first-com-hash])
          second-com-hash "8d410286cba8ae75c9c7225264132b28b5a7b7f2"]
      (is (= "7e980a15b3aac54a97aecd59b28d6cd7cffd368d" first-com-hash))
      (throw-if-error (invoke-with-summary "git" set-branch-args))
      (is (= second-com-hash
             (change git-dir "refs/heads/master"
                     #(->> %
                           (add-blob ["dir" "new-file-1"] "file contents\n")
                           (add-blob ["dir" "new-file-2"] (byte-array [1 2 3])))
                     *commit-tail*)))
      (transact/transact
       (fn [trans]
         (is (= (concat [["tree" "e91010a6f3c954b766d83296a382ceeac6fe556a"]
                         ["parent" first-com-hash]]
                        *commit-tail*)
                (map #(take 2 %)
                     (read-commit git-dir second-com-hash trans)))))))))

(deftest test-change-throws-for-non-existent-ref
  (with-temp-repo [git-dir]
    (try
      (change git-dir "refs/heads/foo" identity *commit-tail*)
      (throw (ex-info "Should have thrown." {}))
      (catch ExceptionInfo e
        (is (not= -1 (.indexOf (.getMessage e) "rev-parse refs/heads/foo")))))))

(deftest test-git-hash
  (with-temp-repo [git-dir]
    (make-first-commit git-dir)
    (is (= *first-commit-hash* (git-hash git-dir "refs/heads/master")))
    (is (thrown? ExceptionInfo (git-hash git-dir "refs/heads/oops")))))

(deftest test-fast-forward
  (let [dir "/srcdir"
        dir-flag "--git-dir=/srcdir"
        git-result (fn [output] (repeat 10 {:err "" :out output :exit 0}))
        invoke-mock (mock {["git" [dir-flag "merge-base" *hash-a* *hash-b*]]
                           (git-result *hash-c*)
                           ["git" [dir-flag "merge-base" *hash-b* *hash-a*]]
                           (git-result *hash-c*)
                           ["git" [dir-flag "merge-base" *hash-d* *hash-e*]]
                           (git-result *hash-e*)
                           ["git" [dir-flag "merge-base" *hash-e* *hash-d*]]
                           (git-result *hash-e*)
                           ["git" [dir-flag "merge-base" *hash-f* *hash-g*]]
                           (git-result *hash-f*)})]
    (with-redefs [invoke invoke-mock]
      (are [from-hash to-hash when-equal res]
           (= (boolean res)
              (boolean (fast-forward? dir from-hash to-hash when-equal)))
           *hash-a* *hash-b* nil false
           *hash-b* *hash-a* nil false
           *hash-d* *hash-d* true true
           *hash-d* *hash-e* nil false
           *hash-e* *hash-d* nil true))))

(deftest test-fast-forward-coerces-ref-to-hash
  (with-temp-repo [git-dir]
    (make-first-commit git-dir)
    (change git-dir "refs/heads/master" #(add-blob ["foo"] "foo-text\n" %)
            *commit-tail*)
    (is (= ::equal
           (fast-forward? git-dir *first-commit-hash* "HEAD~1" ::equal)))
    (is (true?
         (fast-forward? git-dir "HEAD~1" "refs/heads/master" ::equal)))))

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
    (let [rev-parse-result (invoke "git" [git-dir-flag "rev-parse"])]
      (is (= rev-parse-result {:err "" :out "" :exit 0}))
      (is (invoke-result? rev-parse-result)))))

(deftest test-invoke-streams-empty-err
  (with-temp-repo [repo-dir git-dir-flag]
    (let [[in out finish :as result]
          ,(invoke-streams repo-dir "hash-object" ["-w" "--stdin"])]
      (cjio/copy "hello\n" in)
      (is (not (realized? finish)))
      (.close in)
      (is (= "ce013625030ba8dba906f756967f9e9ca394464a\n" (slurp out)))
      (is (= {:err "" :exit 0}) @finish)
      (is (= (str "execute: git " git-dir-flag
                  " hash-object -w --stdin\nstderr:\nexit: 0"))
          (nth result 3)))))

(deftest test-invoke-streams-err
  (with-temp-repo [repo-dir git-dir-flag]
    (let [[_ _ result summary]
          ,(invoke-streams repo-dir "cat-file" ["-t" "1234567"])
          err "fatal: Not a valid object name 1234567\n"]
      (is (= {:err err :exit 128} @result))
      (is (= (str "execute: git " git-dir-flag " cat-file -t 1234567\nstderr:\n"
                  err "exit: 128\n")
             summary)))))

(deftest test-error?
  (are [invoke-res is-err]
    (= is-err (error? invoke-res))

    {:exit 0 :err ""} false
    {:exit 1 :err ""} true
    {:exit 0 :err "a"} true
    {:exit 2 :err "b"} true))

(deftest test-if-error-for-invoke-streams-result
  (let [invoke-res (fn [exit err]
                     [nil nil (future {:exit exit :err err}) "summary"])]
    (is (= "summary+extra"
           (if-error (invoke-res 13 "stderr") #(str % "+extra"))))
    (is (= 42 (if-error (invoke-res 0 "stderr") (constantly 42))))
    (is (= 42 (if-error (invoke-res 13 "") (constantly 42))))
    (is (nil? (if-error (invoke-res 0 "")
                        (fn [_] (throw (IllegalStateException.
                                        "Should not get here."))))))
    (let [res (invoke-res 0 "")]
      (is (= res (throw-if-error res))))
    (is (thrown? ExceptionInfo (throw-if-error (invoke-res 128 ""))))))

(deftest test-if-error-for-invoke-with-summary-result
  (let [invoke-res (fn [exit err]
                     [{:exit exit :err err :out "stdout"} "summary"])]
    (is (= "summary+extra"
           (if-error (invoke-res 13 "stderr") #(str % "+extra"))))
    (is (= nil (if-error (invoke-res 0 "") (constantly 42))))
    (let [res (invoke-res 0 "")]
      (is (= res (throw-if-error res))))
    (is (thrown? ExceptionInfo (throw-if-error (invoke-res 128 ""))))))

(deftest test-summary
  (let [err "[stderr contents]"
        out "[stdout contents]"
        exit 96
        result (summary ["arg1!" "?arg2"] {:err err :out out :exit exit})
        expected-substrs [err out (str exit) "git arg1! ?arg2"]]
    (doseq [substr expected-substrs]
      (is (not= -1 (.indexOf result substr)) substr))))

(deftest test-invoke-with-summary
  (let [result (invoke-with-summary "git" ["--version"])]
    (is (invoke-result? (first result)))
    (is (string? (second result)))
    (is (= 2 (count result)))))
