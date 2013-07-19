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

(ns hesokuri.branch-name
  (:use [clojure.string :only [split]]))

(defrecord BranchName [branch peer]
  Object
  (toString [_]
    (if peer
      (str branch "_hesokr_" peer)
      (str branch))))

(defn parse-branch-name [name]
  (let [s (split name #"_hesokr_" 2)]
    (BranchName. (first s) (second s))))

(def canonical-branch-name
  "This is the name of the only branch that is aggressively synced between
  clients. This branch has the property that it cannot be deleted, and automatic
  updates must always be a fast-forward."
  (BranchName. "hesokuri" nil))
