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

(require '[clojure.java.io :as cjio])
(require '[clojure.reflect :as cref])
(require '[clojure.string :as cstr])
(require '[clojure.tools.logging :as ctl])
(require '[hesokuri.git :as git])
(require '[hesokuri.hesobase :as hesobase])
(require '[hesokuri.ssh :as ssh])
(require '[hesokuri.transact :as transact])
(require '[ring.util.io :as ruio])
(import '[java.io ObjectOutputStream OutputStream])

(use 'clojure.repl)

(defn public-key
  "Coerces the given key to a java.security.PublicKey."
  [key]
  (cond
   (instance? java.security.PublicKey key) key
   (instance? java.security.KeyPair key) (.getPublic key)
   (instance? java.security.PrivateKey key)
    (throw (ex-info "Got a private key instead of public one."))
   :else
    (throw (ex-info (str "Do not know how to coerce to public key: "
                         (class key))))))

(defn serialize [^OutputStream out x]
  (doto (ObjectOutputStream. out)
    (.writeObject x)
    (.flush))
  nil)
