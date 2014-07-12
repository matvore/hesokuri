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

(ns user
  (:require [clojure.data.codec.base64 :as b64]
            [clojure.java.io :as cjio]
            [clojure.pprint :as cppr]
            [clojure.repl :refer :all]
            [clojure.reflect :as cref]
            [clojure.string :as cstr]
            [clojure.tools.logging :as ctl]
            [hesokuri.branch :as branch]
            [hesokuri.config :as config]
            [hesokuri.dynamic-config :as dynamic-config]
            [hesokuri.git :as git]
            [hesokuri.heartbeats :as heartbeats]
            [hesokuri.heso :as heso]
            [hesokuri.hesobase :as hesobase]
            [hesokuri.hesoprot :as hesoprot]
            [hesokuri.main :as main]
            [hesokuri.peer :as peer]
            [hesokuri.peer-repo :as peer-repo]
            [hesokuri.repo :as repo]
            [hesokuri.see :as see]
            [hesokuri.source :as source]
            [hesokuri.source-def :as source-def]
            [hesokuri.ssh :as ssh]
            [hesokuri.transact :as transact]
            [hesokuri.util :refer :all]
            [hesokuri.validation :as validation]
            [hesokuri.watcher :as watcher]
            [hesokuri.web :as web]
            [ring.util.io :as ruio])
  (:import [java.io ByteArrayOutputStream ObjectOutputStream OutputStream]
           [java.security KeyFactory]
           [java.security.spec X509EncodedKeySpec]))

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
       (git/add-blob ["peer" name "key"] #(serialize % (ssh/public-key key)))
       (git/add-blob ["peer" name "port"] (str port))))

(defn read-config
  "Reads a hesobase repo and converts it to the config format, which is defined
  by hesokuri.config/validation.

  git-ctx - Instance of hesokuri.git/Context.
  ref - The ref to read. Defaults to refs/heads/master. Can be a tree or a
      commit."
  ([git-ctx] (read-config git-ctx "refs/heads/master"))
  ([git-ctx ref]
     (transact/transact
      #(hesobase/tree->config (git/read-tree git-ctx ref % git/read-blob)))))
