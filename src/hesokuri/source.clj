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

(ns hesokuri.source
  "Implementation of the source object. A valid source object has the following
  required fields:
  source-dir - The location of this source on the local disk.
  peer-dirs - a map from peer hostnames to the location of the source on that
      peer.
  peers - a map of hostnames to the corresponding peer object.
  local-identity - the hostname or IP of this system."
  (:use [clojure.java.shell :only [with-sh-dir sh]]
        [clojure.string :only [trim]]
        hesokuri.branch-name
        hesokuri.peer
        hesokuri.util
        hesokuri.watching)
  (:import [java.io File]))

(defn- git-init
  "Initializes the repository if it doesn't exist yet."
  [{:keys [source-dir] :as self}]
  (sh-print-when #(not= 0 (:exit %)) "git" "init" source-dir)
  self)

(defn- refresh
  "Updates all values of the source object based on the value of source-dir,
  which remains the same."
  [{:keys [peer-dirs peers local-identity source-dir] :as self}]
  (letmap
   [:keep [peer-dirs peers local-identity source-dir]

    ;; The .git directory of a repository as a java.io.File object, given its
    ;; parent directory. If it is a bare repository, is equal to :source-dir
    git-dir
    (let [source-dir-git (File. source-dir ".git")]
      (if (.isDirectory source-dir-git)
        source-dir-git source-dir))

    ;; Map of branch names to their hashes. The branch names should be created
    ;; by hesokuri.branch-name/->BranchName
    branches
    (into
     {} (for [head-ref-file (seq (.listFiles (File. git-dir "refs/heads")))
              :let [hash (trim (slurp head-ref-file))]]
          [(parse-branch-name (.getName head-ref-file)) hash]))

    ;; true iff there are no untracked files, unstaged changes, or
    ;; uncommitted changes.
    working-area-clean
    (or (= git-dir source-dir)
        (let [status (sh "git" "status" "--porcelain" :dir source-dir)]
          (and (= 0 (:exit status))
               (= "" (:out status)))))

    canonical-checked-out
    (= (trim (slurp (File. git-dir "HEAD")))
       (str "ref: refs/heads/" canonical-branch-name))]))

(defn- advance-b
  [{:keys [branches source-dir] :as self}]
  (doseq [branch (keys branches)
          :when
          (and (not= (:branch canonical-branch-name)
                     (:branch branch))
               (not (nil? (:peer branch))))]
    (sh-print-when #(= (:exit %) 0)
                   "git" "branch" "-d" (str branch) :dir source-dir))
  self)

(defn- advance-a
  [{all-branches :branches
    :keys [source-dir canonical-checked-out working-area-clean]
    :as self}]
  (if (and canonical-checked-out (not working-area-clean))
    self
    (loop [self self
           branches (seq all-branches)]
      (let [canonical-branch (all-branches canonical-branch-name)
            branch (first (first branches))]
        (cond
         (not branches) (advance-b self)

         (or (not= (:branch canonical-branch-name)
                   (:branch branch))
             (= canonical-branch-name branch)
             (and canonical-branch
                  (not (is-ff! source-dir canonical-branch
                               (second (first branches)) true))))
         (recur self (next branches))

         :else
         (let [branch (str branch)]
           (with-sh-dir source-dir
             (if canonical-checked-out
               (when (zero? (sh-print "git" "reset" "--hard" branch))
                 (sh-print "git" "branch" "-d" branch))
               (sh-print "git" "branch" "-M"
                         branch (str canonical-branch-name))))
           (let [self (refresh self)]
             (recur self (seq (:branches self))))))))))

(def advance
  "Checks for local branches that meet the following criteria, and performs
  the given operation, 'advancing' when appropriate.
  a) If hesokuri is not checked out, or it is checked out but the working area
     is clean, and some branch hesokuri_hesokr_* is a fast-forward of hesokuri,
     then rename the hesokuri_hesokr_* branch to hesokuri, and remove the
     existing hesokuri branch.
  b) For any two branches F and B, where F is a fast-forward of B, and B has a
     name (BRANCH)_hesokr_*, and BRANCH is not hesokuri, delete branch B."
  #(-> % git-init refresh advance-a))

(defn- do-push-for-peer
  "Push all branches as necessary to keep a peer up-to-date.
  When pushing:
  * third-party peer branches - which is any branch named *_hesokr_(HOST) where
    HOST is not me or the push destination peer, try to push to the same branch
    name, but if it fails, ignore it.
  * hesokuri - try to push to the same branch name, but if it fails, force push
    to hesokuri_hesokr_(MY_HOSTNAME).
  * local branch - which is any branch that is not hesokuri and not named in the
    form of *_hesokr_*, force push to (BRANCH_NAME)_hesokr_(MY_HOSTNAME)"
  [{:keys [peers branches local-identity source-dir peer-dirs] :as self}
   peer-host]
  (doseq [branch (keys branches)]
    (((peers peer-host) :push)
     source-dir
     (->PeerRepo peer-host (peer-dirs peer-host))
     branch
     (branches branch)
     (let [force-branch (fn [] (->BranchName (:branch branch) local-identity))]
       (cond
        (every? #(not= (:peer branch) %) [nil local-identity peer-host])
        [[branch]]

        (= canonical-branch-name branch)
        [[branch] [(force-branch) "-f"]]

        (and (not= canonical-branch-name branch)
             (not (:peer branch)))
        [[(force-branch) "-f"]]

        :else []))))
  self)

(defn push-for-peer
  "Push all branches necessary to keep one peer up-to-date."
  [self peer-host]
  (-> self git-init refresh (do-push-for-peer peer-host)))

(defn push-for-all-peers
  "Pushes all branches necessary to keep all peers up-to-date."
  [{:keys [peers] :as self}]
  (loop [self self
         peer-hosts (keys peers)]
    (cond
     (nil? peer-hosts) self

     :else (recur (push-for-peer self (first peer-hosts))
                  (next peer-hosts)))))

(defn stop-watching
  "Stops watching the file system. If not watching, this is a no-op."
  [{:keys [watcher] :as self}]
  (when watcher ((watcher :stopper)))
  (dissoc self :watcher))

(defn start-watching
  "Registers paths in this source's repo to be notified of changes so it can
  automatically advance and push"
  [self]
  (let [self-agent *agent*
        watcher (watcher-for-dir
                 (File. (:git-dir self) "refs/heads")
                 (fn [_]
                   (send self-agent advance)
                   (send self-agent push-for-all-peers)))]
    (-> self stop-watching git-init refresh (assoc :watcher watcher))))
