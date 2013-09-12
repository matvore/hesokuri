#Hesokuri

Distributed git repo backup and duplication daemon.

##Intro

Hesokuri is a daemon utility that synchronizes one or more git source code
repositories between multiple machines on a network. It is useful in the
following situations:

1. You want the peace of mind of controlling the machines where your source code
   is stored.
2. You control/own multiple machines and work on the same project on them.
3. You want changes on one machine to appear on all other machines as soon as
   they are connected.
4. There is enough disk capacity on the machines such that each repository can
   fit on two or more peers.
5. The data to store is confidential or sensitive so it cannot be stored on a
   third-party’s machine or on a traditional cloud storage system.
6. You want to use existing hardware rather than buy a dedicated backup device.

###What Hesokuri does
Simply stated, as soon as you commit a change in a repository, Hesokuri attempts
to push the changes to every peer that holds an instance of the repository in
the configuration file.

Hesokuri generally pushes commits on local branches to a remote branch called
`X_hesokr_Y`, where `X` is the local name of the branch and `Y` is the identity
of the peer (actually, it sometimes tries to push to the branch of the _same_
name, but this is just a shortcut to bypass what is explained below, so you
don't have to think about it during normal use).

The rest of this section goes into a lot of detail about Hesokuri behavior. If
you want, you can skip to the "Getting started" section below.

####Sharing changes from other peers
For every branch named `X_hesokr_Y`, Hesokuri will try to push it to other peers
(besides `Y`) using the same branch name. This is useful for sharing changes
between two peers that are not running at the same time, but are running at the
same time as a third machine that serves as the carrier.

####The live edit branch
Most of the time, you will manually merge changes from `X_hesokr_Y` into the
local branch `X` when you need to access them. However, if `X` is actually
`hesokuri` (the _live edit_ branch) and it is a fast-forward of the local
`hesokuri` branch, and the local `hesokuri` branch is either not checked out or
it is checked out and the working area and index are clean, `hesokuri` will
automatically be reset to the commit pointed to by `hesokuri_hesokr_Y`. This
means if `hesokuri` is checked out and there are no uncommitted changes to it,
your working tree will update automatically when another peer commits changes to
it. This process is referred to as _advancing_ in the source code.

Notice that some of this behavior should be improved and made more configurable.
See [issue #1](https://github.com/google/hesokuri/issues/1) and
[issue #3](https://github.com/google/hesokuri/issues/3) for examples.

##Getting started

###Requirements
All machines should have the following. Use earlier versions of each component
at your own risk.

1. Hesokuri source
2. [Leiningen](http://leiningen.org/) 2.1.3 or higher
3. Java runtime 1.7 or higher
4. git version 1.7.10.1 or higher
5. A Unix-like OS is recommended. Hesokuri is tested on Mac OS X and Linux, but
   Windows is worth a try if you are feeling adventurous.
6. A static hostname or IP address. i.e. you should be able to do `ping FOO`
   from any machine and get a response, where `FOO` always refers to the same
   machine. This is string is called the _identity_.
7. Public-key ssh login enabled for each machine. Super-condensed directions
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

##More information

- The [wiki on Github](https://github.com/google/hesokuri/wiki) documents
  things that don't quite fit in the README, like the contribution process.
- Join the [Google group](https://groups.google.com/forum/#!forum/hesokuri)
  to get tips, discuss possible improvements to the tool, etc.

##FAQ

###Where does the name of the project come from?

From the Japanese word meaning "secret cash hoard." It was chosen because this
tool enables a kind of "hoarding" of data on a personal machine. The name also
contrasts this practice with the alternative of storing your data on a third
party server, while the alternative to a hesokuri is storing your money in the
family bank account.

###Does Hesokuri support synchronizing bare repositories?

Hesokuri is mostly tested with non-bare repositories with their own working
trees. Bare repositories should also work, assuming they are initialized
manually with `git init --bare` in the directory specified by the configuration
file.

###What happens when some peer is unreachable?

Before pushing a branch to a remote peer, Hesokuri makes sure it is responsive
with [`InetAddress.isReachable`](http://goo.gl/VnJL7o) (essentially a ping). If
it is not responsive, Hesokuri will not push anything to that peer. Every three
minutes, Hesokuri attempts a ping and push for every peer that does not have the
most recent version of a branch regardless of their last failed ping attempt.
You can also use the web interface to force an immediate push for peers that
failed their last ping.
