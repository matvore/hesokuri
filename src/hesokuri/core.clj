(ns hesokuri.core
  (:use [clojure.java.shell :only [sh]]
        [clojure.string :only [join split trim]]
        hesokuri.util hesokuri.source hesokuri.branch-name)
  (:import [java.io File]
           [java.util Date])
  (:gen-class))

(def sources
  "Defines the hesokuri sources. This is the user-configurable settings that
hesokuri needs to discover sources on the network and how to push and pull
them. This can be read from a configuration file. It is a map in the following
form:
 [
   {
     \"host-1\" \"/foo/bar/path1\"
     \"host-2\" \"/foo/bar/path2\"
   }
   {
     \"host-1\" \"/foo/bar/path3\"
     \"host-3\" \"/foo/bar/path4\"
   }
 ]"
  (ref []))

(def source-agents
  "A map of source-dirs to the corresponding agent."
  (ref {}))

(def peer-hostnames
  "A set of the hostnames of the peers. Updated with sources."
  (ref #{}))

(def local-identity
  "The hostname or IP of this system as known by the peers on the current
network."
  (ref nil))

(def sources-config-file
  "Where to read the hesokuri sources configuration from."
  (str (.get (System/getenv) "HOME") "/.hesokuri/sources"))



(defn identities
  "Returns a vector of the possible identities this system may have on the
network, which includes its hostname and the IP address of all network
interfaces. Each identity is a string."
  []
  (conj
  (let [interfaces (vector-from-enum
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
     (not candidates) nil
     (@peer-hostnames (first candidates)) (first candidates)
     :else (recur (next candidates)))))

(defn refresh-sources
  "Updates sources and peer-hostnames based on the user's sources config file."
  []
  (dosync
   (ref-set sources (read-string (slurp sources-config-file)))
   (ref-set peer-hostnames (set (apply concat (map keys @sources))))
   (ref-set local-identity (-local-identity))
   (ref-set source-agents
            (into {} (for [source @sources
                           :let [source-dir (source @local-identity)]
                           :when source-dir]
                       [source-dir {:source-dir source-dir}]))))
  (doseq [source-agent @source-agents]
    (send source-agent git-init)))

(defn common-sources
  "Returns a list of all items in the sources vector that are on all of the
given peers."
  [& peer-names]
  (loop [sources (seq (dosync (ensure sources)))
         results []]
    (cond
     (not sources) results

     (every? #((first sources) %) peer-names)
     (recur (next sources) (conj results (first sources)))

     :else
     (recur (next sources) results))))

(defn kuri!
  "A very stupid implementation of the syncing process, ported directly from the
Elisp prototype. This simply pushes and pulls every repo with the given peer."
  [peer-name]
  (let [me @local-identity
        sources (and me (seq (common-sources peer-name me)))
        remote-track-name (str (->BranchName "master" me))]
    (cond
     (not me)
     (println "Local identity not set - cannot kuri")

     (not sources)
     (println "Could not find any sources on both " peer-name " and " me)

     :else
     (println "\n\nkuri operation at " (str (Date.))
              " with peer: " peer-name))
    (doseq [source sources
            :let [source (first sources)
                  local-path (source me)
                  local-path-file (File. local-path)
                  peer-path (source peer-name)
                  peer-repo (str "ssh://" peer-name peer-path)]]
      (cond
       (not (.exists local-path-file))
       (sh-print "git" "clone" peer-repo local-path)

       (not (.isDirectory local-path-file))
       (throw (RuntimeException.
               (str "path for repo is occupied by a non-directory file: "
                    local-path)))

       :else
       (loop [ops [:push-straight :pull]]
         (cond
          (= :push-straight (first ops))
          (if (not= 0 (sh-print "git" "push" peer-repo
                                  "master" :dir local-path))
            (recur (cons :push (next ops)))
            (recur (next ops)))

          (= :push (first ops))
          (do
            (sh-print "git" "push" peer-repo
                      (str "master:" remote-track-name) :dir local-path)
            (recur (next ops)))

          (= :pull (first ops))
          (do
            (sh-print "git" "pull" peer-repo "master" :dir local-path)
            (recur (next ops)))))))))

(defn -main
  "I don't do a whole lot ... yet."
  [& args]
  ;; work around dangerous default behaviour in Clojure
  (alter-var-root #'*read-eval* (constantly false))
  (println "Hello, World!"))
