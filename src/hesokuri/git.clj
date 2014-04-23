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
        hesokuri.util))

(def tree-entry-dir
  "The number stored at the start of a tree entry pointing to a directory."
  "100644")

(def tree-entry-file
  "The number stored at the start of a tree entry pointing to a file."
  "40000")

(defn read-hash
  "Reads SHA1 hash bytes from an InputStream into a new byte array. Returns a
sequence with at least two values: the byte[] containing the SHA1 hash bytes
that were read, and the number of bytes lacking to make a complete hash (will
be non-zero if EOF was reached before the end of the hash.)"
  [in]
  (let [result (byte-array 20)]
    [result (- 20 (max 0 (.read in result)))]))

(defn read-tree-entry
  "Reads an entry from a Git tree object. Returns a sequence with at least three
elements: the type as a String (e.g. 100644, or tree-entry-dir), the name of the
entry (e.g. README.md) and a byte array containing the hash. If an entry could
not be read due to EOF at any point during the read, returns nil."
  [in]
  (let [[type-file-str delim] (read-to in zero?)
        type-and-file (split type-file-str #" " 2)
        [hash-bytes lack] (read-hash in)]
    (when (and (not= -1 delim)
               (zero? lack))
      (concat type-and-file [hash-bytes]))))

(defn read-tree
  "Reads all the entries from a Git tree object, and returns a lazy sequence.
The InputStream should not be used for anything else after passing it to this
function."
  [in]
  (lazy-seq (let [entry (read-tree-entry in)]
              (when entry
                (cons entry (read-tree in))))))

(def default-git
  "A Git object which invokes the 'git' tool from the PATH."
  {:path "git"})

(defn git?
  "Returns true iff x is a valid git object."
  [x]
  (and (map? x)
       (= 1 (count x))
       (string? (:path x))
       (not (empty? (:path x)))))

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

(defn invoke
  "Invokes git with the given arguments, and returns a value in the same form as
clojure.java.shell/sh. 'git' is the Git object to use. 'args' is a sequence of
strings to pass to git."
  [git args]
  {:pre [(args? args) (git? git)]}
  (apply clojure.java.shell/sh (:path git) args))

(defn invoke-streams
  "Invokes git with the given arguments. The semantics of the arguments are
identical to the invoke function. The return value is a sequence of at least
three elements: an OutputStream corresponding to stdin, an InputStream
corresponding to stdout, and a future that will realize when the process
terminates. The future is a map with two keys: :exit and :err, whose values
correspond to the values of the same keys in the invoke return value."
  [git args]
  {:pre [(args? args) (git? git)]}
  (let [process (new ProcessBuilder (into [(:path git)] args))]
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
  {:pre [(git? git) (args? args)]}
  (let [result (invoke git args)]
    (cons result (lazy-seq [(summary args result)]))))
