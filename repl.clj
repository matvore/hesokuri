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

;;;; This file is for storing code that helps in working with Hesokuri within
;;;; the REPL, code that performs common debug operations, and code that will
;;;; probably move into the real app but is still experimental or is not
;;;; thoroughly unit tested.

(require '[clojure.data.codec.base64 :as b64])
(require '[clojure.java.io :as cjio])
(require '[clojure.pprint :as cppr])
(require '[clojure.reflect :as cref])
(require '[clojure.string :as cstr])
(require '[clojure.tools.logging :as ctl])
(require '[hesokuri.branch :as branch])
(require '[hesokuri.config :as config])
(require '[hesokuri.dynamic-config :as dynamic-config])
(require '[hesokuri.git :as git])
(require '[hesokuri.heartbeats :as heartbeats])
(require '[hesokuri.heso :as heso])
(require '[hesokuri.hesobase :as hesobase])
(require '[hesokuri.main :as main])
(require '[hesokuri.peer :as peer])
(require '[hesokuri.peer-repo :as peer-repo])
(require '[hesokuri.repo :as repo])
(require '[hesokuri.see :as see])
(require '[hesokuri.source :as source])
(require '[hesokuri.source-def :as source-def])
(require '[hesokuri.ssh :as ssh])
(require '[hesokuri.transact :as transact])
(require '[hesokuri.util :as util])
(require '[hesokuri.validation :as validation])
(require '[hesokuri.watcher :as watcher])
(require '[hesokuri.web :as web])
(require '[ring.util.io :as ruio])

(import '[java.io ByteArrayOutputStream ObjectOutputStream OutputStream])
(import '[java.security KeyFactory])
(import '[java.security.spec X509EncodedKeySpec])

(use 'clojure.repl)

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
   (instance? java.security.PublicKey key) key
   (instance? java.security.KeyPair key) (.getPublic key)
   :else
   (#(throw (ex-info % {:key key}))
    (if (instance? java.security.PrivateKey key)
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

(defn serialize [^OutputStream out x]
  (doto (ObjectOutputStream. out)
    (.writeObject x)
    (.flush))
  nil)

(defn add-peer
  "Adds a new peer to a configuration, and returns a new configuration that can
be passed to hesokuri.git/write-tree.

name: the name of the new peer
key: the key of the new peer. Will be coerced to a public key with the
    public-key function
port: the port that the new peer uses to listen for incoming connections.
config-tree: the tree corresponding to the original configuration. Corresponds
    to the the value returned by hesokuri.git/read-tree.
"
  [name key port config-tree]
  (->> config-tree
       (git/add-blob ["peer" name "key"] #(serialize % (public-key key)))
       (git/add-blob ["peer" name "port"] (str port))))
