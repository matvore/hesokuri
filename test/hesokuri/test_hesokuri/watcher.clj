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
        hesokuri.test-hesokuri.temp
        hesokuri.util
        hesokuri.watcher))

(defn wait-for [condition-fn]
  (loop [total-sleeps 0]
    (cond
     (> total-sleeps 200) false

     (condition-fn) true
     :else (do
             (Thread/sleep 100)
             (recur (inc total-sleeps))))))

(deftest test-for-dir
  (let [changed-files (atom clojure.lang.PersistentQueue/EMPTY)
        temp-dir (create-temp-dir)
        watcher (for-dir temp-dir (cb [changed-files] [path]
                                      (swap! changed-files #(conj % path))))

        wait-for-change
        (fn [filename]
          (or (wait-for #(= (peek @changed-files) (file filename)))
              (throw (IllegalStateException.
                      (str "changed files: " (seq @changed-files)))))
          (swap! changed-files pop))]
    (Thread/sleep 1000)

    (spit (file temp-dir "file1") "new file #1")
    (wait-for-change "file1")

    (spit (file temp-dir "file2") "new file #2")
    (wait-for-change "file2")

    (is (->> "dir" (file temp-dir) .mkdir))
    (wait-for-change "dir")

    (spit (file temp-dir "file1") "42" :append true)
    (wait-for-change "file1")

    (spit (file temp-dir "file2") "1011" :append true)
    (wait-for-change "file2")

    (Thread/sleep 100)
    (is (= [] @changed-files))))

(deftest test-for-file
  (let [changed-file-count (atom 0)
        temp-dir (create-temp-dir)
        watcher (for-file (file temp-dir "foo")
                          (cb [changed-file-count] []
                              (swap! changed-file-count inc)))
        wait-for-change
        (fn [] (is (wait-for #(> @changed-file-count 0))))]
    (Thread/sleep 1000)

    (->> "foo" (file temp-dir) .createNewFile)
    (wait-for-change)

    (Thread/sleep 100)
    (is (= 1 @changed-file-count))))
