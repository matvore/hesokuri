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
  (:require [clojure.java.shell :refer [sh]]
            [clojure.string :refer [split trim]]
            [hesokuri.config :as config]
            [hesokuri.env :as env]
            [hesokuri.heartbeats :as heartbeats]
            [hesokuri.key-files :as key-files]
            [hesokuri.log :as log]
            [hesokuri.peer :as peer]
            [hesokuri.repo :as repo]
            [hesokuri.source :as source]
            [hesokuri.source-def :as source-def]
            [hesokuri.util :refer :all]))

(defn- ips
  "Returns the IP addresses of all network interfaces as a vector of strings."
  []
  (into [] (for [i (-> (java.net.NetworkInterface/getNetworkInterfaces)
                       java.util.Collections/list)
                 addr (and i (.getInterfaceAddresses i))
                 :when addr]
             (-> addr .getAddress .getHostAddress (split #"%") first))))

(defn- common-sources
  "Returns a list of all host-to-path maps in the source-defs vector that are on
  all of the given peers."
  [source-defs & peer-names]
  (for [source-def source-defs
        :let [host-to-path (source-def/host-to-path source-def)]
        :when (every? host-to-path peer-names)]
    host-to-path))

(defn with-config
  "Creates a heso in the inactive state. Is a map with the following values:
  :active - true when the agent is inactive, false when not
  :heartbeats - An agent that represents all the heartbeats started by this
      object. Heartbeats are used to push to a peer automatically (one heartbeat
      per peer) and monitor filesystem changes (one heartbeat). The heartbeats
      are stopped and replaced with new ones whenever the source defs are
      reconfigured.
  :config - This value is an argument passed to this function.  This is the
      user-configurable settings that Hesokuri needs to discover sources on the
      network and how to push and pull them. This data is interpreted by code in
      config.clj.
  :local-identity - The hostname or IP of this system as known by the peers on
       the current network. Here it is deduced from the vector returned by
       (identities) and the peer-hostnames var.
  :peers - Map of peer hostnames to the corresponding peer object.
  :source-agents - A map of source-dirs to the corresponding agent."
  [config]
  (letmap
   [:keep config

    active false
    heartbeats (agent {})

    :omit source-defs (config/source-defs config)
    :omit host-to-paths (map source-def/host-to-path source-defs)
    :omit all-hostnames (set (mapcat keys host-to-paths))

    local-identity
    (or (getenv "HESOHOST")
        (first (for [ip (ips) :when (all-hostnames ip)] ip))
        (-> "hostname" sh :out trim))

    peer-hostnames (disj all-hostnames local-identity)
    peers (into {} (map (fn [p] [p (agent peer/default)]) peer-hostnames))

    source-agents
    (into {} (for [source-def source-defs
                   :let [host-to-path (source-def/host-to-path source-def)
                         source-dir (host-to-path local-identity)]
                   :when source-dir]
               [source-dir (agent {:repo (repo/with-dir source-dir)
                                   :source-def source-def
                                   :peers peers
                                   :local-identity local-identity})]))]))

(defn push-sources-for-peer
  "Pushes all sources to the given peer. This operation happens asynchronously.
  This function returns the new state of the heso object."
  [{:keys [peers config source-agents local-identity] :as self}
   peer-hostname]
  (send (peers peer-hostname) #(dissoc % :last-fail-ping-time))
  (doseq [host-to-path
          (common-sources
           (config/source-defs config) local-identity peer-hostname)
          :let [source-dir (host-to-path local-identity)
                source-agent (source-agents source-dir)]]
    (maybe (format "pushing %s to %s" source-dir peer-hostname)
           (send source-agent source/push-for-peer peer-hostname)))
  self)

(defn- send-args-to-start-sources
  "Returns a sequence of sequences of arguments to pass to send to start the
  source agents."
  [{:keys [source-agents]}]
  (mapcat (fn [[_ agt]] [[agt source/init-repo]
                         [agt source/advance]
                         [agt source/start-watching]])
          source-agents))

(defn start
  "Begins the automatic operations of this object asynchronously. This function
  returns the new state of the heso object. This can safely be called multiple
  times or after the object has already started."
  [self]
  (let [{:keys [active config heartbeats peer-hostnames]} self
        lines #(for [[file line] (:add-lines config)
                     :when (= file %)]
                 line)]
    (when-not active
      (doseq [send-args (send-args-to-start-sources self)]
        (apply send send-args))
      (doseq [peer-hostname peer-hostnames]
        (send heartbeats heartbeats/start 300000
              (cb [self peer-hostname] []
                  (push-sources-for-peer self peer-hostname))))
      (key-files/refresh env/known-hosts-file (lines :known-hosts))
      (key-files/refresh env/authorized-keys-file (lines :authorized-keys)))
    (assoc self :active true)))

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
    (send heartbeats heartbeats/stop-all))
  (assoc self :active false))

(defn update-config
  "Starts the heso again with the given config."
  [self config]
  (do (stop self)
      (.info (log/ger) (str "Starting new heso with config: " config))
      (-> config with-config start)))
