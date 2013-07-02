(ns hesokuri.peer
  "Implementation of the peer object. It optimizes access to a peer by keeping
  track of what has been successfully pushed to it and when it has last failed
  to respond to a ping. All intervals are in milliseconds unless otherwise
  specified."
  (:use hesokuri.util)
  (:import (java.net InetAddress UnknownHostException)))

(def minimum-retry-interval
  "Minimum number of milliseconds to wait between tries."
  (* 5 60 1000))

(def timeout-for-ping
  "Number of milliseconds to wait before a ping response."
  10000)

(defn new-peer
  "Creates a new peer with default values for arguments and the given :host,
  which is the hostname used to reach the peer."
  [host]
  {;; The value returned by System/currentTimeMillis when the last test for
   ;; responsiveness failed.
   :last-no-response-time 0

   ;; A map of keys in the form [local-path-to-source branch-name] to sha1 hash
   ;; strings, which indicate what was most recently pushed to the peer from
   ;; the given branch on the given source. If the current hash of the branch is
   ;; the same as the entry in the map, a push will not be attempted.
   :pushed {}

   ;; The hostname of the peer.
   :host host})

(defn push
  "Performs a push. Branch name parameters can either be a string or something
  that evaluates to the name of the branch when str is invoked.

  always-try - when truthy, indicates ignore the last-no-response-time so a
      recent failed ping will not cause this function to no-op
  local-path - the path to the source to push from on the local machine
  peer-path - the path to the source to push to on the peer (remote) machine
  branch-name - the local name of the branch
  hash - the hash to push (in general, this should be the hash pointed to by
      branch-name
  normal-push-branches - branch names to attempt a non-forced pushed (tried
      first)
  force-push-branches - branch names to attempt a force push (-f). Tried only
      if all the normal push branches failed."
  [{:keys [last-no-response-time pushed host] :as self}
   always-try
   local-path peer-path
   branch-name hash
   normal-push-branches force-push-branches]
  (let [current-time (System/currentTimeMillis)
        peer-repo (str (->PeerRepo host peer-path))
        pushed-key [local-path branch-name]]
    (cond
     (or (and (not always-try) last-no-response-time
              (< (- current-time last-no-response-time) minimum-retry-interval))
         (= hash (pushed [local-path branch-name])))
     self

     (try (-> host InetAddress/getByName (.isReachable timeout-for-ping))
          (catch UnknownHostException _ false))
     (assoc self :last-no-response-time current-time)

     :else
     (loop [normal-push-branches (seq normal-push-branches)
            force-push-branches (seq force-push-branches)]
       (cond
        normal-push-branches
        (if (= 0 (sh-print "git" "push" peer-repo
                           (str hash ":" (first normal-push-branches))
                           :dir local-path))
          (assoc self :pushed (assoc pushed pushed-key hash))
          (recur (next normal-push-branches) force-push-branches))

        force-push-branches
        (if (= 0 (sh-print "git" "push" "-f" peer-repo
                           (str hash ":" (first force-push-branches))
                           :dir local-path))
          (assoc self :pushed (assoc pushed pushed-key hash))
          (recur nil (next force-push-branches)))

        :else self)))))
