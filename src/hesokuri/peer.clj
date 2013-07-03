(ns hesokuri.peer
  "Implementation of the peer object. It optimizes access to a peer by keeping
  track of what has been successfully pushed to it and when it has last failed
  to respond to a ping. All intervals are in milliseconds unless otherwise
  specified.
  A peer object is a map with the following keys:
  :last-fail-ping-time
      The value returned by System/currentTimeMillis when the last test for
      responsiveness failed.
  :pushed
      A map of keys in the form [local-path-to-source branch-name] to sha1 hash
      strings, which indicate what was most recently pushed to the peer from
      the given branch on the given source. If the current hash of the branch is
      the same as the entry in the map, a push will not be attempted."
  (:use hesokuri.util)
  (:import (java.net InetAddress UnknownHostException)))

(def minimum-retry-interval
  "Minimum number of milliseconds to wait between tries."
  (* 5 60 1000))

(def timeout-for-ping
  "Number of milliseconds to wait before a ping response."
  10000)

(def new-peer
  "A new peer with default values for each entry."
  {:last-fail-ping-time 0 :pushed {}})

(defn reset-fail-ping-time
  "Resets the :last-file-ping-time value so that the next push will always try
  pinging again, which may time out for up to timeout-for-ping millis while
  waiting for a response."
  [self]
  (assoc self :last-file-ping-time 0))

(defn push
  "Performs a push. Branch name parameters can either be a string or something
  that evaluates to the name of the branch when str is invoked.

  local-path - the path to the source to push from on the local machine
  peer-repo - a hesokuri.util.PeerRepo object that indicates the peer and source
      to push to.
  branch-name - the local name of the branch
  hash - the hash to push (in general, this should be the hash pointed to by
      branch-name.
  branches - a sequence of sequences in the form: [branch-name & push-args].
      'push-args' is a sequence of strings passed as arguments after 'git push'
      on the command line. 'branch-name' is the destination branch name to use."
  [{:keys [last-fail-ping-time pushed] :as self}
   local-path peer-repo
   branch-name hash
   branches]
  (let [current-time (System/currentTimeMillis)
        pushed-key [local-path branch-name]]
    (cond
     (or (< (- current-time last-fail-ping-time) minimum-retry-interval)
         (= hash (pushed pushed-key)))
     self

     (-> peer-repo :host InetAddress/getByName
         (.isReachable timeout-for-ping)
         (try (catch UnknownHostException _ false)) not)
     (assoc self :last-fail-ping-time current-time)

     :else
     (-> (for [[branch-name & push-args] branches
               :let [latter-args [peer-repo (str hash ":" branch-name)
                                  :dir local-path]]
               :when (= 0 (apply sh-print "git" "push"
                                 `(~@push-args ~@latter-args)))]
           (assoc-in self [:pushed pushed-key] hash))
         first (or self)))))
