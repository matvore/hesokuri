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
  (:require [clojure.java.io :refer [file]]
            [clojure.string :refer [split trim]]
            [clojure.tools.logging :as ctl]
            [hesokuri.git :as git]
            [hesokuri.util :refer :all]
            [hesokuri.watcher :as watcher]))

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
         (-> (git/invoke "git" (git/args dir ["rev-parse"]))
             :exit
             zero?)]
     (if existing-bare
       (assoc self :bare true :init true)
       (let [init-args `["init" ~@(if bare ["--bare"] []) ~(str dir)]
             res-sum (git/invoke-with-summary "git" init-args)]
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
    (git/invoke-with-summary "git" (git/args (git-dir repo) args))))

(defn working-area-clean
  "Returns true if this repo's working area is clean, or it is bare. It is clean
  if there are no untracked files, unstaged changes, or uncommitted changes."
  [{:keys [bare init] :as repo}]
  {:pre [init]}
  (or bare
      (let [[status] (invoke-git repo ["status" "--porcelain"])]
        (and (= 0 (:exit status))
             (= "" (:out status))))))

(defn branches
  "Returns a map of refs/heads branches to their hashes."
  [repo]
  {:pre [(:init repo)]}
  (git/branches (git-dir repo)))

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
  (git/log (invoke-git repo ["branch" (if force "-D" "-d") branch-name]))
  nil)

(defn hard-reset
  "Performs a hard reset to the given ref. Returns 0 for success, non-zero for
  failure."
  [{:keys [init] :as repo} ref]
  {:pre [(string? ref) init]}
  (git/log (invoke-git repo ["reset" "--hard" ref])))

(defn rename-branch
  "Renames the given branch, allowing overwrites if specified. Returns 0 for
  success, non-zero for failure."
  [{:keys [init] :as repo} from to allow-overwrite]
  {:pre [(string? from) (string? to) init]}
  (git/log (invoke-git repo ["branch" (if allow-overwrite "-M" "-m") from to])))

(defn fast-forward?
  "Equivalent to hesokuri.git/fast-forward? but for repo objects."
  [repo & args]
  {:pre [(:init repo)]}
  (apply git/fast-forward? (git-dir repo) args))

(defn push-to-branch
  "Performs a push. Returns 0 for success, non-zero for failure."
  [repo peer-repo local-ref remote-branch allow-non-ff]
  {:pre [(string? peer-repo) (string? local-ref) (string? remote-branch)
         (:init repo)]}
  (git/log (invoke-git repo `("push" ~peer-repo
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
