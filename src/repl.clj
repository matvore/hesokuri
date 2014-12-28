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

(ns repl
  (:require [clojure.java.io :as cjio]
            [clojure.pprint :as cppr]
            [clojure.repl :refer :all]
            [clojure.reflect :as cref]
            [clojure.string :as cstr]
            [hesokuri.branch :as branch]
            [hesokuri.config :as config]
            [hesokuri.dynamic-config :as dynamic-config]
            [hesokuri.git :as git]
            [hesokuri.heartbeats :as heartbeats]
            [hesokuri.heso :as heso]
            [hesokuri.key-files :as key-files]
            [hesokuri.log :as log]
            [hesokuri.main :as main]
            [hesokuri.peer :as peer]
            [hesokuri.peer-repo :as peer-repo]
            [hesokuri.repo :as repo]
            [hesokuri.see :as see]
            [hesokuri.source :as source]
            [hesokuri.source-def :as source-def]
            [hesokuri.transact :as transact]
            [hesokuri.util :refer :all]
            [hesokuri.validation :as validation]
            [hesokuri.watcher :as watcher]
            [hesokuri.web :as web]
            [ring.util.io :as ruio])
  (:import [java.io ByteArrayOutputStream ObjectOutputStream OutputStream]))
