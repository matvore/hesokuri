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

(ns hesokuri.repo
  "An object that abstracts out the access to the git repository. This does not
  have logic that is specific to Hesokuri, so it can be easily replaced with a
  more performant git access layer later. Currently it just shells out to 'git'
  on the command line)."
  (:require [hesokuri.git :as git]
            [hesokuri.watcher :as watcher])
  (:use [clojure.java.io :only [file]]
        [clojure.string :only [split trim]]
        clojure.tools.logging
        hesokuri.util))

(defn hex-char?
  "Returns true iff the given character is a hexadecimal character: 0-9 or a-f.
  Returns false for capitalized hex characters (A-F)."
  [c]
  (let [c (int c)]
    (or (and (>= c (int \a)) (<= c (int \f)))
        (and (>= c (int \0)) (<= c (int \9))))))

(defn full-hash?
  "Returns true iff the given string looks like a full, valid hash. It does not
  have to actually exist in any repo."
  [s]
  (and (= (count s) 40)
       (every? hex-char? s)))

(defn with-dir
  "Returns a repo object that operates through the git command-line tool."
  [dir]
  {:dir (file dir)})

(defn init
  "Initializes the repository if it does not exist. Returns a new repo object."
  [{:keys [dir init] :as self}]
  (cond
   init self
   :else
   (let [init-result (*sh* "git" "init" (str dir))]
     (when-not (zero? (:exit init-result))
       (throw (java.io.IOException. (str "Failed to init repo: " init-result))))
     (assoc self
       :bare (-> (file dir ".git") .isDirectory not)
       :init true))))

(defn git-dir
  "Returns the git directory (.git) of the repo as a java.io.File object. If it
  is a bare repository, it is equal to the :dir value."
  [{:keys [dir bare init] :as repo}]
  {:pre [init]}
  (if bare dir (file dir ".git")))

(defn invoke-git
  "Invokes git with the given arguments, the correct --git-dir flag, and (if
  applicable) the correct --work-tree flag. Uses git/invoke-with-summary."
  [{:keys [dir bare] :as repo} args]
  {:pre [(every? string? args)]}
  (let [dir-flags (concat [(str "--git-dir=" (git-dir repo))]
                          (if bare [] [(str "--work-tree=" dir)]))]
    (git/invoke-with-summary git/default-git (concat dir-flags args))))

(defn working-area-clean
  "Returns true if this repo's working area is clean, or it is bare. It is clean
  if there are no untracked files, unstaged changes, or uncommitted changes."
  [{:keys [bare dir init]}]
  {:pre [init]}
  (or bare
      (let [status (*sh* "git" "status" "--porcelain" :dir dir)]
        (and (= 0 (:exit status))
             (= "" (:out status))))))

(defn- branch-and-hash-list
  "Returns a sequence of pairs. Each pair is a sequence containing two strings:
  a branch name and its hash. output is the output of the command
  'git branch -v --no-abbrev' as a string."
  [output]
  (for [line (-> output trim (split #"\n+"))
        :let [unmarked (if (.startsWith line "*") (.substring line 1) line)
              [name hash] (-> unmarked trim (split #" +" 3))]
        :when (and (full-hash? hash) (not= name ""))]
    [name hash]))

(defn branches
  "Returns a map of refs/heads branches to their hashes."
  [{:keys [dir init]}]
  {:pre [init]}
  (let [{:keys [err out exit]}
        (*sh* "git" "branch" "-v" "--no-abbrev" :dir dir)]
    ;; git-branch can return error even though some branches were read
    ;; correctly, so if there was an error just log a warning and try to parse
    ;; the output anyway.
    (when (not= 0 exit)
      (warnf (str "git-branch returned error in %s, exit code %d:\n"
                  "stdout:\n%s"
                  "stderr:\n%s")
                dir exit out err))
    (into {} (branch-and-hash-list out))))

(defn checked-out-branch
  "Returns the name of the currently checked-out branch, or nil if no local
  branch is checked out."
  [repo]
  {:pre [(:init repo)]}
  (let [head (trim (slurp (file (git-dir repo) "HEAD")))
        local-branch-prefix "ref: refs/heads/"]
    (if (.startsWith head local-branch-prefix)
      (.substring head (count local-branch-prefix))
      nil)))

(defn delete-branch
  "Deletes the given branch. This method always returns nil. It does not throw
  an exception if the branch delete failed. 'force' indicates that -D will be
  used to delete the branch, which means it will succeed even if the branch is
  not yet merged to its upstream branch."
  [{:keys [dir init]} branch-name & [force]]
  {:pre [(string? branch-name) init]}
  (sh-print-when #(= (:exit %) 0)
                 "git" "branch" (if force "-D" "-d") branch-name :dir dir)
  nil)

(defn hard-reset
  "Performs a hard reset to the given ref. Returns 0 for success, non-zero for
  failure."
  [{:keys [dir init]} ref]
  {:pre [(string? ref) init]}
  (sh-print "git" "reset" "--hard" ref :dir dir))

(defn rename-branch
  "Renames the given branch, allowing overwrites if specified. Returns 0 for
  success, non-zero for failure."
  [{:keys [dir init]} from to allow-overwrite]
  {:pre [(string? from) (string? to) init]}
  (sh-print "git" "branch" (if allow-overwrite "-M" "-m") from to :dir dir))

(defn fast-forward?
  "Returns true iff the second hash is a fast-forward of the first hash. When
  the hashes are the same, returns when-equal."
  [{:keys [dir init]} from-hash to-hash when-equal]
  {:pre [(full-hash? from-hash) (full-hash? to-hash) init]}
  (if (= from-hash to-hash)
    when-equal
    (-> (*sh* "git" "merge-base" from-hash to-hash :dir dir)
        :out
        trim
        (= from-hash))))

(defn push-to-branch
  "Performs a push. Returns 0 for success, non-zero for failure."
  [{:keys [dir init]} peer-repo local-ref remote-branch allow-non-ff]
  {:pre [(string? peer-repo) (string? local-ref) (string? remote-branch) init]}
  (apply sh-print "git" "push" peer-repo
         (str local-ref ":refs/heads/" remote-branch)
         (concat (if allow-non-ff ["-f"] [])
                 [:dir dir])))

(defn watch-refs-heads-dir
  "Sets up a watcher for the refs/heads directory and returns an object like
  that returned by hesokuri.watching/watcher-for-dir. on-change-cb is a cb
  that takes no arguments and is called when a change is detected."
  [repo on-change-cb]
  {:pre [(:init repo)]}
  (watcher/for-dir (file (git-dir repo) "refs" "heads")
                   (cb [on-change-cb] [_] (cbinvoke on-change-cb))))
