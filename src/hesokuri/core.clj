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
        hesokuri.peer
        hesokuri.util)
  (:require [hesokuri.source :as source]))

(defn config-file []
  (or (getenv "HESOCFG")
      (str (getenv "HOME") "/.hesocfg")))

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

(defn new-heso
  "Creates a heso in the inactive state. config-file specifies the path of the
  file from which to read the heso configuration. If omitted, uses the value
  returned by config-file."
  ([] (new-heso (config-file)))
  ([config-file]
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

       :keep config-file

       ;; Defines the hesokuri sources. This is the user-configurable settings
       ;; that hesokuri needs to discover sources on the network and how to push
       ;; and pull them. In this function, this is read from the configuration
       ;; file specified by :config-file on this object. It is a map in the
       ;; following form:
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

       ;; Map of peer hostnames to the corresponding peer object.
       :omit peers
       (into {} (map (fn [p] [p (new-peer)]) peer-hostnames))

       ;; A map of source-dirs to the corresponding agent.
       :omit source-agents
       (into {} (for [source sources
                      :let [source-dir (source local-identity)]
                      :when source-dir]
                  [source-dir (agent {:source-dir source-dir
                                      :peer-dirs source
                                      :peers peers
                                      :local-identity local-identity})]))

       ;; Clears the errors on the peer identified by the given identity.
       ;; Returns the exception on the error if it was cleared, or returns nil
       ;; if there was no exception.
       restart-peer (fn [peer-id] (((peers peer-id) :restart)))

       ;; Clears the errors on the source identified by the given identity.
       ;; Returns the exception on the error if it was cleared, or returns nil
       ;; if there was no exception.
       restart-source
       (fn [key]
         (let [agent (source-agents key)
               error (agent-error agent)]
           (when error
             (restart-agent agent)
             error)))

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
           (into {} (for [[key peer] peers]
                      [key ((peer :snapshot))]))]))

       ;; Terminates the automatic operations of this object. For instance,
       ;; stops watching file systems for changes and pinging peers in the
       ;; background. After calling this, the heso object becomes inactive
       ;; and can be discarded. This function returns nil. This function can
       ;; safely be called multiple times or after the object is already
       ;; stopped.
       stop
       (fn [] (send self
                    (fn [active]
                      (when active
                        (doseq [[_ source-agent] source-agents]
                          (send source-agent source/stop-watching))
                        (send heartbeats stop-heartbeats))
                      false))
         nil)

       ;; Pushes all sources to the given peer. This operation happens
       ;; asynchronously, and this function returns nil.
       push-sources-for-peer
       (fn [peer-hostname]
         (((peers peer-hostname) :reset-last-fail-ping-time))
         (doseq [source (common-sources sources local-identity peer-hostname)
                 :let [source-dir (source local-identity)
                       source-agent (source-agents source-dir)]]
           (maybe (format "pushing %s to %s" source-dir peer-hostname)
                  send source-agent source/push-for-peer peer-hostname))
         nil)

       ;; Begins the automatic operations of this object asynchronously. This
       ;; function returns nil. This can safely be called multiple times or
       ;; after the object has already started.
       start
       (fn [] (send self
                    (fn [active]
                      (when (not active)
                        (doseq [[_ source-agent] source-agents]
                          (send source-agent source/advance)
                          (send source-agent source/start-watching))
                        (doseq [:let [heso-agent *agent*]
                                peer-hostname peer-hostnames]
                          (send heartbeats start-heartbeat 300000
                                (fn [] (push-sources-for-peer peer-hostname)))))
                      true))
         nil)])))
