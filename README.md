#Hesokuri

Distributed git repo backup and duplication daemon.

##Intro

Hesokuri is a daemon utility that synchronizes one or more git source code
repositories between multiple machines on a network. It is useful in the
following situations:

1. You want the piece of mind of controlling the machines where your source code
   is stored.
2. You control/own multiple machines and work on the same project on them.
3. You want changes on one machine to appear on all other machines as soon as
   they are connected.
4. There is enough disk capacity on the machines such that each repository can
   fit on two or more peers.
5. The data to store is confidential or sensitive so it cannot be stored on a
   third-party’s machine or on a traditional cloud storage system.
6. You want to use existing hardware rather than buy a dedicated backup device.

##Getting started

###Requirements
All machines should have the following. Use earlier versions of each component
at your own risk.

1. One of:
   - Leiningen 2.1.3 or higher, and Hesokuri source
   - Release jar of Hesokuri
2. Java runtime 1.7 or higher
3. git version 1.7.10.1 or higher
4. A Unix-like OS is recommended. Hesokuri is tested on Mac OS X and Linux, but
   Windows is worth a try if you are feeling adventurous.
5. A static hostname or IP address. i.e. you should be able to do `ping FOO`
   from any machine and get a response, where `FOO` always refers to the same
   machine. This is string is called the _identity_.
6. Public-key ssh login enabled for each machine. Super-condensed directions
   which will work for most cases (`CENTRAL` is the identity of some arbitrary
   "central peer"):
   - Enable ssh login on each machine if it is not already
   - For each peer, run `ssh-keygen -t rsa` (enter an empty string when asked
     for a passphrase)
   - For each peer `FOO` (including `CENTRAL`), run:  
     `scp ~/.ssh/id_rsa.pub CENTRAL:/tmp/id_rsa.pub.FOO`
   - After the previous command has been run on all peers, run the following on
     `CENTRAL` for each peer `FOO`:  
     `cat /tmp/id_rsa.pub.* > /tmp/heso_keys`
   - For each peer FOO (including CENTRAL), run  
     `scp CENTRAL:/tmp/heso_keys /tmp/heso_keys`  
     `cat /tmp/heso_keys >> ~/.ssh/authorized_keys`

###Configure
On each peer, create a file at `~/.hesocfg` which specifies all git repos and
peers to sync with. Each copy of the file can be the same. The file should
contain a Clojure expression (comments are allowed) that is a vector of maps.
Each element in the vector is a different git repository to sync (called a
_source_). The map is a dictionary of peer identities to local paths on each
peer for the source. A source need not appear on every peer. Here is an example
which demonstrates the expression syntax:
```Clojure
[{"host-1" "/foo/bar/path1"
  "host-2" "/foo/bar/path2"}
 {"host-1" "/foo/bar/path3"
  "host-3" "/foo/bar/path4"}]
```

An annotated, real-world configuration file may look something like this:
```Clojure
; My machines:
; 192.168.0.2 - the Linux laptop
; 192.168.0.3 - the Mac desktop
; 192.168.0.4 - the home server
[; Whiz bang project
 {"192.168.0.2" "/home/johndoe/whizbang"
  "192.168.0.3" "/Users/johndoe/whizbang"
  "192.168.0.4" "/home/johndoe/whizbang"}
 ; hacking and small side projects
 {"192.168.0.3" "/Users/johndoe/hacks/lisp-snippits"
  "192.168.0.4" "/home/johndoe/hacks/lisp-snippits"}
 {"192.168.0.3" "/Users/johndoe/hacks/game-engine"
  "192.168.0.4" "/home/johndoe/hacks/game-engine"}]
```

Note that you can save the configuration file in a location other than
`~/.hesocfg` and set the environment variable `HESOCFG` to its location. This
way, you can store the `HESOCFG` in a subdirectory and put it in a git repo to
sync (along with other miscellaneous configuration files and utilities that are
shared between all systems).

If a repo or any containing directory does not exist on a peer and you start the
Hesokuri daemon on it, the containing directory and repo will be created
automatically on Hesokuri start up. This means that, in the above example, if
the `hacks` folder containing the two repos does not exist on `192.168.0.4`, you
can run Hesokuri anyway, `192.168.0.4` will create the folders and initialize
empty git repos, and other peers will push the two repos to it as soon as they
establish a connection to `192.168.0.4`.

###Run
If you are running from source, switch to the directory containing Hesokuri and
run `lein run`. If you are running from the precompiled jar and it is located at
some path `HESOKURI_JAR`, simply run `java -cp HESOKURI_JAR hesokuri.main`

###Web interface
You can go to <http://localhost:8080> in a web browser on any peer to see the
status of all sources and last-pushed hashes for each branch and peer. The port
can be changed from the default of 8080 by setting the environment variable
`HESOPORT` to the desired port number.
