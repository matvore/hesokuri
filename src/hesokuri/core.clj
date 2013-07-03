(ns hesokuri.core
  (:use [clojure.java.shell :only [sh]]
        [clojure.string :only [join split trim]]
        hesokuri.branch-name
        hesokuri.peer
        hesokuri.source
        hesokuri.util)
  (:import [java.io File]
           [java.util Date])
  (:gen-class))

(def heso
  (agent {:push-to-peers (agent (atom nil))

          :config-file
          (-> (System/getenv) (.get "HOME") (str "/.hesokuri/sources"))}))

(defn ips
  "Returns the IP addresses of all network interfaces as a vector of strings."
  []
  (into [] (for [i (-> (java.net.NetworkInterface/getNetworkInterfaces)
                       java.util.Collections/list)
                 addr (and i (.getInterfaceAddresses i))
                 :when addr]
             (-> addr .getAddress .getHostAddress (split #"%") first))))

(defn common-sources
  "Returns a list of all items in the sources vector that are on all of the
given peers."
  [sources & peer-names]
  (loop [sources (seq sources)
         results []]
    (cond
     (not sources) results

     (every? #((first sources) %) peer-names)
     (recur (next sources) (conj results (first sources)))

     :else
     (recur (next sources) results))))

(defn refresh-heso
  "Updates heso state based on the user's sources config file and the state of
  the network."
  [{:keys [config-file push-to-peers]}]
  (letmap
   self
   [:keep
    [config-file

     ;; An object that represents all the heartbeats used to push to a peer
     ;; automatically. Each peer has a separate heartbeat. The heartbeats
     ;; are stopped and replaces with new ones whenever the sources are
     ;; reconfigured.
     push-to-peers]

    ;; Defines the hesokuri sources. This is the user-configurable settings that
    ;; hesokuri needs to discover sources on the network and how to push and
    ;; pull them. In this function, this is read from the configuration file
    ;; specified by :config-file on self. It is a map in the following form:
    ;; [{"host-1" "/foo/bar/path1"
    ;;   "host-2" "/foo/bar/path2"}
    ;;  {"host-1" "/foo/bar/path3"
    ;;   "host-3" "/foo/bar/path4"}]
    sources (read-string (slurp config-file))

    :omit all-hostnames (set (apply concat (map keys sources)))

    ;; The hostname or IP of this system as known by the peers on the current
    ;; network. Here it is deduced from the vector returned by (identities)
    ;; and the peer-hostnames var.
    local-identity
    (or (first (for [ip (ips) :when (all-hostnames ip)] ip))
        (-> "hostname" sh :out trim))

    ;; A set of the hostnames of the peers.
    :omit peer-hostnames (disj all-hostnames local-identity)

    ;; Map of peer hostnames to the corresponding peer agent.
    peer-agents
    (into {} (for [hostname peer-hostnames]
               [hostname (agent new-peer)]))

    ;; A map of source-dirs to the corresponding agent.
    source-agents
    (into {} (for [source sources
                   :let [source-dir (source local-identity)]
                   :when source-dir]
               [source-dir (agent {:source-dir source-dir})]))]
   (doseq [[_ source-agent] source-agents]
     (send source-agent git-init))
   (send push-to-peers stop-heartbeats)
   (doseq [peer-hostname peer-hostnames
           :let [shared-sources
                 (common-sources sources local-identity peer-hostname)]]
     (send push-to-peers start-heartbeat 300000
           (fn []
             (doseq [source shared-sources]
               (send (source-agents (source local-identity))
                     push-for-peer (peer-agents peer-hostname) local-identity
                     (->PeerRepo peer-hostname (source peer-hostname)))))))
   self))

(defn kuri-heso
  "A very stupid implementation of the syncing process, ported directly from the
  Elisp prototype. This simply pushes and pulls every repo with the given peer."
  [{:keys [local-identity sources] :as self}
   peer-name]
  (let [sources (and local-identity
                     (seq (common-sources sources peer-name local-identity)))
        remote-track-name (str (->BranchName "master" local-identity))]
    (cond
     (not local-identity)
     (println "Local identity not set - cannot kuri")

     (not sources)
     (println "Could not find any sources on both "
              peer-name " and " local-identity)

     :else
     (println "\n\nkuri operation at " (str (Date.))
              " with peer: " peer-name))
    (doseq [source sources
            :let [local-path (source local-identity)
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
            (recur (next ops)))))))
    self))

(defn -main
  "I don't do a whole lot ... yet."
  [& args]
  ;; work around dangerous default behaviour in Clojure
  (alter-var-root #'*read-eval* (constantly false))
  (println "Hello, World!"))
