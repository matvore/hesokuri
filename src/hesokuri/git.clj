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

(ns hesokuri.git
  "Module that facilitates invoking git."
  (:require clojure.java.shell)
  (:use [clojure.string :only [join]]))

(def default-git
  "A Git object which invokes the 'git' tool from the PATH."
  {:path "git"})

(defn git?
  "Returns true iff x is a valid git object."
  [x]
  (and (map? x)
       (= 1 (count x))
       (string? (:path x))
       (not (empty? (:path x)))))

(defn invoke-result?
  "Returns true iff x is a valid result of a call to invoke. Note that this has
nothing to do with whether the result indicates a successful invocation."
  [x]
  (and (map? x)
       (= 3 (count x))
       (integer? (:exit x))
       (string? (:out x))
       (string? (:err x))))

(defn args? [x]
  (and (or (seq? x)
           (vector? x)
           (list? x))
       (every? string? x)))

(defn invoke
  "Invokes git with the given arguments, and returns a value in the same form as
clojure.java.shell/sh. 'git' is the Git object to use. 'args' is a sequence of
strings to pass to git."
  [git args]
  {:pre [(args? args) (git? git)]}
  (apply clojure.java.shell/sh (:path git) args))

(defn summary
  "Returns a user-readable summary of the result of 'invoke' as a string."
  [args invoke-result]
  {:pre [(args? args) (invoke-result? invoke-result)]}
  (format "execute: git %s\nstderr:\n%sstdout:\n%sexit: %d\n"
          (join " " args) (:err invoke-result) (:out invoke-result)
          (:exit invoke-result)))

(defn invoke-with-summary
  "Calls invoke and returns two items in a sequence: the result of invoke
followed by a string which is the summary. The summary part of the sequence is
lazy."
  [git args]
  {:pre [(git? git) (args? args)]}
  (let [result (invoke git args)]
    (cons result (lazy-seq [(summary args result)]))))
