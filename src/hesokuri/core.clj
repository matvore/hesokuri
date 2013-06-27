(ns hesokuri.core
  (:use [clojure.java.shell :only [sh]])
  (:use [clojure.string :only [trim split]])
  (:gen-class))

(def sources
  "Defines the hesokuri sources. This is the user-configurable settings that
hesokuri needs to discover sources on the network and how to push and pull
them. This can be read from a configuration file. It is a map in the following
form:
{
  \"source-name-1\" {
    \"host-1\" \"/foo/bar/path1\"
    \"host-2\" \"/foo/bar/path2\"
  }
  \"source-name-2\" {
    \"host-1\" \"/foo/bar/path3\"
    \"host-3\" \"/foo/bar/path4\"
  }
}"
  (ref {}))

(def peer-hostnames
  "A set of the hostnames of the peers. Updated with sources."
  (ref #{}))

(def local-identity
  "The hostname or IP of this system as known by the peers on the current
network."
  (ref "localhost"))

; Represents a git branch on some system. origin is omitted when it is the
; canonical version of the branch. location is omitted when it is located on the
; local system.
(defrecord git-branch [location origin root-name])

(def pushed-hashes
  "Indicates the last pushed hash of branches on peer systems. Is a map of
git-branch records to sha-hash strings."
  (ref {}))

(def local-hashes
  "Indicates the hash of each branch stored locally. Is a map of git-branch
records to sha-hash strings. The 'location' property of each git-branch is
omitted."
  (ref {}))

(def sources-config-file
  "Where to read the hesokuri sources configuration from."
  (str (.get (System/getenv) "HOME") "/.hesokuri_sources"))

(defn -vector-from-enum [enum]
  (vec (java.util.Collections/list enum)))

(defn identities
  "Returns a vector of the possible identities this system may have on the
network, which includes its hostname and the IP address of all network
interfaces. Each identity is a string."
  []
  (conj
  (let [interfaces (-vector-from-enum
                    (java.net.NetworkInterface/getNetworkInterfaces))
        address-of (fn [addr]
                     (first (split (.getHostAddress (.getAddress addr)) #"%")))]
    (loop [res [] i (seq interfaces)]
      (cond
       (not i) res
       (nil? (first i)) (recur res (next i))
       :else
       (recur (into res
         (loop [subres [] addrs (seq (.getInterfaceAddresses (first i)))]
           (cond
            (not addrs) subres
            (nil? (first addrs)) (recur subres (next addrs))
            :else (recur (conj subres (address-of  (first addrs)))
                         (next addrs)))))
              (rest i)))))
  (trim (:out (sh "hostname")))))

(defn -local-identity
  "Returns the identity of this system. It deduces it from the (identities)
vector and the peer-hostnames var."
  []
  (loop [candidates (seq (identities))]
    (cond
     (not identities) nil
     (@peer-hostnames (first candidates)) (first candidates)
     :else (recur (next candidates)))))

(defn refresh-sources
  "Updates sources and peer-hostnames based on the user's sources config file."
  []
  (dosync
   (alter sources
          (fn [_] (read-string (slurp sources-config-file))))
   (alter peer-hostnames
          (fn [_] (set (apply concat (map keys (vals @sources))))))
   (alter local-identity
          (fn [_] (-local-identity)))
   (list @sources @peer-hostnames @local-identity)))

(defn -main
  "I don't do a whole lot ... yet."
  [& args]
  ;; work around dangerous default behaviour in Clojure
  (alter-var-root #'*read-eval* (constantly false))
  (println "Hello, World!"))
