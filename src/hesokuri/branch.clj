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

(ns hesokuri.branch
  "Defines the branch object. A branch has a name and an (optional) peer name
  from which it originates."
  (:require [clojure.string :refer [split]]))

(defn of [name & [peer]]
  {:pre [name]}
  (if peer
    {:name name :peer peer}
    {:name name}))

(defn underscored-name [{:keys [name peer]}]
  {:pre [name]}
  (if peer
    (str name "_hesokr_" peer)
    name))

(defn parse-underscored-name [name]
  (let [s (split name #"_hesokr_" 2)]
    (of (first s) (second s))))
