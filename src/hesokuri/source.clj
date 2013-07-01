(ns hesokuri.source
  "Implementation of the source agent."
  (:use [clojure.java.shell :only [sh]]
        [clojure.string :only [trim]]
        hesokuri.branch-name
        hesokuri.util)
  (:import [java.io File]))

(defn git-init
  "Initializes the repository if it doesn't exist yet."
  [{:keys [source-dir] :as agent}]
  (sh-print "git" "init" source-dir)
  agent)

(defn -refresh
  "Updates all values of the source agent based on the value of source-dir,
  which remains the same."
  [{:keys [source-dir]}]
  (let [source-dir-git (File. source-dir ".git")
        git-dir
        (if (.isDirectory source-dir-git)
          source-dir-git source-dir)

        branches
        (for [head-ref-file (seq (.listFiles (File. git-dir "refs/heads")))
              :let [hash (trim (slurp head-ref-file))]]
          [(parse-branch-name (.getName head-ref-file)) hash])]
    {:source-dir source-dir

     ;; The .git directory of a repository as a java.io.File object, given
     ;; its parent directory. If it is a bare repository, is equal to
     ;; :source-dir
     :git-dir git-dir

     ;; Map of branch names to their hashes. The branch names should be created
     ;; by hesokuri.branch-name/->BranchName
     :branches (into {} branches)

     ;; true iff there are no untracked files, unstaged changes, or
     ;; uncommitted changes.
     :working-area-clean
     (or (= git-dir source-dir)
         (let [status (sh "git" "status" "--porcelain" :dir source-dir)]
           (and (= 0 (:exit status))
                (= "" (:out status)))))

     :canonical-checked-out
     (= (trim (slurp (File. git-dir "HEAD")))
        (str "ref: refs/heads/" canonical-branch-name))}))

(defn -advance-a
  [{all-branches :branches
    :keys [source-dir canonical-checked-out working-area-clean]
    :as agent}]
  (if (and canonical-checked-out (not working-area-clean))
    agent
    (loop [agent agent
           branches (seq all-branches)]
      (let [canonical-branch (all-branches canonical-branch-name)
            branch (first (first branches))]
        (cond
         (not branches) (-advance-b agent)

         (or (not= (:branch canonical-branch-name)
                   (:branch branch))
             (= canonical-branch-name branch)
             (and canonical-branch
                  (not (is-ff! source-dir canonical-branch
                               (second (first branches)) true))))
         (recur agent (next branches))

         :else
         (let [branch (str branch)]
           (if canonical-checked-out
             (lint-or (sh-print "git" "reset" "--hard" branch
                                 :dir source-dir)
                      (sh-print "git" "branch" "-d" branch :dir source-dir))
             (sh-print "git" "branch" "-M" branch
                       (str canonical-branch-name) :dir source-dir))
           (-> agent -refresh -advance-a)))))))

(defn -advance-b
  [{:keys [branches source-dir] :as agent}]
  (doseq [branch (keys branches)
          :when
          (and (not= (:branch canonical-branch-name)
                     (:branch branch))
               (not (nil? (:peer branch))))]
    (sh-print-when #(= (:exit %) 0)
                   "git" "branch" "-d" (str branch) :dir source-dir))
  (-refresh agent))

; TODO: When we get data pushed to us, detect it by monitoring filesystem
; activity in .git. Then invoke the 'advance' logic below.
(def advance
  "Checks for local branches that meet the following criteria, and performs
  the given operation, 'advancing' when appropriate.
  a) If hesokuri is not checked out, or it is checked out but the working area
     is clean, and some branch hesokuri_hesokr_* is a fast-forward of hesokuri,
     then rename the hesokuri_hesokr_* branch to hesokuri, and remove the
     existing hesokuri branch.
  b) For any two branches F and B, where F is a fast-forward of B, and B has a
     name (BRANCH)_hesokr_*, and BRANCH is not hesokuri, delete branch B."
  #(-> % git-init -refresh -advance-a))

(defn -push-for-one
  "Push a branch as necessary to keep a peer up-to-date. The branch parameter
  should be an instance of Branch. pusher is a version of -push! with the first
  two arguments curried.
  When pushing:
  * third-party peer branches - which is any branch named *_hesokr_(HOST) where
    HOST is not me or the push destination peer, try to push to the same branch
    name, but if it fails, ignore it.
  * hesokuri - try to push to the same branch name, but if it fails, force push
    to hesokuri_hesokr_(MY_HOSTNAME).
  * local branch - which is any branch that is not hesokuri and not named in the
    form of *_hesokr_*, force push to (BRANCH_NAME)_hesokr_(MY_HOSTNAME)"
  [{:keys [source-dir] :as agent} branch local-id peer-name peer-path]
  (let [peer-repo (format "ssh://%s%s" peer-name peer-path)
        push
        (fn [] (sh-print "git" "push" peer-repo
                         (str branch ":" branch)
                         :dir source-dir))
        force-push
        (fn [] (sh-print "git" "push" "-f" peer-repo
                         (str branch ":"
                              (->BranchName (:branch branch) local-id))
                         :dir source-dir))]
    (cond
     (every? #(not= (:peer branch) %) [nil local-id peer-name])
     (push)

     (= canonical-branch-name branch)
     (lint-and (push) (force-push))

     (and (not= canonical-branch-name branch)
          (not (:peer branch)))
     (force-push)))
  agent)

(defn -push-for-peer
  [{:keys [branches] :as agent} & args]
  (loop [branches (keys branches)
         agent agent]
    (if branches
      (recur (next branches)
             (apply -push-for-one agent (first branches) args))
      agent)))

(defn push-for-peer
  "Push all branches necessary to keep one peer up-to-date."
  [agent local-id peer-name peer-path]
  (-> agent git-init -refresh (-push-for-peer local-id peer-name peer-path)))
