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

(defproject hesokuri
  "SNAPSHOT"
  :description "distributed git repo backup and duplication daemon"
  :url "https://github.com/google/hesokuri"
  :license {:name "Apache 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0"}
  :dependencies [[compojure "1.1.5"]
                 [hiccup "1.0.4"]
                 [lib-noir "0.7.0"]
                 [net.java.dev.jna/jna "3.2.3"]
                 [org.apache.sshd/sshd-core "0.10.1"]
                 [org.clojure/clojure "1.6.0"]
                 [org.clojure/tools.logging "0.2.6"]
                 [ring/ring-core "1.2.0"]
                 [ring/ring-jetty-adapter "1.2.0"]
                 [org.slf4j/slf4j-jdk14 "1.7.6"]]
  :java-source-paths ["third_party/barbarywatchservice/src"]
  :main hesokuri.main)
