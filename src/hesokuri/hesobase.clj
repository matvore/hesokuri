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
file, but the same kind of objects (repo, peer) are present. Each object
is represented by a directory:

/            # repo root
/peer/       # contains all peers
/peer/{name} # contains information on peer named {name}
/repo/       # contains all repos
/repo/{path} # contains information on repo at path {path}

{name} and {path} are percent-encoded. The {name} of a peer corresponds exactly
with the address used to access the peer.

When a repo {path} is referred to, this is the path using '/' as the path
component separator, even if it is in the context of a peer that does not use
that as a path component separator.

When {name}, {path}, or {branch-name} appear in a directory or file name, it is
percent-encoded.

FOR EACH PEER
-------------

In file called 'port'
A plain-text integer indicating the port on which the peer listens for Hesokuri
connections.

In file called 'repo-root'
The root path for all repositories, as a raw string. All repo paths are relative
to this base.

In file called 'key'
A Java-serialized instance of the RSA public key
(result of (.getPublic (hesokuri.ssh/new-key-pair)))

Empty files named 'repo/{path}'
The presence of the file indicates that the peer has a copy of the repo. This
file allows the user to configure which repos appear on which peers.

FOR EACH REPO
-------------

Empty files called 'live-edit/only/{branch-name}' (optional)
Indicates a branch is considered a live edit branch. Any branch not listed here
IS NOT live edit.

Empty files called 'live-edit/except/{branch-name}' (optional)
List of branch names that are NOT considered live edit branches. Any branch not
listed here IS a live edit branch.

The live-edit/only and live-edit/except directories cannot both exist for a single repo.

Empty files called 'unwanted/{branch-name}/{hash}' (optional)
The presence of such a file tells Hesokuri to delete any branch where the {hash}
listed is a fast-forward or the same as the {branch-name}'s SHA. It will
probably be very common for each branch-name to only have a single SHA listed,
but by allowing multiple SHAs, you can later re-use the same branch name for
newer work.

FUTURE IMPROVEMENTS
-------------------

The name of the peer and its address are the same thing. Allow multiple
addresses or allow the name to be mnemonic in cases where the address is an IP
or something arbitrary.)
***
There are some instances of 'empty files.' Each empty file may some day be
changed to a directory or a non-empty file to hold more information.
***
Merge conflicts that cannot be resolved automatically should be summarized in
some kind of log in the repo, so in the off-chance it happens, the user can
recover.
")
