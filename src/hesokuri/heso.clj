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

(ns hesokuri.heso
  (:use [clojure.java.shell :only [sh]]
        [clojure.string :only [split trim]]
        clojure.tools.logging
        hesokuri.util)
  (:require [hesokuri.peer :as peer]
            [hesokuri.repo :as repo]
            [hesokuri.source :as source]))

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
  (for [source sources
        :when (every? source peer-names)]
    source))

(defn with-sources
  "Creates a heso in the inactive state. Is a map with the following values:
  :config-file - the path of the file from which to read the heso configuration
  :active - true when the agent is inactive, false when not
  :heartbeats - An agent that represents all the heartbeats started by this
      object. Heartbeats are used to push to a peer automatically (one heartbeat
      per peer) and monitor filesystem changes (one heartbeat). The heartbeats
      are stopped and replaced with new ones whenever the sources are
      reconfigured.
  :sources - This value is an argument passed to this function. It defines the
      hesokuri sources. This is the user-configurable settings that Hesokuri
      needs to discover sources on the network and how to push and pull them.
      In this function, this is read from the configuration file specified by
      :config-file on this object. It is a map in the following form:
      [{\"host-1\" \"/foo/bar/path1\"
        \"host-2\" \"/foo/bar/path2\"}
       {\"host-1\" \"/foo/bar/path3\"
        \"host-3\" \"/foo/bar/path4\"}]
  :local-identity - The hostname or IP of this system as known by the peers on
       the current network. Here it is deduced from the vector returned by
       (identities) and the peer-hostnames var.
  :peers - Map of peer hostnames to the corresponding peer object.
  :source-agents - A map of source-dirs to the corresponding agent."
  [sources]
  (letmap
   [:keep sources
    active false
    heartbeats (agent (atom nil))
    :omit all-hostnames (set (mapcat keys sources))

    local-identity
    (or (getenv "HESOHOST")
        (first (for [ip (ips) :when (all-hostnames ip)] ip))
        (-> "hostname" sh :out trim))

    peer-hostnames (disj all-hostnames local-identity)
    peers (into {} (map (fn [p] [p (agent peer/default)]) peer-hostnames))

    source-agents
    (into {} (for [source sources
                   :let [source-dir (source local-identity)]
                   :when source-dir]
               [source-dir (agent {:repo (repo/with-dir source-dir)
                                   :peer-dirs source
                                   :peers peers
                                   :local-identity local-identity})]))]))

(defn push-sources-for-peer
  "Pushes all sources to the given peer. This operation happens asynchronously.
  This function returns the new state of the heso object."
  [{:keys [active peers sources source-agents local-identity] :as self}
   peer-hostname]
  {:pre [active]}
  (send (peers peer-hostname) #(dissoc % :last-fail-ping-time))
  (doseq [source (common-sources sources local-identity peer-hostname)
          :let [source-dir (source local-identity)
                source-agent (source-agents source-dir)]]
    (maybe (format "pushing %s to %s" source-dir peer-hostname)
           send source-agent source/push-for-peer peer-hostname))
  self)

(defn start
  "Begins the automatic operations of this object asynchronously. This function
  returns the new state of the heso object. This can safely be called multiple
  times or after the object has already started."
  [{:keys [active heartbeats peer-hostnames source-agents] :as self}]
  (let [active-self (assoc self :active true)]
    (when-not active
      (doseq [[_ source-agent] source-agents]
        (send source-agent source/init-repo)
        (send source-agent source/advance)
        (send source-agent source/start-watching))
      (doseq [peer-hostname peer-hostnames]
        (send heartbeats start-heartbeat 300000
              (fn [] (push-sources-for-peer active-self peer-hostname)))))
    active-self))

(defn stop
  "Terminates the automatic operations of this object. For instance, stops
  watching file systems for changes and pinging peers in the background. After
  calling this, the heso object becomes inactive and can be discarded. This
  function returns the new state of the heso object. This function can safely be
  called multiple times or after the object is already stopped."
  [{:keys [active source-agents heartbeats] :as self}]
  (when active
    (doseq [[_ source-agent] source-agents]
      (send source-agent source/stop-watching))
    (send heartbeats stop-heartbeats))
  (assoc self :active false))

(defn update-from-config-file
  "Reads the config file, stops the given heso object, and starts a new one.
  If the config-file has errors, this effectively stops the heso without
  starting a new one. Returns the new heso object."
  [self config-file]
  (let [sources (maybe (str "Read sources from " config-file)
                       read-string
                       (slurp config-file))]
    (if sources
      (do
        (stop self)
        (info "Starting new heso with sources: " sources)
        (-> sources with-sources start))
      self)))

