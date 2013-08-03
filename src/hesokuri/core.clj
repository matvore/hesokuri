; Copyright (C) 2013 Google Inc.
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

(ns hesokuri.core
  (:use [clojure.java.shell :only [sh]]
        [clojure.string :only [split trim]]
        [clojure.tools.logging :only [logf]]
        hesokuri.branch-name
        hesokuri.peer
        hesokuri.source
        hesokuri.util)
  (:require [noir.server :as server])
  (:gen-class))

(defn- config-file []
  (or (getenv "HESOCFG")
      (str (getenv "HOME") "/.hesocfg")))

(defn- port []
  "Returns the port to serve the heso web UI."
  (Integer. (or (getenv "HESOPORT") "8080")))

(defn- ips
  "Returns the IP addresses of all network interfaces as a vector of strings."
  []
  (into [] (for [i (-> (java.net.NetworkInterface/getNetworkInterfaces)
                       java.util.Collections/list)
                 addr (and i (.getInterfaceAddresses i))
                 :when addr]
             (-> addr .getAddress .getHostAddress (split #"%") first))))

(defn- common-sources
  "Returns a list of all items in the sources vector that are on all of the
  given peers."
  [sources & peer-names]
  (into []
        (for [source sources
              :when (every? source peer-names)]
          source)))

(defn- new-heso
  "Creates a heso in the inactive state."
  []
  (letmap
   [;; An agent corresponding to this heso object. The value in the agent is
    ;; true when the agent is active, false when not.
    :omit self (agent false)

    ;; An object that represents all the heartbeats started by this object.
    ;; Heartbeats are used to push to a peer automatically (one heartbeat per
    ;; peer) and monitor filesystem changes (one heartbeat). The heartbeats
    ;; are stopped and replaced with new ones whenever the sources are
    ;; reconfigured.
    :omit heartbeats (agent (atom nil))

    config-file (config-file)

    ;; Defines the hesokuri sources. This is the user-configurable settings that
    ;; hesokuri needs to discover sources on the network and how to push and
    ;; pull them. In this function, this is read from the configuration file
    ;; specified by :config-file on this object. It is a map in the following
    ;; form:
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
    (or (getenv "HESOHOST")
        (first (for [ip (ips) :when (all-hostnames ip)] ip))
        (-> "hostname" sh :out trim))

    ;; A set of the hostnames of the peers.
    :omit peer-hostnames (disj all-hostnames local-identity)

    ;; Map of peer hostnames to the corresponding peer agent.
    :omit peer-agents
    (into {} (for [hostname peer-hostnames]
               [hostname (agent new-peer)]))

    ;; A map of source-dirs to the corresponding agent.
    :omit source-agents
    (into {} (for [source sources
                   :let [source-dir (source local-identity)]
                   :when source-dir]
               [source-dir (agent {:source-dir source-dir
                                   :peer-dirs source
                                   :peer-agents peer-agents
                                   :local-identity local-identity})]))

    :omit restarter
    (fn [agent-map]
      (fn [key]
        (let [agent (agent-map key)
              error (agent-error agent)]
        (when error
          (restart-agent agent)
          error))))

    ;; Clears the errors on the peer identified by the given identity.
    ;; Returns the exception on the error if it was cleared, or returns nil if
    ;; there was no exception.
    restart-peer (restarter peer-agents)

    ;; Clears the errors on the source identified by the given identity.
    ;; Returns the exception on the error if it was cleared, or returns nil if
    ;; there was no exception.
    restart-source (restarter source-agents)

    ;; Returns a snapshot of heso state, converting agents into their raw
    ;; values. This is NOT an agent action, but just a regular function.
    snapshot
    (fn []
      (letmap
       [:keep [config-file sources local-identity]
        active @self

        source-info
        (into {} (for [[key agent] source-agents]
                   [key {:branches (@agent :branches)
                         :errors (agent-errors agent)}]))
        peer-info
        (into {} (for [[key agent] peer-agents]
                   [key (assoc @agent
                          :errors (agent-errors agent))]))]))

    ;; Terminates the automatic operations of this object. For instance,
    ;; stops watching file systems for changes and pinging peers in the
    ;; background. After calling this, the heso object becomes inactive
    ;; and can be discarded. This function returns nil. This function can safely
    ;; be called multiple times or after the object is already stopped.
    stop
    (fn [] (send self
      (fn [active]
        (when active
          ((doseq [[_ source-agent] source-agents]
             (send source-agent stop-watching))
           (send heartbeats stop-heartbeats)))
        false))
      nil)

    ;; Pushes all sources to the given peer. This operation happens
    ;; asynchronously, and this function returns nil.
    push-sources-for-peer
    (fn [peer-hostname]
      (send (peer-agents peer-hostname) reset-fail-ping-time)
      (doseq [source (common-sources sources local-identity peer-hostname)
              :let [source-dir (source local-identity)
                    source-agent (source-agents source-dir)]]
        (try
          (send source-agent push-for-peer peer-hostname)
          (catch Exception e
            ;; For some reason, log needs *read-eval* enabled.
            (binding [*read-eval* true]
              (logf :error e "Error when pushing %s to %s"
                    source-dir peer-hostname)))))
      nil)

    ;; Begins the automatic operations of this object asynchronously. This
    ;; function returns nil. This can safely be called multiple times or after
    ;; the object has already started.
    start
    (fn [] (send self
      (fn [active]
        (when (not active)
          (doseq [[_ source-agent] source-agents]
            (send source-agent advance)
            (send source-agent start-watching))
          (doseq [:let [heso-agent *agent*]
                  peer-hostname peer-hostnames]
            (send heartbeats start-heartbeat 300000
                  (fn [] (push-sources-for-peer peer-hostname)))))
        true))
      nil)]))

(defonce heso (new-heso))

(server/load-views-ns 'hesokuri.web)

(defn -main
  "Starts up hesokuri."
  [& args]
  ;; work around dangerous default behaviour in Clojure
  (alter-var-root #'*read-eval* (constantly false))
  ((heso :start))
  (server/start (port) {:mode :dev, :ns 'hesokuri}))
