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

(ns hesokuri.test-hesokuri.mock)

(defn mock [arg-sets]
  (let [arg-sets (ref arg-sets)]
    (fn [& args]
      (dosync
       (let [args (into [] args)
             entry (seq (get @arg-sets args))]
         (if entry
           (do
             (alter arg-sets assoc args (next entry))
             (first entry))
           (throw (Exception. (str "Mock can't respond for args " args
                                   ". Remaining arg-sets: " @arg-sets)))))))))
