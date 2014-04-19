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
  (:use hesokuri.util))

(defn new-key-pair []
  (.genKeyPair (java.security.KeyPairGenerator/getInstance "rsa")))

(defn same-byte-array [a b]
  (= (into [] a) (into [] b)))

(defn connect-to
  "Tries to connect to SSH server. Returns sequence containing OutputStream and InputStream, corresponding to SSH connection."
  [host port key-pair known-server-keys]
  (let [client (org.apache.sshd.SshClient/setUpDefaultClient)
        server-key-verifier (reify org.apache.sshd.client.ServerKeyVerifier
                              (verifyServerKey [_ _ server-key]
                                (first (for [known known-server-keys
                                             :when (same-byte-array )]))))]
    (.setServerKeyVerifier client ))
)

