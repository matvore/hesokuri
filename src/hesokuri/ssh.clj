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
  (:import [java.io ByteArrayOutputStream PipedInputStream PipedOutputStream]
           [java.security KeyFactory KeyPair PrivateKey PublicKey]
           [java.security.spec X509EncodedKeySpec])
  (:require [clojure.data.codec.base64 :as b64]
            [clojure.java.io :as cjio]
            [hesokuri.util :refer :all]
            [ring.util.io :as ruio]))

(defn new-key-pair []
  (.genKeyPair (doto (java.security.KeyPairGenerator/getInstance "rsa")
                 (.initialize 2048))))

(defn rsa-key-pair-provider [key-pair]
  (let [type-map {"ssh-rsa" key-pair}]
    (reify org.apache.sshd.common.KeyPairProvider
      (getKeyTypes [_] "ssh-rsa")
      (loadKey [_ type] (type-map type)))))

(defn channel-streams [channel]
  [(.getInvertedIn channel)
   (.getInvertedOut channel)
   (.getInvertedErr channel)])

(defn public-key
  "Coerces the given key value to a java.security.PublicKey. If the key value is
a string, it is assumed to be a base64 X509-encoded, RSA public key. If the key
value is a java.security.KeyPair instance, the .getPublic method is used to get
the public key of the pair."
  [key]
  (cond
   (string? key) (let [b64-encoded-bytes-stream
                        (ruio/piped-input-stream #(cjio/copy key %))
                       baos (ByteArrayOutputStream.)]
                   (b64/decoding-transfer b64-encoded-bytes-stream baos)
                   (.generatePublic
                    (KeyFactory/getInstance "RSA")
                    (X509EncodedKeySpec. (.toByteArray baos))))
   (instance? PublicKey key) key
   (instance? KeyPair key) (.getPublic key)
   :else
    (#(throw (ex-info % {:key key}))
     (if (instance? PrivateKey key)
       "Got a private key instead of public one."
       (str "Do not know how to coerce class to public key: " (class key))))))

(defn public-key-str
  "Converts a key to a public key, then the public key to a base64 String in the
default encoding."
  [key]
  (if (string? key)
    key
    (let [key (public-key key)
          raw-key-bytes (cjio/input-stream (.getEncoded key))
          b64-encoded-bytes-stream
           (ruio/piped-input-stream #(b64/encoding-transfer raw-key-bytes %))]
      (slurp b64-encoded-bytes-stream))))

(defn connect-to
  "Tries to connect to a peer with SSH authentication. This machine acts as the
  SSH client. Returns an already-opened instance of org.apache.sshd.ClientChannel
  corresponding to a Hesokuri SSH channel. Returns the value of the accumulator
  after the last successful operation if any error occurred.

  This function returns the final value of the accumulator.

  host - String representing the host to connect to
  port - port on the host to connect to
  key-pair - key pair with which to authenticate this client
  known-server-key? - a function that accepts a java.security.PublicKey and
      returns truthy iff a server with that key should be authenticated
  client-session - authenticated client session instance
  connection-fn - a function that takes the following arguments, in this order:
      1. OutputStream corresponding to stdin
      2. InputStream corresponding to stdout
      3. InputStream corresponding to stderr
      4. acc - value of the accumulator

      This function returns a sequence with at least two values: first, truthy
      to create another connection, or falsey to stop. Second, the new
      accumulator value.
  acc - the initial value of the accumulator."
  ([^org.apache.sshd.ClientSession client-session connection-fn acc]
     (let [channel (.createShellChannel client-session)]
       (if-not (-> channel .open .awaitUninterruptibly .isOpened)
         acc
         (let [[continue new-acc]
               ,(try (connection-fn (.getInvertedIn channel)
                                    (.getInvertedOut channel)
                                    (.getInvertedErr channel)
                                    acc)
                     (finally (.close channel false)))]
           (if-not continue
             new-acc
             (recur client-session connection-fn new-acc))))))
  ([host port key-pair known-server-key? connection-fn acc]
     (let [client (org.apache.sshd.SshClient/setUpDefaultClient)]
       (doto client
         (.setServerKeyVerifier
          (reify org.apache.sshd.client.ServerKeyVerifier
            (verifyServerKey [_ _ _ server-key]
              (boolean (known-server-key? server-key)))))
         (.setKeyPairProvider (rsa-key-pair-provider key-pair))
         .start)
       (let-try [connect-future
                 ,(.awaitUninterruptibly (.connect client host port))]
         (if-not (.isConnected connect-future)
           acc
           (let-try [session (.getSession connect-future)]
             (if-not (.isSuccess
                      (.awaitUninterruptibly
                       (.authPublicKey session "hesokuri_user" key-pair)))
               acc
               (connect-to session connection-fn acc))
             (finally (.close session false))))
         (finally (.stop client))))))

(defn listen-connect
  "Accepts incoming Hesokuri connections. connection-fn is a function that
  takes these arguments in this order:
      1. InputStream corresponding to stdin
      2. OutputStream corresponding to stdout
      3. OutputStream corresponding to stderr
  And returns the exit value as an integer (i.e. 0 for success).
  Returns the org.apache.sshd.SshServer instance, which can be used to stop the
  server."
  [port key-pair known-client-key? connection-fn]
  (doto (org.apache.sshd.SshServer/setUpDefaultServer)
    (.setPort port)
    (.setPublickeyAuthenticator
     (reify org.apache.sshd.server.PublickeyAuthenticator
       (authenticate [_ _ client-key _] (known-client-key? client-key))))
    (.setShellFactory
     (reify org.apache.sshd.common.Factory
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
               (let [{:keys [in out err exit]} @streams]
                 (future
                   (.onExit exit (connection-fn in out err))))))))))
    (.setKeyPairProvider (rsa-key-pair-provider key-pair))
    (.start)))
