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

(ns hesokuri.peer
  "Implementation of the peer object. It optimizes access to a peer by keeping
  track of what has been successfully pushed to it."
  (:use hesokuri.util)
  (:import (java.net ConnectException InetAddress UnknownHostException)))

(defn- accessible
  "Checks if the given host is accessible, waiting for the specified timeout for
  a respose.
  host - hostname or IP address of host to check
  timeout - the number of millis to wait for a response"
  [host timeout]
  (-> host
      InetAddress/getByName
      (.isReachable timeout)
      (try (catch UnknownHostException _ false)
           ;; ConnectException happens when "Host is down" which is
           ;; not "exceptional"
           (catch ConnectException _ false))))

(defn new-peer
  "Creates a new peer with default values for each entry."
  []
  (letmap
   [:omit timeout-for-ping 10000
    :omit minimum-retry-interval (* 4 60 1000)

    ;; The agent corresponding to this object. Contains the :last-fail-ping-time
    ;; value and the :pushed map.
    :omit self (agent {:pushed {}})

    ;; Returns the current state of this peer.
    snapshot
    (fn []
      (letmap
       [:keep
        [;; The number of milliseconds to wait before a ping response.
         timeout-for-ping

         ;; Minimum number of milliseconds to wait between tries.
         minimum-retry-interval]

        ;; The last exception that occurred on the peer that wasn't cleared,
        ;; or nil if there is no error.
        error (agent-error self)

        ;; The previous value in a singleton sequence, or nil if there is no
        ;; error.
        errors (agent-errors self)

        :omit self @self

        ;; A map of keys in the form [local-path-to-source branch-name] to
        ;; sha1 hash strings, which indicate what was most recently pushed to
        ;; the peer from the given branch on the given source. If the current
        ;; hash of the branch is the same as the entry in the map, a push will
        ;; not be attempted.
        pushed (self :pushed)

        ;; The value returned by System/currentTimeMillis when the last test
        ;; for responsiveness failed. nil if it has never failed before, or if
        ;; the last ping was successful.
        last-fail-ping-time (self :last-fail-ping-time)]))

    ;; Removes the last-fail-ping-time value so the next push will definitely
    ;; retry.
    reset-last-fail-ping-time
    (fn []
      (send self #(dissoc % :last-fail-ping-time))
      nil)

    ;; Performs a push. Branch name parameters can either be a string or
    ;; something that evaluates to the name of the branch when str is invoked.
    push
    (fn [;; the path to the source to push from on the local machine
         local-path
         ;; a hesokuri.util.PeerRepo object that indicates the peer and source
         ;; to push to.
         peer-repo
         ;; the local name of the branch
         branch-name
         ;; the hash to push (in general, this should be the hash pointed to by
         ;; branch-name).
         hash
         ;; a sequence of sequences in the form: [branch-name & push-args].
         ;; 'push-args' is a sequence of strings passed as arguments after 'git
         ;; push' on the command line. 'branch-name' is the destination branch
         ;; name to use."
         tries]
      (send self (fn [{:keys [pushed last-fail-ping-time] :as self}]
        (let [current-time (current-time-millis)
              pushed-key [local-path branch-name]]
          (cond
           (or (< (- current-time (or last-fail-ping-time
                                      (- minimum-retry-interval)))
                  minimum-retry-interval)
               (= hash (pushed pushed-key)))
           self

           (not (accessible (:host peer-repo) timeout-for-ping))
           (assoc self :last-fail-ping-time current-time)

           :else
           (-> (for [[branch-name & push-args] tries
                     :let [latter-args [(str peer-repo)
                                        (str hash ":refs/heads/" branch-name)
                                        :dir local-path]]
                     :when (= 0 (apply sh-print "git" "push"
                                       `(~@push-args ~@latter-args)))]
                 (assoc-in self [:pushed pushed-key] hash))
               first
               (or self)
               (dissoc :last-fail-ping-time)))))))

    ;; Clears the error on the agent, and returns the Exception for it. If there
    ;; is no error, this function returns nil.
    restart
    (fn []
      (let [error (agent-error self)]
        (when error
          (restart-agent self @self)
          error)))]))
