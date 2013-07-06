(ns hesokuri.source
  "Implementation of the source object. A valid source object has the following
  required fields:
  source-dir - The location of this source on the local disk.
  peer-dirs - a map from peer hostnames to the location of the source on that
      peer.
  peer-agents - a map of hostnames to the corresponding peer agent.
  local-identity - the hostname or IP of this system."
  (:use [clojure.java.shell :only [sh]]
        [clojure.string :only [trim]]
        hesokuri.branch-name
        hesokuri.log
        hesokuri.peer
        hesokuri.util)
  (:import [java.io File]
           [java.nio.file
            ClosedWatchServiceException
            FileSystems
            Paths
            StandardWatchEventKinds]))

(defn -git-init
  "Initializes the repository if it doesn't exist yet."
  [{:keys [source-dir] :as self}]
  (sh-print-when #(not= 0 (:exit %)) "git" "init" source-dir)
  self)

(defn -refresh
  "Updates all values of the source object based on the value of source-dir,
  which remains the same."
  [{:keys [peer-dirs peer-agents local-identity source-dir] :as self}]
  (letmap
   [:keep [peer-dirs peer-agents local-identity source-dir]

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

(defn -advance-b
  [{:keys [branches source-dir] :as self}]
  (doseq [branch (keys branches)
          :when
          (and (not= (:branch canonical-branch-name)
                     (:branch branch))
               (not (nil? (:peer branch))))]
    (sh-print-when #(= (:exit %) 0)
                   "git" "branch" "-d" (str branch) :dir source-dir))
  self)

(defn -advance-a
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
         (not branches) (-advance-b self)

         (or (not= (:branch canonical-branch-name)
                   (:branch branch))
             (= canonical-branch-name branch)
             (and canonical-branch
                  (not (is-ff! source-dir canonical-branch
                               (second (first branches)) true))))
         (recur self (next branches))

         :else
         (let [branch (str branch)]
           (if canonical-checked-out
             (lint-or (sh-print "git" "reset" "--hard" branch
                                 :dir source-dir)
                      (sh-print "git" "branch" "-d" branch :dir source-dir))
             (sh-print "git" "branch" "-M" branch
                       (str canonical-branch-name) :dir source-dir))
           (recur (-refresh self) (seq all-branches))))))))

(def advance
  "Checks for local branches that meet the following criteria, and performs
  the given operation, 'advancing' when appropriate.
  a) If hesokuri is not checked out, or it is checked out but the working area
     is clean, and some branch hesokuri_hesokr_* is a fast-forward of hesokuri,
     then rename the hesokuri_hesokr_* branch to hesokuri, and remove the
     existing hesokuri branch.
  b) For any two branches F and B, where F is a fast-forward of B, and B has a
     name (BRANCH)_hesokr_*, and BRANCH is not hesokuri, delete branch B."
  #(-> % -git-init -refresh -advance-a))

(defn -send-args-to-push-branch
  "Push a branch as necessary to keep a peer up-to-date. The branch parameter
  should be an instance of Branch.
  When pushing:
  * third-party peer branches - which is any branch named *_hesokr_(HOST) where
    HOST is not me or the push destination peer, try to push to the same branch
    name, but if it fails, ignore it.
  * hesokuri - try to push to the same branch name, but if it fails, force push
    to hesokuri_hesokr_(MY_HOSTNAME).
  * local branch - which is any branch that is not hesokuri and not named in the
    form of *_hesokr_*, force push to (BRANCH_NAME)_hesokr_(MY_HOSTNAME)"
  [{:keys [peer-agents local-identity source-dir branches peer-dirs] :as self}
   peer-host branch]
  [(peer-agents peer-host) push
   source-dir (->PeerRepo peer-host (peer-dirs peer-host))
   branch (branches branch)
   (let [force-branch (fn [] (->BranchName (:branch branch) local-identity))]
     (cond
      (every? #(not= (:peer branch) %) [nil local-identity peer-host])
      [[branch]]

      (= canonical-branch-name branch)
      [[branch] [(force-branch) "-f"]]

      (and (not= canonical-branch-name branch)
           (not (:peer branch)))
      [[(force-branch) "-f"]]

      :else []))])

(defn -push-for-peer
  [{:keys [branches] :as self} peer-host]
  (doseq [branch (keys branches)]
    (apply send (-send-args-to-push-branch self peer-host branch)))
  self)

(defn push-for-peer
  "Push all branches necessary to keep one peer up-to-date."
  [self peer-host]
  (-> self -git-init -refresh (-push-for-peer peer-host)))

(defn push-for-all-peers
  "Pushes all branches necessary to keep all peers up-to-date."
  [{:keys [peer-agents] :as self}]
  (loop [self self
         peer-hosts (keys peer-agents)]
    (cond
     (nil? peer-hosts) self

     :else (recur (push-for-peer self (first peer-hosts))
                  (next peer-hosts)))))

(defn stop-watching
  "Stops watching the file system. If not watching, this is a no-op."
  [{:keys [watcher] :as self}]
  (when watcher (.close watcher))
  (dissoc self :watcher))

(defn start-watching
  "Registers paths in this source's repo to be notified of changes so it can
  automatically advance and push"
  [self]
  (let [watcher (.newWatchService (FileSystems/getDefault))
        self (-> self stop-watching -git-init -refresh (assoc :watcher watcher))
        self-agent *agent*
        watch-dir (Paths/get (str (:git-dir self))
                             (into-array ["refs" "heads"]))
        start
        (fn []
          (let [watch-key (.take watcher)]
            (log "got a key for %s" (self :source-dir))
            (doseq [event (.pollEvents watch-key)]
              (log "found a change in %s" (self :source-dir))
              (send self-agent advance)
              (send self-agent push-for-all-peers))
            (if (.reset watch-key) (recur) nil)))]
    (.register watch-dir watcher
               (into-array
                [StandardWatchEventKinds/ENTRY_CREATE
                 StandardWatchEventKinds/ENTRY_MODIFY]))
    (-> start Thread. .start)
    self))
