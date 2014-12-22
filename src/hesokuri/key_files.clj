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

(ns hesokuri.key-files
  "Provides functionality to update the authorized_keys and known_hosts files."
  (:import [java.nio.file StandardOpenOption]
           [java.nio.channels FileChannel])
  (:require [clojure.java.io :as cjio]
            [clojure.string :as cstr]
            [clojure.tools.logging :as ctl]
            [hesokuri.util :refer :all]))

(def line-suffix
  "All lines added automatically end with this string. This is used to remove
  stale entries from the files as well - if any line ends with this but is not
  in the config file, it will be removed."
  " {Hesokuri}")

(defn refresh
  "Makes sure the file at the given path has lines given in a sequence of
  Strings. Every line not in the file is added to the end. If the given file
  could not be locked, this function fails silently.

  TODO: do not add a line if it is already present without a prefix"
  [file lines]
  (let [file (cjio/file file)]
    (.createNewFile file)
    (let-try [channel (FileChannel/open
                       (.toPath file)
                       (into-array [StandardOpenOption/WRITE]))
              lock (.tryLock channel)]
      (if-not lock
        (ctl/error "Could not lock file " file)
        (let-try [;; Lines already in the file.
                  already-lines (remove #{""} (cstr/split (slurp file) #"\n"))

                  lines (map #(str % line-suffix) lines)

                  ;; Lines that are in the 'lines' sequence but are not in
                  ;; file.
                  missing-lines (remove (set already-lines) lines)

                  lines (set lines)
                  ;; Lines that are pre-existing AND should not be removed.
                  vetted-lines (filter #(or (not (.endsWith % line-suffix))
                                            (lines %))
                                       already-lines)

                  combined-lines (concat vetted-lines missing-lines)]
          (when (or (seq missing-lines)
                    (not= (count already-lines) (count vetted-lines)))
            (spit file (cstr/join "\n" combined-lines)))
          (finally (.close lock))))

      (finally (.close channel))))
  nil)
