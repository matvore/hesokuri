; Copyright (C) 2014 Google Inc.
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

(ns hesokuri.hesobase
  "Module for reading and writing the Hesokuri database. The database is a bare
Git repository that contains configuration and state information about the repos
synced by Hesokuri. The master branch is the configuration that is active on the
local machine. Merges are managed with Hesokuri-specific logic that is aware of
the semantics of the data.

Data is organized as a file system, rather than a single flat file. This makes
the database more storage-efficient when updated frequently.

The Hesobase has slightly different information from the original configuration
file, but the same kind of objects (source, peer) are present. Each object
is represented by a directory:

/              # repo root
/peer/         # contains all peers
/peer/{name}   # contains information on peer named {name}
/source/       # contains all sources (synced repos)
/source/{name} # contains information on source named {name}
/log/...       # a command log, used for merging (see COMMAND LOG)

{name} for source and peer is percent-encoded. The {name} of a peer corresponds
exactly with the address used to access the peer.

When {name}, {path}, or {branch-name} appear in a directory or file name, it is
percent-encoded.

FOR EACH PEER
-------------

In file called 'port'
A plain-text integer indicating the port on which the peer listens for Hesokuri
connections.

In file called 'key'
A Java-serialized instance of the RSA public key
(result of (.getPublic (hesokuri.ssh/new-key-pair)))

Files named 'source/{name}'
The presence of the file indicates that the peer has a copy of the source named
{name}. The contents of the file is a raw String indicating the path of the
source on the peer. If the path is relative, it is relative to hesoroot on that
peer.

FOR EACH SOURCE
---------------

Empty files called 'live-edit/only/{branch-name}' (optional)
Indicates a branch is considered a live edit branch. Any branch not listed here
IS NOT live edit.

Empty files called 'live-edit/except/{branch-name}' (optional)
List of branch names that are NOT considered live edit branches. Any branch not
listed here IS a live edit branch.

The live-edit/only and live-edit/except directories cannot both exist for a
single source.

Empty files called 'unwanted/{branch-name}/{hash}' (optional)
The presence of such a file tells Hesokuri to delete any branch with the given
name and SHA-1 hash. It will probably be very common for each branch-name to
only have a single SHA listed, but by allowing multiple SHAs, you can later
re-use the same branch name for newer work.

COMMAND LOG
-----------

For each command that caused the hesobase to change, information about the
command is stored in order to facilitate merging later on. This information
includes the timestamp of the action, the command name, the arguments, and (if
applicable) the error message. Each command is represented by a single blob. The
blob itself is empty if the command was successful; otherwise, it is a
human-readable error message. The blob path is as follows:

/log/{timestamp}/{cmd}/{args}

{timestamp} is 16 path segments, each a single, lowercase hex character. This
corresponds to a 64-bit value which is the timestamp in milliseconds (see
java.lang.System.currentTimeMillis). {cmd} is a single path segment representing
the command name, and {args} is a single path segment representing the arguments
to the command, which may be anything, including for instance a percent-encoded
Clojure vector literal.

FUTURE IMPROVEMENTS
-------------------

The name of the peer and its address are the same thing. Allow multiple
addresses or allow the name to be mnemonic in cases where the address is an IP
or something arbitrary.)
***
There are some instances of 'empty files.' Each empty file may some day be
changed to a directory or a non-empty file to hold more information."
  (:require [clojure.java.io :as cjio]
            [hesokuri.git :as git]
            [hesokuri.ssh :as ssh]
            [hesokuri.transact :as transact]
            [hesokuri.util :refer :all]))

(defn log-blob-path
  "Returns the path to place a log blob. See the COMMAND LOG section of the
  hesobase namespace documentation for detailed information. The path is
  returned as a vector of path segments."
  [timestamp cmd args]
  (concat ["log"]
          (map str (seq (format "%016x" timestamp)))
          [(%-encode (pr-str args))]))

(defn source-name?
  "Detects whether the given value can be used as a source name. See
  source-name-spec for values that this function will return true for."
  [name]
  (and (string? name)
       (not-empty name)
       (every? #(or (like int <= \a % \z)
                    (like int <= \A % \Z)
                    (like int <= \0 % \9)
                    (= \- %)
                    (= \_ %))
               name)))

(def source-name-spec
  "Human-readable string describing what is allowed in a source name."
  (str "A source name is a non-empty string containing alpha-numeric "
       "characters, hyphens (-), and/or underscores (_)."))

