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

(ns hesokuri.source-def
  "Tools for interpreting a source definition in a configuration file. There are
  two kinds of source definitions: simple and extensible. The simple form is
  just a map of strings to strings, which are host identities and the local path
  of the the source on that host, respectively. Here is an example:
      {\"host-1\" \"/foo/bar/path1\"
       \"host-2\" \"/foo/bar/path2\"}
  In the extensible form, this simplified form becomes an entry in a map:
      {:host-to-path
       {\"host-1\" \"/foo/bar/path1\"
        \"host-2\" \"/foo/bar/path2\"}}
  The extensible form allows these entries:
      :host-to-path (required)
      :live-edit-branches (optional)
          Should be a map with a single entry called :only or :except, whose
          value is a set of strings that indicates what branch names should or
          should not be considered live-edit branches. For instance:
          - :live-edit-branches #{:only #{\"hesokuri\" \"master\"}}
            indicates that branches called hesokuri or master should be
            considered live-edit.
          - :live-edit-branches #{:except #{\"private\"}}
            indicates that any branch, except one called private, should be
            considered live-edit.
      :unwanted-branches (optional)
          This can be either a set or a map.

          [If it is a set]
          A set of strings which indicate unwanted branch names. For any string
          in this set FOO, branches named FOO or FOO_hesokr_* will be
          automatically deleted when they are created. This prevents other peers
          from polluting your local set of branches. Obviously, care must be
          taken when putting strings here.

          [If it is a map]
          A map of branch names to vector of Git hashes (see hesokuri.git/Hash).
          The branch is branch is deleted if some branch
          exists with the given name and points to one of those hashes.

  More entries will be added in the future to allow advanced customization of
  behavior."
  (:import [java.util Set])
  (:require [hesokuri.git :as git]
            [hesokuri.validation :as validation]))

(defn- kind
  "Returns the kind of the given def, and also performs a sanity check on it
  with assert. Possible return values are :simple and :extensible."
  [def]
  ({String :simple, clojure.lang.Keyword :extensible}
   (class (key (first def)))))

(defn- all-non-empty-strings? [s]
  (every? #(and (string? %) (not (empty? %))) s))

(defn- set-of-non-empty-strings? [s]
  (and (set? s) (all-non-empty-strings? s)))

(declare validation)

(defn- extensible-validation
  [def]
  (let [live-edit-branches-value
        ,(delay (second (first (:live-edit-branches def))))
        unwanted (delay (:unwanted-branches def))]
    (validation/conditions
     (= (kind def) :extensible)
     "expected :extensible, but got :simple"

     (:host-to-path def)
     "extensible source def requires :host-to-path key"

     (or (not (:live-edit-branches def))
         (#{[:except] [:only]} (keys (:live-edit-branches def))))
     [":live-edit-branches requires exactly one key (:except or :only): "
      (:live-edit-branches def)]

     (or (not (:live-edit-branches def))
         (set-of-non-empty-strings? @live-edit-branches-value))
     ["Value in live-edit-branches map must be set of non-empty strings: "
      @live-edit-branches-value]

     (or (nil? @unwanted)
         (set-of-non-empty-strings? @unwanted)
         (and (map? @unwanted)
              (all-non-empty-strings? (keys @unwanted))
              (every? (fn [v]
                        (and (vector? v)
                             (every? #(and (string? %) (git/full-hash? %)) v)))
                      (vals @unwanted))))
     [":unwanted-branches value must be set of non-empty strings, or a map of "
      "branch names to vectors of full Git hash strings: "
      @unwanted]

     :passes
     (validation (:host-to-path def)))))

(defn validation
  "Performs validation on the given source-def."
  [def]
  (let [first-key-class (delay (class (key (first def))))
        first-key (delay (key (first def)))
        first-non-matching-key
        (delay (some #(and (not= @first-key-class (class %))
                           (or % "nil/false"))
                     (keys def)))]
    (validation/conditions
     (map? def)
     "should be a map, e.g. {key1 value1, key2 value2}"

     (not (empty? def))
     "should have at least one entry"

     (#{String clojure.lang.Keyword} @first-key-class)
     ["all keys should be a string or keyword, but first key ("
      @first-key ") is a " @first-key-class]

     (not @first-non-matching-key)
     ["all keys should be same class, but '" @first-key
      "' is not the same class as '" @first-non-matching-key "'"]

     :passes
     (if (= (kind def) :simple)
       nil
       (extensible-validation def))

     (or (= (kind def) :extensible)
         (all-non-empty-strings? (apply concat def)))
     "all keys and values should be non-empty strings")))

(defn host-to-path
  "Returns a map of host identities (strings) to paths (strings)."
  [def]
  (case (kind def)
    :simple def
    :extensible (:host-to-path def)
    (assert false)))

(defn live-edit-branch?
  "Truthy if the branch name given is aggressively merged from the peer-
  originated version to the locally-originated version. This means it is merged
  as long as the local version is either not checked out or checked out with no
  uncommitted changes, and the peer version is a fast-forward of the local
  version."
  [def branch-name]
  {:pre [(string? branch-name)]}
  (let [live-edit-branches (or (:live-edit-branches def) {:only #{"hesokuri"}})]
    (let [except (:except live-edit-branches)
          only (:only live-edit-branches)]
      (assert (#{[:except] [:only]} (keys live-edit-branches))
              (str ":live-edit-branches entry should have one key labeled, and "
                   "it should be :except or :only: "
                   def))
      (if except
        (not (except branch-name))
        (only branch-name)))))

(defn unwanted-branches
  [def]
  (let [ubs (:unwanted-branches def)]
    (if (map? ubs)
      ubs
      (into {} (for [ub ubs] [ub ["*"]])))))

(defn unwanted-branch?
  "Truthy if the branch name given should be deleted. This includes versions of
  the branch that originate on another peer. If the branch-hash is not given,
  and the unwanted-branches map specifies unwanted hashes, then this function
  will return falsey."
  [def branch-name branch-hash]
  (let [shas ((unwanted-branches def) (str branch-name))]
    (some #{"*" (str branch-hash)}
          (map str shas))))

(defn normalize
  [def]
  (case (kind def)
    :simple
    ,(normalize {:host-to-path def})
    :extensible
    ,(assoc def
            :unwanted-branches
            (unwanted-branches def))))
