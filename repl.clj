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

;;;; This file is for storing code that helps in working with Hesokuri within
;;;; the REPL, code that performs common debug operations, and code that will
;;;; probably move into the real app but is still experimental or is not
;;;; thoroughly unit tested.

(require '[clojure.java.io :as cjio])
(require '[clojure.string :as cstr])
(require '[clojure.tools.logging :as ctl])
(require '[hesokuri.git :as git])
(require '[hesokuri.transact :as transact])

(use 'clojure.repl)

(defn read-tree*
  "Reads a tree object recursively and lazily from a Git repository. Returns a
sequence of tree entries similar to those returned by read-tree. However, each
(sub)tree entry (the entry itself being a sequence) has at least one extra item:
the result of read-tree* for that subtree. The value is in a lazy-seq, so it
will not be read from the repository until it is accessed."
  ([git git-dir tree-hash] (read-tree* git git-dir tree-hash (atom nil)))
  ([git git-dir tree-hash trans]
     (let [git-args (git/args git-dir ["cat-file" "tree" (str tree-hash)])
           [_ stdout :as cat-tree] (git/invoke-streams git git-args)]
       (swap! trans transact/open stdout)
       (concat
        (for [[type _ hash :as info] (git/read-tree stdout)]
          (concat info
                  (lazy-seq
                   (when (= type "40000")
                     [(read-tree* git git-dir hash trans)]))))
        (lazy-seq (swap! trans transact/close stdout)
                  (git/if-error cat-tree
                                #(throw (ex-info "git failed to read tree."
                                                 {:summary %})))
                  nil)))))

(defn write-tree*
  "Writes a tree into a Git repository. The tree is a structure similar to that
returned by read-tree*, but for each blob or tree that must be updated, the hash
has been replaced with nil. For each blob that must be updated, a
clojure.java.io/IOFactory instance is after the nil value. For each tree that
must be updated, a the original tree structure (usually after the hash) has been
replaced with a different one of the same format. This function returns the Hash
of the tree that was written."
  [git git-dir tree]
  (let [cat-tree-args (git/args git-dir ["hash-object" "-w" "--stdin" "-t"
                                         "tree"])
        [stdin stdout :as cat-tree] (git/invoke-streams git cat-tree-args)]
    (try
      (doseq [[type name hash replace] tree]
        (let [new-hash
              (cond
               hash hash
               (= type "40000") (write-tree* git git-dir replace)
               :else (git/write-blob git git-dir replace))]
          (git/write-tree-entry stdin [type name new-hash])))
      (.close stdin)
      (first [(cstr/trim (slurp stdout))
              (git/if-error cat-tree
                            #(throw (ex-info "git failed to write tree."
                                             {:summary %})))])
      (finally (.close stdin)
               (.close stdout)))))

(comment
  ;; Recursively read a tree at the given hash
  (let [hash "a9ef38249198b290668ebfb2e29ca5d528a88661"]
    (pprint (read-tree* "git" "/Users/matvore/hesokuri/.git" hash)))

  (write-tree* "git" "/Users/matvore/hesokuri/.git"
               [["100644" "foo" nil (.getBytes "at top dir\n")]
                ["40000" "bar" nil [["100644" "file" nil
                                     (.getBytes "in dir\n")]]]]))
