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
  (:require [hesokuri.peer-repo :as peer-repo]
            [hesokuri.repo :as repo]
            [hesokuri.util :refer :all]))

(def default
  "A new peer with default values for each entry. A peer has the following keys:
  :timeout-for-ping - amount of time to wait for a response from the peer when
      pinging to check responsiveness
  :minimum-retry-interval - amount of time that must have passed from
      :last-fail-ping-time before try to push again
  :last-fail-ping-time (optional) - the last time a ping for responsiveness
      timed out. Remove this from the object if you don't want the next call to
      push to be a no-op as a result of the last ping failing before
      :minimum-retry-interval.
  :pushed - A map of keys in the form [local-repo-dir branch] to
      sha1 hash strings, which indicate what was most recently pushed to
      the peer from the given branch on the given source. If the current
      hash of the branch is the same as the entry in the map, a push will
      not be attempted."
  {:timeout-for-ping 10000
   :minimum-retry-interval (* 4 60 1000)
   :pushed {}})

(defn- too-soon-to-push
  [{:keys [last-fail-ping-time minimum-retry-interval]} current-time]
  (and last-fail-ping-time
       (< (- current-time last-fail-ping-time)
          minimum-retry-interval)))

(defn push
  "Performs a push, respecting the :last-failed-ping value and :pushed map
  Returns a new peer object. This function may block when checking if the peer
  is responsive.
  local-repo - hesokuri.repo object representing the source to push from on the
      local machine
  peer-repo - a hesokuri.peer-repo object that indicates the repo to push to
  push-branch - an object representing the branch to push
  hash - the hash to push (in general, this should be the hash pointed to by
      push-branch
  tries - a sequence of sequences in the form: [branch allow-non-ff].
      'branch' is the destination branch name as a string. 'allow-non-ff'
      indicates whether a non-fast-forward should be allowed in the push
      Setting this to true is equivalent to passing '-f' to git push."
  [{:keys [pushed timeout-for-ping] :as self}
   local-repo
   peer-repo
   push-branch
   hash
   tries]
  (let [current-time (current-time-millis)
        pushed-key [(:dir local-repo) push-branch]]
    (cond
     (too-soon-to-push self current-time) self

     (= hash (pushed pushed-key)) self

     (not (peer-repo/accessible peer-repo timeout-for-ping))
     (assoc self :last-fail-ping-time current-time)

     :else
     (-> (for [[branch allow-non-ff] tries
               :when (= 0 (repo/push-to-branch
                           local-repo
                           (peer-repo/push-str peer-repo)
                           hash
                           branch
                           allow-non-ff))]
           (assoc-in self [:pushed pushed-key] hash))
         first
         (or self)
         (dissoc :last-fail-ping-time)))))
