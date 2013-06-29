(ns hesokuri.core
  (:use [clojure.java.shell :only [sh]])
  (:use [clojure.string :only [join split trim]])
  (:import [java.io File]
           [java.util Date Collections])
  (:gen-class))

(def sources
  "Defines the hesokuri sources. This is the user-configurable settings that
hesokuri needs to discover sources on the network and how to push and pull
them. This can be read from a configuration file. It is a map in the following
form:
 [
   {
     \"host-1\" \"/foo/bar/path1\"
     \"host-2\" \"/foo/bar/path2\"
   }
   {
     \"host-1\" \"/foo/bar/path3\"
     \"host-3\" \"/foo/bar/path4\"
   }
 ]"
  (ref []))

(def peer-hostnames
  "A set of the hostnames of the peers. Updated with sources."
  (ref #{}))

(def local-identity
  "The hostname or IP of this system as known by the peers on the current
network."
  (ref nil))

(def sources-config-file
  "Where to read the hesokuri sources configuration from."
  (str (.get (System/getenv) "HOME") "/.hesokuri/sources"))

(defrecord BranchName [branch peer]
  Object
  (toString [_]
    (if peer
      (str branch "_hesokr_" peer)
      (str branch))))

(def canonical-branch-name
  "This is the name of the only branch that is aggressively synced between
  clients. This branch has the property that it cannot be deleted, and automatic
  updates must always be a fast-forward."
  (BranchName. "hesokuri" nil))

(defn parse-branch-name [name]
  (let [s (split name #"_hesokr_" 2)]
    (BranchName. (first s) (second s))))

(defn -vector-from-enum [enum]
  (vec (Collections/list enum)))

(defn identities
  "Returns a vector of the possible identities this system may have on the
network, which includes its hostname and the IP address of all network
interfaces. Each identity is a string."
  []
  (conj
  (let [interfaces (-vector-from-enum
                    (java.net.NetworkInterface/getNetworkInterfaces))
        address-of (fn [addr]
                     (first (split (.getHostAddress (.getAddress addr)) #"%")))]
    (loop [res [] i (seq interfaces)]
      (cond
       (not i) res
       (nil? (first i)) (recur res (next i))
       :else
       (recur (into res
         (loop [subres [] addrs (seq (.getInterfaceAddresses (first i)))]
           (cond
            (not addrs) subres
            (nil? (first addrs)) (recur subres (next addrs))
            :else (recur (conj subres (address-of  (first addrs)))
                         (next addrs)))))
              (rest i)))))
  (trim (:out (sh "hostname")))))

(defn -local-identity
  "Returns the identity of this system. It deduces it from the (identities)
vector and the peer-hostnames var."
  []
  (loop [candidates (seq (identities))]
    (cond
     (not candidates) nil
     (@peer-hostnames (first candidates)) (first candidates)
     :else (recur (next candidates)))))

(defn refresh-sources
  "Updates sources and peer-hostnames based on the user's sources config file."
  []
  (dosync
   (ref-set sources (read-string (slurp sources-config-file)))
   (ref-set peer-hostnames (set (apply concat (map keys @sources))))
   (ref-set local-identity (-local-identity))
   (list @sources @peer-hostnames @local-identity)))

(defn -push! [local-path peer-repo local-branch remote-branch & other-flags]
  (let [args (concat (list "git" "push") other-flags
                     (list peer-repo (str local-branch ":" remote-branch)
                           :dir local-path))]
    (io!
     (println (join " " args))
     (let [res (apply sh args)]
       (print (:out res) (:err res))
       (:exit res)))))

(defn -pull! [peer-repo local-path]
  (io!
   ; TODO: Do a git fetch and process the branches more intelligently.
   ; TODO: Pull to a different branch if the current one has merge conflicts.
   (println "Pulling " peer-repo " to " local-path)
   (let [res (sh "git" "pull" peer-repo "master" :dir local-path)]
     (print (:out res) (:err res))
     (:exit res))))

(defn -clone! [peer-repo local-path]
  (io!
   (println "Cloning " peer-repo " to " local-path)
   (let [res (sh "git" "clone" peer-repo local-path)]
     (print (:out res) (:err res))
     (:exit res))))

(defn report [results func-name func & args]
  ; TODO: make this a macro, I think
  (let [append-to (if (= 0 (apply func args)) :succeeded :failed)
        report-item (cons func-name (seq args))]
    (conj results
          [append-to (conj (append-to results) report-item)]
          [:last append-to])))

(defmacro lint-and
  "Performs a short-circuited logical int-based and. If the first expression is
  0, then the next expression is not evaluated. Returns the last expression
  evaluated."
  [x y]
  (let [x-res (gensym)]
    `(let [~x-res ~x]
       (if (= 0 ~x-res) ~x-res ~y))))

(defmacro lint-or
  "Performs a short-circuited logical int-based or. If the first expression is
  non-zero, then the next expression is not evaluated. Returns the last
  expression evaluated."
  [x y]
  (let [x-res (gensym)]
    `(let [~x-res ~x]
       (if (not= 0 ~x-res) ~x-res ~y))))

(defn push-for-one
  "Push a branch as necessary to keep a peer up-to-date. The branch parameter
should be an instance of Branch. pusher is a version of -push! with the first
two arguments curried."
  [me pusher branch peer]
  (letfn [(force-push []
            (pusher branch (BranchName. (:branch branch) me) "-f"))]
    (cond
     (every? #(not= (:peer branch) %) [nil me peer])
     (pusher (str branch) (str branch))

     (= canonical-branch-name branch)
     (lint-and (pusher branch branch) (force-push))

     (and (not= canonical-branch-name branch)
          (not (:peer branch)))
     (force-push))))

(defn common-sources
  "Returns a list of all items in the sources vector that are on all of the
given peers."
  [& peer-names]
  (loop [sources (seq (dosync (ensure sources)))
         results []]
    (cond
     (not sources) results

     (every? #((first sources) %) peer-names)
     (recur (next sources) (conj results (first sources)))

     :else
     (recur (next sources) results))))

(defn push-for-peer!
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

(defn git-dir!
  "Returns the .git directory of a repository as a java.io.File object, given
  its parent directory. If it is a bare repository, returns the source-dir
  parameter as-is."
  [source-dir]
  (io!
   (let [source-dir-git (File. source-dir ".git")]
     (if (.isDirectory source-dir-git)
       source-dir-git source-dir))))

(defn branch-hashes!
  "Gets all of the branches of the local repo at the given string path. It
returns a map of branch names to sha1 hashes."
  [local-path]
  (io!
   (let [git-dir (git-dir! local-path)
         heads-dir (File. git-dir "refs/heads")]
     (loop [files (seq (.listFiles heads-dir))
            branches {}]
       (if (not files) branches
           (let [file (first files)
                 hash (trim (slurp file))]
             (recur (next files)
                    (if (= (count hash) 40)
                      (conj branches [(parse-branch-name (.getName file)) hash])
                      branches))))))))

(defn working-area-clean!
  "Returns true iff there are no untracked files, unstaged changes, or
  uncommitted changes."
  [source-dir]
  (io!
   (let [git-dir (git-dir! source-dir)]
     (or (= git-dir source-dir)
         (let [status (sh "git" "status" "--porcelain" :dir source-dir)]
           (and (= 0 (:exit status))
                (= "" (:out status))))))))

(defn is-ff!
  "Returns true iff the second hash is a fast-forward of the first hash. When
  the hashes are the same, returns when-equal."
  [source-dir from-hash to-hash when-equal]
  (if (= from-hash to-hash) when-equal
      (= from-hash (trim (:out (sh "git" "merge-base"
                                   from-hash to-hash :dir source-dir))))))

(defn sh-print!
  [& args]
  (io!
   (let [result (apply sh args)]
     (.print *err* (:err result))
     (.print *out* (:out result))
     (:exit result))))

; TODO: When we get data pushed to us, detect it by monitoring filesystem
; activity in .git. Then invoke the 'advance' logic below.
(defn advance!
  "Checks for local branches that meet the following criteria, and performs
  the given operation, 'advancing' when appropriate.
  a)If hesokuri is not checked out, or it is checked out but the working area is
    clean, and some branch hesokuri_hesokr_* is a fast-forward of hesokuri, then
    rename the hesokuri_hesokr_* branch to hesokuri to it, and remove the
    existing hesokuri branch.
  b)For any two branches F and B, where F is a fast-forward of B, and B has a
    name (BRANCH)_hesokr_*, and BRANCH is not hesokuri, delete branch B."
  [source-dir]
  (io!
   ; (a)
   (let [canonical-checked-out
         (= (trim (slurp (File. (git-dir! source-dir) "HEAD")))
            (str "ref: refs/heads/" canonical-branch-name))]
     (when (or (not canonical-checked-out) (working-area-clean! source-dir))
       (loop [all-branches (branch-hashes! source-dir)
              branches (seq all-branches)]
         (let [canonical-branch (all-branches canonical-branch-name)
               branch (first (first branches))]
           (cond
            (not branches) nil

            (or (not= (:branch canonical-branch-name)
                      (:branch branch))
                (= canonical-branch-name branch)
                (and canonical-branch
                     (not (is-ff! source-dir canonical-branch
                                  (second (first branches)) true))))
            (recur all-branches (next branches))

            :else
            (let [branch (str branch)]
              (if canonical-checked-out
                (lint-or (sh-print! "git" "reset" "--hard" branch
                                    :dir source-dir)
                         (sh-print! "git" "branch" "-d" branch :dir source-dir))
                (sh-print! "git" "branch" "-M" branch
                           (str canonical-branch-name) :dir source-dir))
              (let [new-branches (branch-hashes! source-dir)]
                (recur new-branches (seq new-branches)))))))))
   ; (b)
   (doseq [branch (keys (branch-hashes! source-dir))]
     (when (and (not= (:branch canonical-branch-name) (:branch branch))
                (not (nil? (:peer branch))))
       (sh-print! "git" "branch" "-d" (str branch) :dir source-dir)))))

(defn kuri!
  "A very stupid implementation of the syncing process, ported directly from the
Elisp prototype. This simply pushes and pulls every repo with the given peer."
  [peer-name]
  (io!
   (let [me @local-identity
         sources (and me (seq (common-sources peer-name me)))
         remote-track-name (str (BranchName. "master" me))]
     (cond
      (not me)
      (println "Local identity not set - cannot kuri")

      (not sources)
      (println "Could not find any sources on both " peer-name " and " me)

      :else
      (println "\n\nkuri operation at " (str (Date.))
               " with peer: " peer-name))
     (let [results
       (loop [sources sources
              results {:succeeded [] :failed []}]
         (if (not sources) results
         (let [source (first sources)
               local-path (source me)
               local-path-file (File. local-path)
               peer-path (source peer-name)
               peer-repo (str "ssh://" peer-name peer-path)]
           (cond
            (not (.exists local-path-file))
            (recur (next sources)
                   (report results "clone" -clone! peer-repo local-path))

            (not (.isDirectory local-path-file))
            (throw (RuntimeException.
                    (str "path for repo is occupied by a non-directory file: "
                         local-path)))

            :else
            (let [results (loop
              [ops [:push-straight :pull] results results]
              (cond
               (not ops) results

               (= :push-straight (first ops))
               (let [results (report results "push"
                                     -push! local-path peer-repo
                                     "master" "master")]
                 (if (= :succeeded (results :last))
                   (recur (next ops) results)
                   (recur (cons :push (next ops)) results)))

               (= :push (first ops))
               (recur (next ops)
                      (report results "push"
                              -push! local-path peer-repo
                              "master" remote-track-name))

               :else
               (recur (next ops)
                      (report results "pull" -pull! peer-repo local-path))))]
              (recur (next sources) results))))))]
       (when (seq (:succeeded results))
         (println "\nThe following operations succeeded:")
         (doseq [s (:succeeded results)] (println s)))
       (when (seq (:failed results))
         (println "\nThe following operations failed:")
         (doseq [f (:failed results)] (println f)))))))

(defn -main
  "I don't do a whole lot ... yet."
  [& args]
  ;; work around dangerous default behaviour in Clojure
  (alter-var-root #'*read-eval* (constantly false))
  (println "Hello, World!"))