(defn peer-names
  "Returns the names of every peer in the hesobase given by 'tree'."
  [tree]
  (let [[unmatched [_ _ _ peer-tree]] (git/get-entry ["peer"] tree)]
    (if (not-empty unmatched)
      []
      (map #(nth % 1) peer-tree))))

(def cmd-map
  "Maps each command name to a function that implements it. A command is an
  atomic hesobase operation that may fail and is comprehensible to a human.
  Each one corresponds to an action that a user may perform to configure
  Hesokuri.

  The command names - or keys - of this map are Strings. The mapped values are
  functions that take a tree corresponding to the hesobase repo followed by any
  number of String arguments.

  Each function returns either the new tree of the hesobase repo, or an error
  message as a String. The function does NOT add a log entry."
  {"add-peer"
   (fn [tree machine-name port key]
     (if (git/can-add-blob? (git/get-entry ["peer" machine-name] tree))
       (->> tree
            (git/add-blob ["peer" machine-name "port"] port)
            (git/add-blob ["peer" machine-name "key"] key))
       (str "There is already a machine named: " machine-name)))

   "new-source"
   ;;; Adds a source to every peer. The path of the source is equal to the name
   ;;; of the source. Error if no peers exist, or all peers already have the
   ;;; given source.
   (fn [tree source-name]
     (if-not (source-name? source-name)
       (format "Not a valid source name. %s (%s)" source-name-spec source-name)
       (loop [tree tree
              to-add (peer-names tree)
              added 0]
         (if (empty? to-add)
           (if (zero? added)
             (str "No peers to add source to: " source-name)
             tree)
           (let [source-blob-path
                 ["peer" (first to-add) "source" source-name]]
             (if (git/can-add-blob? (git/get-entry source-blob-path tree))
               (recur (git/add-blob source-blob-path source-name tree)
                      (rest to-add)
                      (inc added))
               (recur tree
                      (rest to-add)
                      added)))))))})

(defn cmd
  "Executes a command, usually one in the cmd-map, altering the given tree (if
  successful) and adding a log entry.

  cmd-result, log-path - Give if the command has already been run. cmd-result is
      the result of calling a function in cmd-map, and log-path is a value
      returned by log-blob-path.

  cmd-name - Name of the command to execute.
  timestamp-ms - When the command is executing. See System/currentTimeMillis.
  args - Vector of Strings representing the arguments to the command.
  tree - The tree representing the hesobase before the command was executed."
  ([cmd-result log-path tree]
     (if (string? cmd-result)
       [(git/add-blob log-path cmd-result tree) cmd-result]
       [(git/add-blob log-path "" cmd-result) ""]))
  ([cmd-name timestamp-ms args tree]
     {:pre [(vector? args)
            (every? string? args)]}
     (cmd (apply (cmd-map cmd-name) tree args)
          (log-blob-path timestamp-ms cmd-name args)
          tree)))

(defn init
  "Initializes the hesobase repository with the information of a single peer.
  Returns the hash of the first commit.

  git-ctx - instance of hesokuri.git/Context
  machine-name - the name of the first peer.
  port - the port on which the first peer listens for Hesokuri connections.
  key - the key of the first peer. Before storing, this will be coerced with
      ssh/public-key-str.
  author - the author string of the first commit in the hesobase repo. See
      hesokuri.git/author.
  timestamp - value returned by System/currentTimeMillis."
  [git-ctx machine-name port key author timestamp]
  (git/throw-if-error (git/invoke-with-summary git-ctx "init" ["--bare"]))
  (let [port (str port)
        key (ssh/public-key-str key)
        [tree err-msg] (cmd "add-peer" timestamp [machine-name port key] [])
        commit-hash
        ,(git/write-commit git-ctx [["tree" nil tree]
                                    ["author" author]
                                    ["committer" author]
                                    [:msg "executing hesobase/init\n"]])]
    (when (seq err-msg)
      (throw (ex-info err-msg {})))
    (git/throw-if-error
     (git/invoke-with-summary
      git-ctx "update-ref" ["refs/heads/master" (str commit-hash) ""]))
    commit-hash))

(defn tree->config
  "Converts a tree to the config format, which is defined by
  hesokuri.config/validation. The tree is in the format returned by
  hesokuri.git/read-tree with hesokuri.git/read-blob as the blob reader."
  [tree]
  (letfn [(reducer [config [path & blob-detail]]
            (case (path 0)
              "peer"
              ,(let [peer-name (%-decode (path 1))
                     blob-str (nth blob-detail 1)]
                 (case (path 2)
                   "port"
                   ,(assoc-in config
                              [:host-to-port peer-name]
                              (Integer/parseInt blob-str))
                   "key"
                   ,(assoc-in config
                              [:host-to-key peer-name]
                              (ssh/public-key blob-str))
                   "source"
                   ,(let [source-path (%-decode (path 3))]
                      (assoc-in config
                                [:source-name-map source-path :host-to-path
                                 peer-name]
                                blob-str))))
              "source"
              ,(let [source-name (%-decode (path 1))]
                 (case (path 2)
                   "live-edit"
                   ,(let [kind (keyword (path 3))
                          branch-name (%-decode (path 4))]
                      (conj-in config
                               [:source-name-map source-name :live-edit-branches
                                kind]
                               branch-name
                               #{}))
                   "unwanted"
                   ,(let [branch-name (%-decode (path 3))
                          branch-hash (path 4)]
                      (conj-in config
                               [:source-name-map source-name :unwanted-branches
                                branch-name]
                               branch-hash
                               []))))))
          (add-sources-vector [config]
            (assoc config :sources (vec (vals (:source-name-map config)))))]
    (->> (git/blobs tree)
         (reduce reducer {:source-name-map (sorted-map)})
         add-sources-vector)))
