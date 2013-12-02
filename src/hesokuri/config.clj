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
  (:use [clojure.string :only [join]])
  (:require [hesokuri.source-def :as source-def]
            [hesokuri.util :as util]))

(defn source-defs
  [config]
  (if (map? config) (:sources config) config))

(defn- join-error-strings [all-strings]
  (let [non-nil-strings (filter identity all-strings)]
    (and (seq non-nil-strings) (join "\n" non-nil-strings))))

(defn- source-defs-validation-error
  "Sees if the given source-defs appears valid. If it is valid, returns nil.
  Otherwise, returns a plain English string explaining which source-defs are
  invalid and why."
  [source-defs]
  (if (vector? source-defs)
    (join-error-strings
     (for [source-def source-defs
           :let [def-error-message (source-def/validation-error source-def)]
           :when def-error-message]
       (str "Source def '" source-def "' is invalid because: "
            def-error-message)))
    (str "Sources must be specified with a vector, but it is a "
         (class source-defs))))

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
   (join-error-strings
    (apply concat (for [[k v] data] [(round-trip-validation-error k)
                                     (round-trip-validation-error v)])))

   (or (vector? data) (set? data))
   (join-error-strings (map round-trip-validation-error data))

   true
   (str "Data of type " (class data) " is not allowed in config files: " data)))

(defn validation-error
  "Sees if the configuration appears valid. If it is valid, returns nil.
  Otherwise, returns a plain English string explaining which source-defs are
  invalid and why."
  [config]
  (if (not (or (map? config) (vector? config)))
    (str "config must be map or vector, but it is a " (class config))
    (join-error-strings [(source-defs-validation-error (source-defs config))
                         (round-trip-validation-error config)])))
