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
  More entries will be added in the future to allow advanced customization of
  behavior.")

(defn- kind
  "Returns the kind of the given def, and also performs a sanity check on it
  with assert. Possible return values are :simple and :extensible."
  [def]
  (let [[first-key & rest-keys] (keys def)
        key-class-to-kind {String :simple, clojure.lang.Keyword :extensible}
        result (key-class-to-kind (class first-key))]
    (assert (every? #(= (class first-key) (class %)) rest-keys)
            (str "All keys in source-def should be same class, but are: "
                 (keys def)))
    (assert result (str "Keys in source-def should be one of: "
                        (keys key-class-to-kind)))
    (when (= result :extensible)
      (assert (:host-to-path def)
              (str "extensible source-def requires :host-to-path: " def))
      (assert (= :simple (kind (:host-to-path def)))
              (str ":host-to-path entry should look like simple source-def: "
                   (:host-to-path def))))
    result))

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
  ;; Validate def, even though we don't need to know the kind:
  (kind def)
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
