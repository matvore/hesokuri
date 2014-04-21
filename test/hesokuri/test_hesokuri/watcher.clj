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

(ns hesokuri.test-hesokuri.watcher
  (:use [clojure.java.io :only [file]]
        clojure.test
        hesokuri.testing.temp
        hesokuri.util
        hesokuri.watcher))

(deftest test-for-dir
  (let [temp-dir (create-temp-dir)]
    (spit (file temp-dir "file3") "initial contents")
    (spit (file temp-dir "file4") "initial contents")
    (let [change-promises {"file1" (promise)
                           "file2" (promise)
                           "dir" (promise)
                           "file3" (promise)
                           "file4" (promise)}
          watcher (for-dir temp-dir (cb [change-promises] [path]
                                        (let [p (change-promises (str path))]
                                          (when-not (realized? p)
                                            (deliver p true)))))]
      (Thread/sleep 1000)

      (spit (file temp-dir "file1") "new file #1")
      @(change-promises "file1")

      (spit (file temp-dir "file2") "new file #2")
      @(change-promises "file2")

      (is (->> "dir" (file temp-dir) .mkdir))
      @(change-promises "dir")

      (spit (file temp-dir "file3") "42" :append true)
      @(change-promises "file3")

      (spit (file temp-dir "file4") "1011" :append true)
      @(change-promises "file4"))))

(deftest test-for-file
  (let [changed-promise (promise)
        temp-dir (create-temp-dir)
        watcher (for-file (file temp-dir "foo")
                          (cb [changed-promise] []
                              (if (realized? changed-promise)
                                (throw (IllegalStateException.
                                        "file changed twice"))
                                (deliver changed-promise true))))]
    (Thread/sleep 1000)

    (->> "foo" (file temp-dir) .createNewFile)
    @changed-promise

    (Thread/sleep 100)))
