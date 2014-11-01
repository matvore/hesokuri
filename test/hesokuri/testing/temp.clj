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

(ns hesokuri.testing.temp
  (:import [java.io File FileWriter])
  (:require [hesokuri.git :as git])
  (:use clojure.test
        [clojure.java.io :only [file]]
        hesokuri.testing.data))

(defn create-temp-dir
  "Creates a temporary directory and returns a File pointing to its path."
  []
  (let [path (File/createTempFile "hesokuri-tests" nil)]
    (or (.delete path)
        (throw (IllegalStateException. (str "Could not delete " path))))
    (or (.mkdir path)
        (throw (IllegalStateException. (str "Could not create temp dir " path))))
    path))

(defn temp-file-containing
  "Creates a temporary file containing the given object (converted with str).
  Returns a java.io.File object containing the path to the file."
  [contents]
  (let [path (File/createTempFile "hesokuri-tests" nil)
        stream (FileWriter. path)]
    (.write stream (str contents))
    (.close stream)
    path))

(defmacro with-temp-repo
  "Creates a repo, binding the directory to the dir symbol and the --git-dir
flag (to pass to git when operating on the repo) to the git-dir-flag symbol."
  [[dir git-dir-flag non-bare] & body]
  (let [git-dir-flag (or git-dir-flag (gensym "git-dir-flag"))]
    `(let [bare# (not ~non-bare)
           ~dir (create-temp-dir)
           ;; _ (.makeDirectory)
           ~git-dir-flag (str "--git-dir=" (file ~dir (if bare# "" ".git")))]
       (doseq [args# [[~git-dir-flag "init"]
                      [~git-dir-flag "config" "user.name" "Hesokuri Tester"]
                      [~git-dir-flag "config" "user.email" "test@hesokuri"]]]
         (git/invoke+throw "git" args#))
       ~@body)))

(defn make-first-commit
  "Writes *first-commit* to the given repository and creates a branch
  that points to it.

  git-dir - the .git directory of the repository to commit to.
  branch-name - the full name of the branch to create, which is
      'refs/heads/master' by default."
  ([git-dir] (make-first-commit git-dir "refs/heads/master"))
  ([git-dir branch-name]
     (is (= *first-commit-hash* (git/write-commit git-dir *first-commit*)))
     (->> ["update-ref" branch-name *first-commit-hash*]
          (git/args git-dir)
          (git/invoke+throw "git"))))
