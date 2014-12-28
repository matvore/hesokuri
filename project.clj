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
                 [lib-noir "0.7.0"]
                 [net.java.dev.jna/jna "3.2.3"]
                 [org.clojure/clojure "1.6.0"]
                 [org.clojure/data.codec "0.1.0"]
                 [org.clojure/tools.logging "0.2.6"]
                 [ring/ring-core "1.2.0"]
                 [ring/ring-jetty-adapter "1.2.0"]

                 ;; The items below are listed individually rather than the
                 ;; top-level apache-sshd artifact because Leiningen is having
                 ;; trouble downloading the top-level one. We may not need all
                 ;; of them, and we may be able to replace with the top-level
                 ;; artifact once the bug (?) is fixed in Leiningen.
                 [org.apache.sshd/sshd-core "0.10.1"]
                 [org.apache.sshd/sshd-pam "0.10.1"]
                 [org.apache.sshd/sshd-sftp "0.10.1"]
                 [org.bouncycastle/bcpg-jdk15on "1.49"]
                 [org.bouncycastle/bcpkix-jdk15on "1.49"]
                 [org.slf4j/slf4j-jdk14 "1.6.4"]
                 [tomcat/tomcat-apr "5.5.23"]]
  :java-source-paths ["third_party/barbarywatchservice/src"]
  :main hesokuri.main)
