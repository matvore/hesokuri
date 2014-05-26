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

(def ^:dynamic *hash-a* "a00000000000000000000000000000000000000a")
(def ^:dynamic *hash-b* "b00000000000000000000000000000000000000b")
(def ^:dynamic *hash-c* "c00000000000000000000000000000000000000c")
(def ^:dynamic *hash-d* "d00000000000000000000000000000000000000d")
(def ^:dynamic *hash-e* "e00000000000000000000000000000000000000e")
(def ^:dynamic *hash-f* "f00000000000000000000000000000000000000f")
(def ^:dynamic *hash-g* "0100000000000000000000000000000000000010")

(def ^:dynamic *first-commit*
  [["tree" nil [["100644" "some-file" nil "contents\n"]]]
   ["author" (git/author 1000)]
   ["committer" (git/author 1000)]
   [:msg "commit msg\n"]])

(def ^:dynamic *first-commit-hash* "807edf48041693d978ca374fe34ea761dd68df2e")

(def ^:dynamic *commit-tail* [["author" (git/author 2)]
                              ["committer" (git/author 2)]
                              [:msg "hesokuri.git/test-change\n"]])

;;; Keys take a non-trivial amount of time to generate. DO NOT generate them in
;;; unit tests.

(def ^:dynamic *key-str-a*
  (str "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAtkP4D+a8xsr6W"
       "Shm86vD0msWxKIwgBXwKSHwkiuzVEUPeND9iNXdfTIeDt1tw/IDfFuCq5"
       "ZbSDIl5X5stx3r26ls8s/bFzG7cqJ1W523QmeH+QZWbjcRqdByw48e6Df"
       "mcE2UcWoB/O3TgHqIhfUeRvyfeZ3+hgeJumwsmjKqcCE5sTMPum9OfpKQ"
       "U2KzjdXb/njO10v9g2CzByJd2V9rEp7amTBsexIe2gZ7Oui8or3Op9yls"
       "Gokf8YD5l2NOvlDT2DonSmxWTqcRlTfN44ywXrNfHkzX3qnj9XstbyIf7"
       "F8Ejl9Jhyrpp+ygRuJxg9k2tF7hRiP4ToGtPR340nUNQIDAQAB"))

(def ^:dynamic *key-str-b*
  (str "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAkNHFHdrOIZawB"
       "p5gidF8X3dkWu0kUkFfMhcLFzdS5FDKF5zdWgaAtwyn4UTXa7aB3f4zxl"
       "V2SwEIl33nOcUIzRphER0Hl9bYkyrWrdWnP2Xc0CF3EqGXQGCF75+PJ7F"
       "Lrf0CcNc6h/d3YFXoctJxlqEu43K5s0kZFWFsRrjNxgbZuqCsHvo4qqw7"
       "2R3qLJ5GdRl2ZFXy3hQ+h72FXkyCbqqTT0Jzre7lo0mgWr3/EA2lGbuLf"
       "Te3fM1L7J2tENOUTVZsEXrAtrBGa9BiLG4SPJkiJ6lLgE9S7fXF9M3Rkf"
       "wO6OpXO4OS+DRRD+n+f4sR4RgWsMXuc7ksT50M6B+pVQIDAQAB"))
