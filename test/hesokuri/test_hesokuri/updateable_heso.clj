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
  (:use clojure.test
        hesokuri.core
        hesokuri.test-hesokuri.mock
        hesokuri.updateable-heso
        hesokuri.util
        hesokuri.watching))

(def ^:dynamic *config-file* "/home/jdoe/hesocfg")

(def ^:dynamic *heso*
  {:start (mock {[] [0 :started-1 1 :started-2]})
   :stop (mock {[] [0 :stopped-1]})})

(def ^:dynamic *on-change-cfg* (atom nil))

(defn watcher-for-config-file [file on-change]
  (is (= file *config-file*))
  (swap! *on-change-cfg* (constantly on-change))
  {:stopper (constantly nil)
   :file file})

(deftest restart-heso-when-config-file-changed
  (binding [*letmap-omitted-key* ::omitted]
    (with-redefs [config-file (constantly *config-file*)

                  watcher-for-file watcher-for-config-file

                  new-heso
                  (mock {[*config-file*] [*heso* *heso* :end-of-mock]})]
      (let [updateable (new-updateable-heso)
            updateable-agent (-> updateable ::omitted :self)

            await-updateable-agent
            (fn []
              (await-for 3000 updateable-agent)
              (is (nil? (agent-error updateable-agent))))]
        ((updateable :start))
        (await-updateable-agent)
        (is (= *heso* ((updateable :heso))))
        (is (= :started-1 ((*heso* :start))))

        (@*on-change-cfg*)
        (await-updateable-agent)
        (is (= *heso* ((updateable :heso))))
        (is (= :started-2 ((*heso* :start))))

        (is (= :end-of-mock (new-heso *config-file*)))
        (is (= :stopped-1 ((*heso* :stop))))))))

(deftest dead-heso-is-reasonable
  (with-redefs [config-file (constantly *config-file*)]
    (let [dead-heso (#'hesokuri.updateable-heso/dead-heso *config-file*)

          check-properties
          (fn [object]
            (is (= [[] "localhost" *config-file*]
                   (map #(get object %)
                        [:sources :local-identity :config-file]))))]
      (check-properties dead-heso)
      (check-properties ((dead-heso :snapshot)))
      (is (get dead-heso :start))
      (is (get dead-heso :stop)))))
