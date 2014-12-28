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

(ns hesokuri.testing.data
  (:require [hesokuri.git :as git]))

(def ^:dynamic *sources-eg*
  [{:host-to-path {"peer1" "/peer1/foo"
                   "peer2" "/peer2/foo"}}
   {"peer2" "/peer2/bar"}
   {:host-to-path {"peer1" "/peer1/baz"
                   "peer3" "/peer3/baz"}}
   {"peer1" "/peer1/42"
    "peer3" "/peer3/42"
    "peer4" "/peer4/42"}])

(def ^:dynamic *config-eg*
  {:comment "config comment"
   :sources *sources-eg*})

(defmacro thash
  "Creates a test hash. 'base' should be a symbol that looks like a hexadecimal
  SHA1 hash, but can be any length 1-40 and should not start with 0. It will be
  left-padded with 0 to make a full hash."
  [base]
  (cond
   (and (not (symbol? base)) (not (integer? base)))
   ,(throw (ex-info (str "Must be symbol or integer: " base) {}))
   (.startsWith (str base) "0")
   ,(throw (ex-info (str "Cannot start with 0: " base) {}))
   (some #(nil? (git/hex-char-val %)) (str base))
   ,(throw (ex-info (str "Contains non-hex char: " base) {}))
   :else
   ,(.replace (format "%40s" base) " " "0")))

(def ^:dynamic *hash-a* (thash a))
(def ^:dynamic *hash-b* (thash b))
(def ^:dynamic *hash-c* (thash c))
(def ^:dynamic *hash-d* (thash d))
(def ^:dynamic *hash-e* (thash e))
(def ^:dynamic *hash-f* (thash f))
(def ^:dynamic *hash-g* (thash 10))

(def ^:dynamic *first-commit*
  [["tree" nil [["100644" "some-file" nil "contents\n"]]]
   ["author" (git/author 1000)]
   ["committer" (git/author 1000)]
   [:msg "commit msg\n"]])

(def ^:dynamic *first-commit-hash* "807edf48041693d978ca374fe34ea761dd68df2e")

(def ^:dynamic *commit-tail* [["author" (git/author 2)]
                              ["committer" (git/author 2)]
                              [:msg "hesokuri.git/test-change\n"]])
