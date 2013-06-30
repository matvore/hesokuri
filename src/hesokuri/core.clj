(ns hesokuri.core
  (:use [clojure.java.shell :only [sh]]
        [clojure.string :only [join split trim]]
        hesokuri.util
        hesokuri.source)
  (:import [java.io File]
           [java.util Date])
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

(defn identities
  "Returns a vector of the possible identities this system may have on the
network, which includes its hostname and the IP address of all network
interfaces. Each identity is a string."
  []
  (conj
  (let [interfaces (vector-from-enum
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

(defn refresh-sources!
  "Updates sources and peer-hostnames based on the user's sources config file.
  Also creates empty repositories for any that are specified to exist on this
  system but for which the directory does not exist."
  []
  (let [[my-sources local-identity]
         (dosync
          (ref-set sources (read-string (slurp sources-config-file)))
          (ref-set peer-hostnames (set (apply concat (map keys @sources))))
          (ref-set local-identity (-local-identity))
          (list (common-sources @local-identity) @local-identity))]
    (doseq [source my-sources
            :let [source-dir (File. (source local-identity))]
            :when (not (.exists source-dir))]
      (io! (sh-print "git" "init" (str source-dir))))))

(defn -push! [local-path peer-repo local-branch remote-branch & other-flags]
  (let [args (concat (list "git" "push") other-flags
                     (list peer-repo (str local-branch ":" remote-branch)
                           :dir local-path))]
    (io!
     (println (join " " args))
     (apply sh-print args))))

(defn -pull! [peer-repo local-path]
  (io!
   ; TODO: Do a git fetch and process the branches more intelligently.
   ; TODO: Pull to a different branch if the current one has merge conflicts.
   (println "Pulling " peer-repo " to " local-path)
   (sh-print "git" "pull" peer-repo "master" :dir local-path)))

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

(defn push-for-one
  "Push a branch as necessary to keep a peer up-to-date. The branch parameter
  should be an instance of Branch. pusher is a version of -push! with the first
  two arguments curried.
  When pushing:
  * third-party peer branches - which is any branch named *_hesokr_(HOST) where
    HOST is not me or the push destination peer, try to push to the same branch
    name, but if it fails, ignore it.
  * hesokuri - try to push to the same branch name, but if it fails, force push to
    hesokuri_hesokr_(MY_HOSTNAME).
  * local branch - which is any branch that is not hesokuri and not named in the
    form of *_hesokr_*, force push to (BRANCH_NAME)_hesokr_(MY_HOSTNAME)"
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
