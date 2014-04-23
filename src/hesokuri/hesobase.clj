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
with the address used to access the peer (we may want to make this more flexible
later, allowing multiple addresses or allowing that the name is mnemonic in
cases where the address is an IP or something arbitrary.)

When a repo {path} is referred to, this is the path using '/' as the path
component separator, even if it is in the context of a peer that does not use
that as a path component separator.

FOR EACH PEER
-------------

In file called 'main'
:port int
- The port on which the peer listens for Hesokuri connections.
:repo-root string
- The root path for all repositories. All repo paths are relative to this base.
  The string is a Clojure string literal, e.g. \"foo/bar\", including the
  quotes.

In file called 'key'
A Java-serialized instance of the RSA public key
(result of (.getPublic (hesokuri.ssh/new-keypair)))

Empty files named 'repo/{path}'
The {path} portion is percent-encoded, since we need to include directory
separators in the name. The presence of the file indicates that the peer has a
copy of the. This can some day be changed to a directory or a non-empty file to
hold more information. This file allows the user to configure which repos appear
on which peers.

FOR EACH REPO
-------------

In file called 'live-edit/only' (optional)
List of branch names that are considered live edit branches. Any branch not
listed here IS NOT live edit. Names are raw strings separated by a newline.

In file called 'live-edit/except' (optional)
List of branch names that are NOT considered live edit branches. Any branch not
listed here IS a live edit branch. Names are raw strings separated by a newline.

In file called 'unwanted/{branch-name}' (optional)
Binary sequence of SHAs, with no delimiters, sorted numerically. The meaning of
each SHA: any branch where the SHA listed is a fast-forward of the branch's SHA
should be deleted. It will probably be very common for each branch-name to only
have a single SHA listed, however, by allowing multiple SHAs, you can later
re-use the same branch name for newer work several times.
")
