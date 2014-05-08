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

(ns hesokuri.git
  "Module that facilitates invoking git, and other Git utility code not specific
to Hesokuri logic."
  (:require clojure.java.io
            clojure.java.shell
            [hesokuri.transact :as transact])
  (:use [clojure.string :only [join split trim]]
        clojure.tools.logging
        hesokuri.util))

(declare args
         throw-if-error
         invoke-streams
         invoke-with-summary)

(defn hex-char-val
  "Returns the integral value of a hex digit for c in the range of 0-9 or a-f.
Returns nil for non hex characters and capitalized hex characters (A-F)."
  [c]
  (let [c (int c)]
    (cond
     (<= (int \0) c (int \9)) (- c (int \0))
     (<= (int \a) c (int \f)) (-> c (- (int \a)) (+ 10))
     :else nil)))

(defprotocol Hash
  (write-binary-hash [this ^java.io.OutputStream out]
    "Writes the raw binary representation of this hash to the given
OutputStream.")
  (full-hash? [this]
    "Indicates whether the data structure backing this hash is the correct size
and formatted correctly."))

(deftype ArrayBackedHash [b]
  Hash
  (write-binary-hash [_ out] (.write out b))
  (full-hash? [_] (= (count b) 20))
  Object
  (equals [o1 o2]
    (and (= (class o1) (class o2))
         (= (str o1) (str o2))))
  (toString [_]
    (apply str (map #(format "%02x" (bit-and 0xff %)) b)))
  (hashCode [_]
    ;; Our data is already a "hash code"; just use the first four bytes for a
    ;; smaller hash code.
    (-> b java.nio.ByteBuffer/wrap .getInt)))

(extend-type String
  Hash
  (write-binary-hash [s ^java.io.OutputStream out]
    (doseq [[c1 c2] (partition 2 s)
            :let [c1v (hex-char-val c1)
                  c2v (hex-char-val c2)]]
      (.write out (unchecked-byte (+ (* 16 c1v) c2v)))))
  (full-hash? [s]
    (and (= (count s) 40)
         (every? hex-char-val s))))

(defn read-hash
  "Reads SHA1 hash bytes from an InputStream into a new object supporting the
Hash protocol. Returns a Hash object containing the SHA1 hash bytes that were
read. Throws an ex-info if the EOF was reached before a full hash could be
read."
  [in]
  (let [result (byte-array 20)
        bytes-read (.read in result)]
    (when (not= 20 bytes-read)
      (throw (ex-info "Could not read a full hash."
                      {:lack (- 20 (max 0 bytes-read))})))
    (new ArrayBackedHash result)))

(defn read-blob
  "Reads a blob as a String."
  [git git-dir blob-hash]
  (let [[{:keys [exit err out]} :as res-sum]
        (invoke-with-summary
         git (args git-dir ["cat-file" "blob" (str blob-hash)]))]
    (if (and (zero? exit) (empty? err))
      out
      (throw (ex-info "git failed to read the blob."
                      {:summary (second res-sum)})))))

(defn write-blob
  "Writes a blob to the given Git repository. data represents the blob data. It
can be one of the following, checked in this order:
1. a function which, when called with an OutputStream, writes the blob data to
   that stream. The function can close or flush the stream, but it need not.
2. an argument that is passed as the first argument to clojure.java.io/copy to
   write the blob data. 'data' can be any type accepted by that function."
  [git git-dir data]
  (let [hash-blob-args (args git-dir ["hash-object" "-w" "--stdin"])
        [blob-in blob-out :as hash-blob]
        (invoke-streams git hash-blob-args)]
    (try
      (if (fn? data)
        (data blob-in)
        (clojure.java.io/copy data blob-in))
      (.close blob-in)
      (first [(trim (slurp blob-out))
              (throw-if-error hash-blob)])
      (finally (.close blob-in)
               (.close blob-out)))))

(defn read-tree-entry
  "Reads an entry from a Git tree object. Returns a sequence with at least three
elements: the type as a String (e.g. '100644', which is a file with the
permission bits set to 644), the name of the entry (e.g. README.md) and a Hash
object corresponding to the hash. If there are no entries left in the stream,
returns nil."
  [in]
  (let [[type-file-str term] (read-until in zero?)
        type-and-file (split type-file-str #" " 2)]
    (cond
     (= [type-file-str term] ["" -1]) nil
     (= -1 term)
     (throw (ex-info "Reached EOF while reading entry type and file name."
                     {:type-and-file type-and-file}))
     :else (conj type-and-file (read-hash in)))))

(defn read-tree
  "Reads a tree object recursively and lazily from a Git repository. Returns a
sequence of tree entries similar to those returned by read-tree-entry. However,
if git-dir was passed to this function, each (sub)tree entry (the entry itself
being a sequence) has at least one extra item: the result of read-tree for that
subtree. The value is in a lazy-seq, so it will not be read from the repository
until it is accessed.

If in is passed, then in cannot be used to read anything else, but the caller is
responsible for closing it."
  ([in] (take-while some? (repeatedly #(read-tree-entry in))))
  ([git-dir tree-hash trans] (read-tree "git" git-dir tree-hash trans))
  ([git git-dir tree-hash trans]
     (let [git-args (args git-dir ["cat-file" "tree" (str tree-hash)])
           [_ stdout :as cat-tree] (invoke-streams git git-args)]
       (swap! trans transact/open stdout)
       (concat
        (for [[type _ hash :as info] (read-tree stdout)]
          (concat info
                  (lazy-seq
                   (when (= type "40000")
                     [(read-tree git git-dir hash trans)]))))
        (lazy-seq (swap! trans transact/close stdout)
                  (throw-if-error cat-tree)
                  nil)))))

(defn write-tree-entry
  "Write a tree entry to an output stream."
  [^java.io.OutputStream out [type name sha]]
  (doto out
    (write-bytes type)
    (.write (int \space))
    (write-bytes name)
    (.write 0))
  (write-binary-hash sha out))

(defn write-tree
  "Writes a tree into a Git repository. The tree is a structure similar to that
returned by read-tree, but for each blob or tree that must be updated, the hash
has been replaced with nil. For each blob that must be updated, some value
accepted as the third argument to write-blob should be after the nil value. For
each tree that must be updated, the original tree structure (usually after the
hash) has been replaced with a different one of the same format. This function
returns the Hash of the tree that was written."
  ([git-dir tree] (write-tree "git" git-dir tree))
  ([git git-dir tree]
     (let [cat-tree-args (args git-dir ["hash-object" "-w" "--stdin" "-t"
                                        "tree"])
           [stdin stdout :as cat-tree] (invoke-streams git cat-tree-args)]
       (try
         (doseq [[type name hash replace] tree]
           (let [new-hash
                 (cond
                  hash hash
                  (= type "40000") (write-tree git git-dir replace)
                  :else (write-blob git git-dir replace))]
             (write-tree-entry stdin [type name new-hash])))
         (.close stdin)
         (first [(trim (slurp stdout))
                 (throw-if-error cat-tree)])
         (finally (.close stdin)
                  (.close stdout))))))

(defn read-commit
  "Reads commit information lazily from an InputStream or by invoking git.
Returns a lazyseq where each element is a sequence with at least two elements:
the key and the value. They are returned in the same order they appear in the
commit. The possible keys/values are:

KEY           VALUE
\"tree\"      Hash
\"parent\"    Hash
\"author\"    exact String containing author name and time
\"committer\" same as above, but for committer
:msg          String containing commit message

When invoked with an InputStream, this function only reads from the InputStream
and returns an equivalent lazy-seq. When invoked with a git-dir and commit-hash,
associates the read with a transaction so the stream will be closed
automatically when the transaction is over, and checks for errors in the git
process, and throws an ex-info if one is encountered."
  ([git-dir commit-hash trans] (read-commit "git" git-dir commit-hash trans))
  ([git git-dir commit-hash trans]
     (let [cat-commit-args (args git-dir ["cat-file" "commit" commit-hash])
           [_ stdout :as cat-commit] (invoke-streams git cat-commit-args)]
       (swap! trans transact/open stdout)
       (concat (read-commit stdout)
               (lazy-seq (do (swap! trans transact/close stdout)
                             (throw-if-error cat-commit)
                             nil)))))
  ([^java.io.InputStream in]
     (lazy-seq
      (let [nl (int \newline)
            sp (int \space)
            [name nt] (read-until in #{sp nl})]
        (cond
         (= [name nt] ["" -1])
         nil

         (= [name nt] ["" nl])
         [(lazy-cat [:msg] [(first (read-until in))])]

         (= nt sp)
         (let [[value vt] (read-until in #{nl})]
           (when (not= vt nl)
             (throw (ex-info "Commit field does not end with newline."
                             {:name name :value value :vt vt})))
           (cons [name value] (read-commit in)))

         :else (throw (ex-info "Unexpected pattern in commit field."
                               {:name name :nt nt})))))))

(defn write-commit-entry
  "Writes a single commit entry to the given OutputStream. A commit entry is a
name and a value and corresponds to the names and values returned by
read-commit."
  [^java.io.OutputStream out [name value]]
  (do (if (= :msg name)
        (doto out
          (.write (int \newline))
          (write-bytes value))
        (doto out
          (write-bytes name)
          (.write (int \space))
          (write-bytes (str value))
          (.write (int \newline))))
      nil))

(defn invoke-result?
  "Returns true iff x is a valid result of a call to invoke. Note that this has
nothing to do with whether the result indicates a successful invocation."
  [x]
  (and (map? x)
       (= 3 (count x))
       (integer? (:exit x))
       (string? (:out x))
       (string? (:err x))))

(defn args? [x]
  (and (or (seq? x)
           (vector? x)
           (list? x))
       (every? string? x)))

(defn args
  "Helper for building arguments when invoking git."
  [git-dir args]
  (cons (str "--git-dir=" git-dir) args))

(defn invoke
  "Invokes git with the given arguments, and returns a value in the same form as
clojure.java.shell/sh. 'git' is the Git object to use. 'args' is a sequence of
strings to pass to git."
  [git args]
  {:pre [(args? args)]}
  (apply clojure.java.shell/sh git args))

(defn invoke-streams
  "Invokes git with the given arguments. The semantics of the arguments are
identical to the invoke function. The return value is a sequence of at least
four elements: an OutputStream corresponding to stdin, an InputStream
corresponding to stdout, a future that will realize when the process
terminates, and the summary as a string. The future is a map with two keys:
:exit and :err, whose values correspond to the values of the same keys in the
invoke return value. The summary part of the returned sequence is lazy."
  [git args]
  {:pre [(args? args)]}
  (let [process (new ProcessBuilder (into [git] args))]
    (doto process
      (.redirectInput java.lang.ProcessBuilder$Redirect/PIPE)
      (.redirectOutput java.lang.ProcessBuilder$Redirect/PIPE)
      (.redirectError java.lang.ProcessBuilder$Redirect/PIPE))
    (let [process (.start process)
          finish (future
                   (let [stderr (slurp (.getErrorStream process))]
                     {:exit (.waitFor process)
                      :err stderr}))]
      (concat
       [(.getOutputStream process)
        (.getInputStream process)
        finish]
       (lazy-seq [(let [{:keys [err exit]} @finish]
                    (format "execute: %s %s\nstderr:\n%sexit: %d\n"
                            git (join " " args) err exit))])))))

(defn if-error
  "Calls a function if the result of invoke-streams indicates an error. A
non-empty stderr or a non-zero exit code indicate error. The function (f) is
called with a single argument: a human-readable summary of the invoke-streams
result. If f is called, if-error returns whatever f returns. If f is not called,
if-error returns nil."
  [[_ _ finish :as invoke-streams-res] f]
  (let [{:keys [err exit]} @finish]
    (when (or (not= 0 exit) (not (empty? err)))
      (f (nth invoke-streams-res 3)))))

(defn throw-if-error
  "Similar to if-error, but rather than executing an arbitrary function on
error, throws an ex-info whose message is the summary and the info map contains
the exit code and stderr output entries from the invoke-streams promise map."
  [[_ _ finish :as invoke-streams-res]]
  (if-error invoke-streams-res #(throw (ex-info % @finish))))

(defn summary
  "Returns a user-readable summary of the result of 'invoke' as a string."
  [args invoke-result]
  {:pre [(args? args) (invoke-result? invoke-result)]}
  (format "execute: git %s\nstderr:\n%sstdout:\n%sexit: %d\n"
          (join " " args) (:err invoke-result) (:out invoke-result)
          (:exit invoke-result)))

(defn invoke-with-summary
  "Calls invoke and returns two items in a sequence: the result of invoke
followed by a string which is the summary. The summary part of the sequence is
lazy."
  [git args]
  {:pre [(args? args)]}
  (let [result (invoke git args)]
    (cons result (lazy-seq [(summary args result)]))))

(comment
  ;; Recursively read a tree at the given hash
  (let [hash "a9ef38249198b290668ebfb2e29ca5d528a88661"]
    (transact/transact
     (fn [trans]
       (clojure.pprint/pprint
        (read-tree "/Users/matvore/hesokuri/.git" hash trans))))))
