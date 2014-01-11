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

(ns hesokuri.config
  (:require [hesokuri.discovery-dir-def :as discovery-dir-def]
            [hesokuri.source-def :as source-def]
            [hesokuri.util :as util]
            [hesokuri.validation :as validation]))

(defn source-defs
  [config]
  (if (map? config) (:sources config) config))

(defn discovery-dir-defs
  "Returns the discovery dirs in the given config. For the legacy config format,
  this is always an empty vector. For the normal config format, this is the
  :discovery-dirs entry, and is a vector of any number of discovery-dir-def
  values, defined in the discovery-dir-def module."
  [config]
  (or (and (map? config) (:discovery-dirs config))
      []))

(defn- round-trip-validation-error
  [data]
  (cond
   (string? data) nil
   (number? data) nil
   (keyword? data) nil
   (true? data) nil
   (false? data) nil
   (nil? data) nil

   (map? data)
   (validation/combine (map round-trip-validation-error (apply concat data)))

   (or (vector? data) (set? data))
   (validation/combine (map round-trip-validation-error data))

   true
   (str "Data of type " (class data) " is not allowed in config files: " data)))

(defn validation
  "Performs validation on the given config."
  [config]
  (if (not (or (map? config) (vector? config)))
    (str "config must be map or vector, but it is a " (class config))
    (validation/combine
     [(validation/for-vector "source def"
                             source-def/validation
                             (source-defs config))
      (validation/for-vector "discovery dir def"
                             discovery-dir-def/validation
                             (discovery-dir-defs config))
      (round-trip-validation-error config)])))
