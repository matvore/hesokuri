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
            [hesokuri.watcher :as watcher]
            clojure.tools.logging)
  (:use [clojure.java.io :only [file]]
        [clojure.string :only [split trim]]
        hesokuri.util))

(defn with-dir
  "Returns a repo object that operates through the git command-line tool."
  [dir]
  {:dir (file dir)})

(defn init
  "Initializes the repository if it does not exist. Returns a new repo object."
  [{:keys [dir init bare] :as self}]
  (cond
   init self
   :else
   (let [existing-bare
         (-> (git/invoke git/default-git (git/args dir ["rev-parse"]))
             :exit
             zero?)]
     (if existing-bare
       (assoc self :bare true :init true)
       (let [init-args `["init" ~@(if bare ["--bare"] []) ~(str dir)]
             res-sum (git/invoke-with-summary git/default-git init-args)]
         (when-not (zero? (:exit (first res-sum)))
           (throw (java.io.IOException. (second res-sum))))
         (assoc self
           :bare (boolean bare)
           :init true))))))

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
  (let [args (concat (if bare [] [(str "--work-tree=" dir)]) args)]
    (git/invoke-with-summary git/default-git (git/args (git-dir repo) args))))

(defn- log
  "Takes the result of invoke-git, logs the summary, and returns the exit code."
  [invoke-git-result]
  (let [[{:keys [exit]} summary] invoke-git-result]
    (if (zero? exit)
      (clojure.tools.logging/info summary)
      (clojure.tools.logging/warn summary))
    exit))

(defn working-area-clean
  "Returns true if this repo's working area is clean, or it is bare. It is clean
  if there are no untracked files, unstaged changes, or uncommitted changes."
  [{:keys [bare init] :as repo}]
  {:pre [init]}
  (or bare
      (let [[status] (invoke-git repo ["status" "--porcelain"])]
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
        :when (and hash (git/full-hash? hash) (not= name ""))]
    [name hash]))

(defn branches
  "Returns a map of refs/heads branches to their hashes."
  [{:keys [dir init] :as repo}]
  {:pre [init]}
  (let [res-sum (invoke-git repo ["branch" "-v" "--no-abbrev"])
        [{:keys [out exit]}] res-sum]
    ;; git-branch can return error even though some branches were read
    ;; correctly, so if there was an error just log a warning and try to parse
    ;; the output anyway.
    (when (not= 0 exit) (clojure.tools.logging/warn (second res-sum)))
    (into {} (branch-and-hash-list out))))

(defn checked-out-branch
  "Returns the name of the currently checked-out branch, or nil if no local
  branch is checked out."
  [repo]
  {:pre [(:init repo)]}
  (let [[{:keys [out exit]}]
        (invoke-git repo ["rev-parse" "--symbolic-full-name" "HEAD"])
        out (trim out)
        local-branch-prefix "refs/heads/"]
    (cond
     (not (zero? exit)) nil
     (not (.startsWith out local-branch-prefix)) nil
     :else (.substring out (count local-branch-prefix)))))

(defn delete-branch
  "Deletes the given branch. This method always returns nil. It does not throw
  an exception if the branch delete failed. 'force' indicates that -D will be
  used to delete the branch, which means it will succeed even if the branch is
  not yet merged to its upstream branch."
  [{:keys [init] :as repo} branch-name & [force]]
  {:pre [(string? branch-name) init]}
  (log (invoke-git repo ["branch" (if force "-D" "-d") branch-name]))
  nil)

(defn hard-reset
  "Performs a hard reset to the given ref. Returns 0 for success, non-zero for
  failure."
  [{:keys [init] :as repo} ref]
  {:pre [(string? ref) init]}
  (log (invoke-git repo ["reset" "--hard" ref])))

(defn rename-branch
  "Renames the given branch, allowing overwrites if specified. Returns 0 for
  success, non-zero for failure."
  [{:keys [init] :as repo} from to allow-overwrite]
  {:pre [(string? from) (string? to) init]}
  (log (invoke-git repo ["branch" (if allow-overwrite "-M" "-m") from to])))

(defn fast-forward?
  "Returns true iff the second hash is a fast-forward of the first hash. When
  the hashes are the same, returns when-equal."
  [{:keys [dir init] :as repo} from-hash to-hash when-equal]
  {:pre [(git/full-hash? from-hash) (git/full-hash? to-hash) init]}
  (if (= from-hash to-hash)
    when-equal
    (-> (invoke-git repo ["merge-base" from-hash to-hash])
        first
        :out
        trim
        (= from-hash))))

(defn push-to-branch
  "Performs a push. Returns 0 for success, non-zero for failure."
  [repo peer-repo local-ref remote-branch allow-non-ff]
  {:pre [(string? peer-repo) (string? local-ref) (string? remote-branch)
         (:init repo)]}
  (log (invoke-git repo `("push" ~peer-repo
                          ~(str local-ref ":refs/heads/" remote-branch)
                          ~@(if allow-non-ff ["-f"] [])))))

(defn watch-refs-heads-dir
  "Sets up a watcher for the refs/heads directory and returns an object like
  that returned by hesokuri.watching/watcher-for-dir. on-change-cb is a cb
  that takes no arguments and is called when a change is detected."
  [repo on-change-cb]
  {:pre [(:init repo)]}
  (watcher/for-dir (file (git-dir repo) "refs" "heads")
                   (cb [on-change-cb] [_] (cbinvoke on-change-cb))))
