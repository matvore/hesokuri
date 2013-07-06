(ns hesokuri.core
  (:use [clojure.java.shell :only [sh]]
        [clojure.string :only [split trim]]
        hesokuri.branch-name
        hesokuri.peer
        hesokuri.source
        hesokuri.util)
  (:require [noir.server :as server])
  (:gen-class))

(defonce heso
  (agent {:heartbeats (agent (atom nil))

          :config-file
          (or (-> (System/getenv) (.get "HESOCFG"))
              (-> (System/getenv) (.get "HOME") (str "/.hesocfg")))}))

;; Port to serve the heso web UI.
(defonce port
  (Integer. (get (System/getenv) "HESOPORT" "8080")))

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

(defn -suspend-heso
  [{:keys [source-agents heartbeats]}]
  (doseq [[_ source-agent] source-agents]
    (send source-agent stop-watching))
  (send heartbeats stop-heartbeats)
  nil)

(defn suspend-heso
  "Stops all background operations if there are any."
  [{:keys [source-agents heartbeats] :as self}]
  (-suspend-heso self)
  self)

(defn refresh-heso
  "Updates heso state based on the user's sources config file and the state of
  the network."
  [{:keys [source-agents heartbeats config-file] :as old-self}]
  (-suspend-heso old-self)
  (letmap
   self
   [:keep
    [config-file

     ;; An object that represents all the heartbeats started by this object.
     ;; Heartbeats are used to push to a peer automatically (one heartbeat per
     ;; peer) and monitor filesystem changes (one heartbeat). The heartbeats
     ;; are stopped and replaced with new ones whenever the sources are
     ;; reconfigured.
     heartbeats]

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
    :omit peer-agents
    (into {} (for [hostname peer-hostnames]
               [hostname (agent new-peer)]))

    ;; A map of source-dirs to the corresponding agent.
    source-agents
    (into {} (for [source sources
                   :let [source-dir (source local-identity)]
                   :when source-dir]
               [source-dir (agent {:source-dir source-dir
                                   :peer-dirs source
                                   :peer-agents peer-agents
                                   :local-identity local-identity})]))]
   (doseq [[_ source-agent] source-agents]
     (send source-agent advance)
     (send source-agent start-watching))
   (doseq [peer-hostname peer-hostnames
           :let [shared-sources
                 (common-sources sources local-identity peer-hostname)]]
     (send heartbeats start-heartbeat 300000
           (fn []
             (doseq [source shared-sources]
               (send (source-agents (source local-identity))
                     push-for-peer peer-hostname)))))
   self))

(server/load-views-ns 'hesokuri.web)

(defn -main
  "Starts up hesokuri."
  [& args]
  ;; work around dangerous default behaviour in Clojure
  (alter-var-root #'*read-eval* (constantly false))
  (send heso refresh-heso)
  (server/start port {:mode :dev, :ns 'hesokuri}))
