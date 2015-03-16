#Hesokuri

Distributed Git repo synchronization tool.

##Intro
Hesokuri synchronizes one or more Git source code repositories between multiple
machines on a network. It is useful in the following situations:

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

Hesokuri pushes commits on local branches to a remote branch called
`X_hesokr_Y`, where `X` is the local name of the branch and `Y` is the identity
of the host pushing the change.

####Live-edit branches
Sometimes, you will manually merge changes from `X_hesokr_Y` into the local
branch `X` when you need to access them. However, if `X` is a _live-edit_
branch, and it is a fast-forward of the local `X` branch, and the local `X`
branch is either not checked out or it is checked out and the working area and
index are clean, `X` will automatically be reset to the commit pointed to by
`X_hesokr_Y`. _This means if `X` is checked out and there are no uncommitted
changes to it, your working tree will update automatically when another peer
commits changes to it._ This process is referred to as _advancing_ in the source
code.

You can specify which branches are live-edit in the configuration file (see the
**Configure** section below).

####Sharing changes from other peers
For every branch named `X_hesokr_Y`, Hesokuri will try to push it to other peers
(besides `Y`) using the same branch name. This is useful for sharing changes
between two peers that are not running at the same time, but are running at the
same time as some third peer. In this case, the third peer acts as a carrier for
changes that did not originate from it.

##Getting started

###Requirements
All machines should have the following. Use earlier versions of each component
at your own risk.

1. The latest [Hesokuri release jar](http://github.com/google/hesokuri/releases)
2. Java Development Kit 1.7 or higher
3. Git version 1.8.5.6 or higher
4. A Unix-like OS is recommended. Hesokuri is tested on Mac OS X and Linux, but
   Windows is worth a try if you are feeling adventurous.
5. A static hostname or IP address. i.e. you should be able to do `ping FOO`
   from any machine and get a response, where `FOO` always refers to the same
   machine. This is string is called the _identity_.
6. Public-key ssh login enabled for each machine. You should not be asked for
   credentials when logging in between peers. Super-condensed directions
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
7. (alternative to 6) Be willing to use the experimental feature of authorized
   keys synchronization, documented in
   [the wiki](https://github.com/google/hesokuri/wiki/Authorized-keys-synchronization).

###Configure
Decide on a directory in your home folder to put the configuration file. If you
already have a Git repository for syncing configuration files and other assorted
files between machines, use that. These instructions assume this directory is
`~/etc` and the configuration file is `~/etc/hesocfg`.

On each peer, create a file at `~/[ETC]/.hesocfg` which specifies all Git repos
and peers to sync with. `[ETC]` can be anything you like, and also contains
other configuration files that you want to sync between machines. You are now
ready to create your configuration file.

1. Create a repo at `~/etc` (`git init ~/etc`)
2. Assuming the peers to sync have addresses `host-1` and `host-2`, create the
   configuration file at `~/etc/hesocfg` with this text:  
   ```Clojure
{:comment
 ["You can put any data here as notes to maintainers of this file."]

 :sources
 [{:host-to-path {"host-1" "/home/jdoe/etc"
                  "host-2" "/home/jdoe/etc"}
   :live-edit-branches {:only #{"master"}}}]}
```
3. Commit the file: `cd ~/etc; git add hesocfg; git commit -m 'add hesocfg'`

For detailed guidance on the configuration file, see
[the wiki](https://github.com/google/hesokuri/wiki/Configuration-file).

If a repo or any containing directory does not exist on a peer and you start
Hesokuri on it, the containing directory and repo will be created automatically.
This means that, in the above example, if the `hacks` folder containing the two
repos does not exist on `192.168.0.4`, you can run Hesokuri anyway,
`192.168.0.4` will create the folders and initialize empty Git repos, and other
peers will push the two repos to it as soon as they establish a connection.

###Run
To run, execute `java -jar hesokuri.jar` at the command line. This starts
Hesokuri in background mode, which means it is monitoring the repos for changes
and pushing them or merging them automatically. Hesokuri also has sub-commands
which may be handy. Run `java -jar hesokuri.jar help` for more information.

When you first run Hesokuri, check the output occassionally to see if it shows
prompts for passwords or to confirm host keys. (It is doing this through your
`ssh` client, of course). If you see it, then the `known_hosts` and/or
`authorized_keys` files are not up-to-date. `known_hosts` can be updated by
manually ssh-ing to the host machine, (this can be impractical for large numbers
of hosts). Updating `authorized_keys` requires more effort, although Hesokuri
can help with this - see the **Requirements** section above.

###Web interface
When Hesokuri is running in background mode, you can go to
<http://localhost:8080> in a web browser to see the status of all sources and
last-pushed hashes for each branch and peer. The port can be changed from the
default of 8080 by setting the environment variable `HESOPORT` to the desired
port number.

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
most recent version of a branch. You can also use the web interface to force an
immediate push for peers that failed their last ping.
