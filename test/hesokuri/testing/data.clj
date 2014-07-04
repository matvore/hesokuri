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

;;; Keys take a non-trivial amount of time to generate. DO NOT generate them in
;;; unit tests. Evaluate the following code to generate another public key.
(comment
  (do
    (require 'clojure.pprint 'hesokuri.ssh)
    (clojure.pprint/pprint
     (cons 'str (map #(apply str %)
                     (partition 57 57 [] (hesokuri.ssh/public-key-str
                                          (hesokuri.ssh/new-key-pair)))))))
)

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

(def ^:dynamic *key-str-c*
  (str "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAl/e4GPxW+3qQu"
       "YTzJSQlyQEiWTyhC2k0JsFaSR3QYoN3Yx3s8wK/ftPzscxIlMOHqGWiyC"
       "QbRfjGJhZtX6bPEwtrX9trJBcj0psXbGBxbq8coJrlYlQUCeIcjVv4Vnj"
       "Ic2AovRRbr+vKoWk+HVKlT6lC50JpnGpeX18k6W9lj5dNWuIscaxVHkbi"
       "7fOml1GXOVIJxvMUuFK8XDvDXwUN3X6jXQzGMC/z9nGuWHqDXhpuXdn5s"
       "hvESsJD9eSxh+CzUBkwjLkhwxXxPMtANwVnyMq87eWXC/e42fk45xurAr"
       "4LqVZUnZ9h6oIS9qK8K6C99GpKN2l+qGFSbxGy9f66lwIDAQAB"))

(def ^:dynamic *key-str-d*
  (str "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAox0ZJuMfPZQ6p"
       "1w/yLbgUFsHS2Cy2AMycrWWTk6+9s7UPEIm7YZB1caQ1fLmP7INz8pa7b"
       "T1vf/BjhCLcGp5LI0evM9mU4SW1aT85Ch8O0rsHP91FudKBCi1V50B+Wg"
       "IMF4u5J8oa8STFZpsk4EifR0I95Z7SlvY717ba7ffdEiVqiQDFJb2tBMc"
       "zo0wDc7B0Dg74yZG+jSikb6+Vrw/xbFNVZevz+h2goi36WUoE0Ct9EePk"
       "/990hXGCSsh7nxjREd99etVDId+X/mMs2YsvUGtGA/u7Fyynwj2Wn9tRh"
       "mmvk4rn34XiUrLUZRg2vrMhkBE9eYCZFU9vskvil6SJwIDAQAB"))
