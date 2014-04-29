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
            clojure.java.shell)
  (:use [clojure.string :only [join split]]
        clojure.tools.logging
        hesokuri.util))

(declare args
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
Hash protocol. Returns a sequence with at least two values: the Hash object
containing the SHA1 hash bytes that were read, and the number of bytes lacking
to make a complete hash (will be non-zero if EOF was reached before the end of
the hash.)"
  [in]
  (let [result (byte-array 20)]
    [(new ArrayBackedHash result) (- 20 (max 0 (.read in result)))]))

(defn read-blob
  "Reads a blob as a String. Returns nil if any error occurred."
  [git git-dir blob-hash]
  (let [[{:keys [exit err out]} :as res-sum]
        (invoke-with-summary
         git (args git-dir ["cat-file" "blob" (str blob-hash)]))]
    (if (and (zero? exit) (empty? err))
      out
      (do (error (second res-sum))
          nil))))

(defn read-tree-entry
  "Reads an entry from a Git tree object. Returns a sequence with at least three
elements: the type as a String (e.g. '100644', which is a file with the
permission bits set to 644), the name of the entry (e.g. README.md) and a Hash
object corresponding to the hash. If an entry could not be read due to EOF at
any point during the read, returns nil."
  [in]
  (let [[type-file-str term] (read-until in zero?)
        type-and-file (split type-file-str #" " 2)
        [hash lack] (read-hash in)]
    (when (and (not= -1 term)
               (zero? lack))
      (concat type-and-file [hash]))))

(defn read-tree
  "Reads all the entries from a Git tree object, and returns a lazy sequence.
The InputStream should not be used for anything else after passing it to this
function."
  [in]
  (lazy-seq (let [entry (read-tree-entry in)]
              (when entry
                (cons entry (read-tree in))))))

(defn write-tree-entry
  "Write a tree entry to an output stream."
  [^java.io.OutputStream out [type name sha]]
  (doto out
    (write-bytes type)
    (.write (int \space))
    (write-bytes name)
    (.write 0))
  (write-binary-hash sha out))

(defn read-commit
  "Reads commit information lazily from the given InputStream. Returns a lazyseq
Where each element is a sequence with at least two elements: the key and the
value. They are returned in the same order they appear in the commit. The possible
keys/values are:
KEY           VALUE
\"tree\"      Hash
\"parent\"    Hash
\"author\"    exact String containing author name and time
\"committer\" same as above, but for committer
:msg          String containing commit message
"
  [^java.io.InputStream in]
  (lazy-seq
   (let [nl (int \newline)
         sp (int \space)
         [name nt] (read-until in #{sp nl})]
     (cond
      (= [name nt] ["" nl])
      [(lazy-cat [:msg] [(first (read-until in))])]

      (= nt sp)
      (let [[value vt] (read-until in #{nl})]
        (when (= vt nl)
          (cons [name value] (read-commit in))))))))

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
three elements: an OutputStream corresponding to stdin, an InputStream
corresponding to stdout, and a future that will realize when the process
terminates. The future is a map with two keys: :exit and :err, whose values
correspond to the values of the same keys in the invoke return value."
  [git args]
  {:pre [(args? args)]}
  (let [process (new ProcessBuilder (into [git] args))]
    (doto process
      (.redirectInput java.lang.ProcessBuilder$Redirect/PIPE)
      (.redirectOutput java.lang.ProcessBuilder$Redirect/PIPE)
      (.redirectError java.lang.ProcessBuilder$Redirect/PIPE))
    (let [process (.start process)]
      [(.getOutputStream process)
       (.getInputStream process)
       (future
         (let [stderr (slurp (.getErrorStream process))]
           {:exit (.waitFor process)
            :err stderr}))])))

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
