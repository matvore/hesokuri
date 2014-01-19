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

(ns hesokuri.testing.temp
  (:import [java.io File FileWriter]))

(defn create-temp-dir
  "Creates a temporary directory and returns a File pointing to its path."
  []
  (let [path (File/createTempFile "hesokuri-tests" nil)]
    (or (.delete path)
        (throw (IllegalStateException. (str "Could not delete " path))))
    (or (.mkdir path)
        (throw (IllegalStateException. (str "Could not create temp dir " path))))
    path))

(defn temp-file-containing
  "Creates a temporary file containing the given object (converted with str).
  Returns a java.io.File object containing the path to the file."
  [contents]
  (let [path (File/createTempFile "hesokuri-tests" nil)
        stream (FileWriter. path)]
    (.write stream (str contents))
    (.close stream)
    path))
