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
  More entries will be added in the future to allow advanced customization of
  behavior.")

(defn- kind
  "Returns the kind of the given def, and also performs a sanity check on it
  with assert. Possible return values are :simple and :extensible."
  [def]
  ({String :simple, clojure.lang.Keyword :extensible}
   (class (key (first def)))))

(defn validation-error
  "Sees if the given source-def appears valid. If it is valid, returns nil.
  Otherwise, returns a plain English string explaining one of the errors in it."
  [def]
  (letfn [(first-key-class [] (class (key (first def))))
          (first-key [] (key (first def)))
          (first-non-matching-key []
            (some #(and (not= (first-key-class) (class %))
                        (or % "nil/false"))
                  (keys def)))
          (has-non-string-or-empty-string? [seq]
            (some #(or (not= String (class %)) (= "" %)) seq))
          (live-edit-branches-value []
            (second (first (:live-edit-branches def))))]
    (cond
     (not (map? def)) "should be a map, e.g. {key1 value1, key2 value2}"
     (empty? def) "should have at least one entry"

     (not (#{String clojure.lang.Keyword} (first-key-class)))
     (str "all keys should be a string or keyword, but first key ("
          (first-key) ") is a" (first-key-class))

     (first-non-matching-key)
     (str "all keys should be same class, but '" (first-key)
          "' is not the same class as '" (first-non-matching-key) "'")

     (= (kind def) :extensible)
     (cond
      (not (:host-to-path def))
      "extensible source def requires :host-to-path key"

      (and (:live-edit-branches def)
           (not (#{[:except] [:only]} (keys (:live-edit-branches def)))))
      (str ":live-edit-branches requires exactly one key (:except or :only): "
           (:live-edit-branches def))

      (and (:live-edit-branches def)
           (or (not (set? (live-edit-branches-value)))
               (has-non-string-or-empty-string? (live-edit-branches-value))))
      (str "Value in live-edit-branches map must be set of non-empty strings: "
           (live-edit-branches-value))

      :else
      (validation-error (:host-to-path def)))

     (has-non-string-or-empty-string? (apply concat def))
     "all keys and values should be non-empty strings"

     :else nil)))

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
