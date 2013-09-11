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

(ns hesokuri.test-hesokuri.updateable-heso
  (:use [clojure.java.io :only [file]]
        clojure.test
        hesokuri.test-hesokuri.mock
        hesokuri.updateable-heso
        hesokuri.util
        hesokuri.watching)
  (:require [hesokuri.heso :as heso]))

(def ^:dynamic *config-file* "/home/jdoe/hesocfg")

(def ^:dynamic *on-change-cfg* (atom nil))

(def ^:dynamic *sources-eg*
  [{"peer1" "/peer1/foo"
    "peer2" "/peer2/foo"}])

(defn watcher-for-config-file [file on-change]
  (is (= file *config-file*))
  (swap! *on-change-cfg* (constantly on-change))
  {:stopper (mock {[] [nil :already-stopped]})
   :file file})

(deftest test-with-config-file
  (let [result (with-config-file *config-file*)]
    (is (= (:config-file result) *config-file*))
    (is (= (-> result :heso deref :sources) []))
    (is (not (:active result)))))

(deftest test-start-and-stop-autoupdate
  (with-redefs
    [watcher-for-file watcher-for-config-file
     heso/update-from-config-file #(assoc %1 :updated-from-file %2)]
    (let [not-started (with-config-file *config-file*)
          heso-agent (:heso not-started)
          started (start-autoupdate not-started)]
      (is (nil? (-> started :heso :updated-from-file)))
      (is (:watcher started))
      (@*on-change-cfg*)
      (await-for 3000 heso-agent)
      (is (= *config-file* (-> @heso-agent :updated-from-file)))
      (let [stopped (stop-autoupdate started)]
        (is (= not-started stopped))
        (is (= *config-file* (-> @heso-agent :updated-from-file)))
        (is (= :already-stopped ((-> started :watcher :stopper))))))))
