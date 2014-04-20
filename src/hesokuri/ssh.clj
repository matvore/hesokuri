; Copyright (C) 2014 Google Inc.
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

(ns hesokuri.ssh
  "Module for transferring data over SSH. Authentication is done with
public/private key pairs only. All keys use RSA algorithm."
  (:import [java.io PipedInputStream PipedOutputStream]))

(defn new-key-pair []
  (.genKeyPair (java.security.KeyPairGenerator/getInstance "rsa")))

(defn rsa-key-pair-provider [key-pair]
  (let [type-map {"ssh-rsa" key-pair}]
    (reify org.apache.sshd.common.KeyPairProvider
      (getKeyTypes [_] "ssh-rsa")
      (loadKey [_ type] (type-map type)))))

(defn channel-streams [channel]
  [(.getInvertedIn channel)
   (.getInvertedOut channel)
   (.getInvertedErr channel)])

(defn connect-to
  "Tries to connect to a peer with SSH authentication. This machine acts as the
SSH client. Returns an unopened instance of org.apache.sshd.ClientChannel
corresponding to a Hesokuri SSH channel.
"
  [host port key-pair known-server-key?]
  (let [client (org.apache.sshd.SshClient/setUpDefaultClient)]
    (doto client
      (.setServerKeyVerifier
       (reify org.apache.sshd.client.ServerKeyVerifier
         (verifyServerKey [_ _ _ server-key]
           (boolean (known-server-key? server-key)))))
      (.setKeyPairProvider (rsa-key-pair-provider key-pair))
      (.start))
    (let [connect-future (.awaitUninterruptibly (.connect client host port))]
      (if-not (.isConnected connect-future)
        (throw (.getException connect-future))
        (let [session (.getSession connect-future)
              auth-future (.awaitUninterruptibly
                           (.authPublicKey session "hesokuri_user" key-pair))]
          (if-not (.isSuccess auth-future)
            (throw (or (.getException auth-future)
                       (new RuntimeException "Could not authenticate")))
            (let [channel
                  (.createChannel
                   session
                   org.apache.sshd.ClientChannel/CHANNEL_SUBSYSTEM
                   "hesokuri")
                  open-future (-> channel .open .awaitUninterruptibly)]
              (if-not (.isOpened open-future)
                (throw (.getException open-future))
                channel))))))))

(defn listen-connect
  "Accepts incoming Hesokuri connections. new-connection-fn is a function that
takes these arguments in this order:
  1. InputStream corresponding to stdin
  2. OutputStream corresponding to stdout
  3. OutputStream corresponding to stderr
And returns the exit value as an integer (i.e. 0 for success).
Returns the org.apache.sshd.SshServer instance, which can be used to stop the
server.
"
  [port key-pair known-client-key? new-connection-fn]
  (doto (org.apache.sshd.SshServer/setUpDefaultServer)
    (.setPort port)
    (.setPublickeyAuthenticator
     (reify org.apache.sshd.server.PublickeyAuthenticator
       (authenticate [_ _ client-key _] (known-client-key? client-key))))
    (.setSubsystemFactories
     [(reify org.apache.sshd.common.NamedFactory
        (getName [_] "hesokuri")
        (create [_]
          (let [streams (atom {})]
            (reify org.apache.sshd.server.Command
              (destroy [_] nil)
              (setErrorStream [_ err]
                (swap! streams #(assoc % :err err)))
              (setExitCallback [_ callback]
                (swap! streams #(assoc % :exit callback)))
              (setInputStream [_ in]
                (swap! streams #(assoc % :in in)))
              (setOutputStream [_ out]
                (swap! streams #(assoc % :out out)))
              (start [_ _]
                (let [{:keys [in out err exit]} @streams
                      exit-value (new-connection-fn in out err)]
                  (.onExit exit exit-value)))))))])
    (.setKeyPairProvider (rsa-key-pair-provider key-pair))
    (.start)))
