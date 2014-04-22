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
the database more storage-efficient when updated frequently. Data in a single
file is stored as a series of key/value pairs, with each pair on a line, and
separated by a space. The key and value are plain Clojure literals.

The Hesobase has slightly different information from the original configuration
file, but the objects (repo, peer) are the same. Each object (repo, peer)
corresponds to a directory:

/ # repo root
/peer/ # contains all peers
/peer/{name} # contains information on peer named {name}
/repo/ # contains all repos
/repo/{path} # contains information on repo at path {path}

{name} and {path} are percent-encoded.

For each peer:
In file called 'main'
:port int
- The port on which the peer listens for Hesokuri connections.
:repo-root string
- The root path for all repositories. All repo paths are relative to this base.
In file called 'key'
A Java-serialized instance of the RSA public key
(result of (.getPublic hesokuri.ssh/new-keypair))
")
