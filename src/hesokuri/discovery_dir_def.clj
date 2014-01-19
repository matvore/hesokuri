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

(ns hesokuri.discovery-dir-def
  "Utilities for validating and reading discovery-dir definitions. A typical
  definition looks like:
  {:peers
   [{:hosts [\"192.168.1.1\" \"192.168.1.33\"]
     :path \"/home/jdoe/repos\"}
    {:hosts [\"192.168.1.2\" \"192.168.1.30\"]
     :path \"/Users/jdoe/repos\"}]}
  The definition is a map with a :peers entry. The value associated with :peers
  is a vector of maps, where each map has a :hosts and :path entry. The :hosts
  entry is a indicates the IPs or host names to which the path corresponds. The
  :path indicates where the discovery dir is located on those hosts. It is OK to
  specify only one entry in the :hosts vector."
  (:require [hesokuri.validation :as validation]))

(defn- peers-entry-validation
  "Performs validation for one of the entries in the :peers vector."
  [peer]
  (validation/conditions
   (map? peer)
   ["Expected a map for :peers entry but got a " (class peer)]

   (vector? (:hosts peer))
   ["Expected a :hosts entry of type vector in map: " peer]

   (string? (:path peer))
   ["Expected a :path entry of type string in map: " peer]

   (every? string? (:hosts peer))
   ["Expected every entry to be a string in vector: " (:hosts peer)]))

(defn validation
  "Performs validation on the given discovery-dir-def."
  [def]
  (validation/conditions
   (map? def)
   ["Expected a map for discovery-dir-def but got a " (class def)]

   (contains? def :peers)
   ["Expected a :peers entry in map: " def]

   :passes
   (validation/for-vector "entry in :peers for discovery-dir-def"
                          peers-entry-validation
                          (:peers def))))
