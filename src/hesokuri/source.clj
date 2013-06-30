(ns hesokuri.source
  "Implementation of the source agent."
  (:use [clojure.java.shell :only [sh]])
  (:use [clojure.string :only [trim]])
  (:use hesokuri.core)
  (:use hesokuri.util)
  (:import [java.io File]))

(defrecord Agent [source-dir branch-hashes
                  canonical-checked-out working-area-clean])

(def agents
  "A map of source-dirs to the corresponding agent."
  (ref {}))

(defn -git-dir
  "Returns the .git directory of a repository as a java.io.File object, given
  its parent directory. If it is a bare repository, returns the source-dir
  parameter as-is."
  [source-dir]
  (let [source-dir-git (File. source-dir ".git")]
    (if (.isDirectory source-dir-git)
      source-dir-git source-dir)))

(defn -working-area-clean
  "Returns true iff there are no untracked files, unstaged changes, or
  uncommitted changes."
  [source-dir]
  (let [git-dir (-git-dir source-dir)]
    (or (= git-dir source-dir)
        (let [status (sh "git" "status" "--porcelain" :dir source-dir)]
          (and (= 0 (:exit status))
               (= "" (:out status)))))))

(defn -refresh
  "Updates the branch-hashes and canonical-checked-out values of the agent."
  [{:keys [source-dir]}]
  (let [git-dir (-git-dir source-dir)
        heads-dir (File. git-dir "refs/heads")]
    (loop [files (seq (.listFiles heads-dir))
           branches {}]
      (if (not files)
        (Agent. source-dir
                branches
                (= (trim (slurp (File. git-dir "HEAD")))
                   (str "ref: refs/heads/" canonical-branch-name))
                (-working-area-clean source-dir))
        (let [file (first files)
              hash (trim (slurp file))]
          (recur (next files)
                 (if (= (count hash) 40)
                   (conj branches [(parse-branch-name (.getName file)) hash])
                   branches)))))))

(defn -advance-a
  [{:keys [source-dir branch-hashes canonical-checked-out working-area-clean]
    :as agent}]
  (when (or (not canonical-checked-out) working-area-clean)
    (loop [branches (seq branch-hashes)]
      (let [canonical-branch (branch-hashes canonical-branch-name)
            branch (first (first branches))]
        (cond
         (not branches)
         (do
           (send (agents source-dir) -advance-b)
           agent)

         (or (not= (:branch canonical-branch-name)
                   (:branch branch))
             (= canonical-branch-name branch)
             (and canonical-branch
                  (not (is-ff! source-dir canonical-branch
                               (second (first branches)) true))))
         (recur (next branches))

         :else
         (let [branch (str branch)]
           (if canonical-checked-out
             (lint-or (sh-print "git" "reset" "--hard" branch
                                 :dir source-dir)
                      (sh-print "git" "branch" "-d" branch :dir source-dir))
             (sh-print "git" "branch" "-M" branch
                        (str canonical-branch-name) :dir source-dir))
           (send (agents source-dir) -refresh)
           (send (agents source-dir) -advance-a)
           agent))))))

(defn -advance-b
  [{:keys [branch-hashes source-dir] :as agent}]
  (doseq [branch (keys branch-hashes)]
    (when (and (not= (:branch canonical-branch-name) (:branch branch))
               (not (nil? (:peer branch))))
      (let [res (sh "git" "branch" "-d" (str branch) :dir source-dir)]
        (when (= 0 (:exit res))
          (.print *out* (:out res))
          (.print *err* (:err res))))))
  agent)


; TODO: When we get data pushed to us, detect it by monitoring filesystem
; activity in .git. Then invoke the 'advance' logic below.
(defn advance
  "Checks for local branches that meet the following criteria, and performs
  the given operation, 'advancing' when appropriate.
  a) If hesokuri is not checked out, or it is checked out but the working area is
     clean, and some branch hesokuri_hesokr_* is a fast-forward of hesokuri, then
     rename the hesokuri_hesokr_* branch to hesokuri to it, and remove the
     existing hesokuri branch.
  b) For any two branches F and B, where F is a fast-forward of B, and B has a
     name (BRANCH)_hesokr_*, and BRANCH is not hesokuri, delete branch B."
  [{:keys [source-dir]}]
  (send-off (agents source-dir) -refresh)
  (send-off (agents source-dir) -advance-a))

(defn push-for-peer
  "Push all sources and branches necessary to keep one peer up-to-date."
  [peer-name]
  (io!
   (let [me @local-identity]
     (doseq [source (common-sources peer-name me)]
       (let [my-branches (keys (branch-hashes! (source me)))
             peer-repo (format "ssh://%s%s" peer-name (source peer-name))
             pusher (fn [& args] (apply -push! (source me) peer-repo args))]
         (doseq [branch my-branches]
           (push-for-one me pusher branch peer-name)))))))
