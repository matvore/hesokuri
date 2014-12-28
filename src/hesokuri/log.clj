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

(ns hesokuri.log
  (:import [java.util.logging Level Logger]))

(defmacro ger
  "Evaluates to a Logger for the current Clojure namespace. Because this is a
  macro, 'namespace' (i.e. *ns*) is what you want it to be: the namespace
  which this macro is invoked, not the runtime namespace."
  []
  `(Logger/getLogger ~(str *ns*)))

(defn error [^Logger l msg ^Throwable e]
  (.log l Level/SEVERE (str msg) e))
