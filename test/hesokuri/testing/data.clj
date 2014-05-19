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

(ns hesokuri.testing.data)

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

(def ^:dynamic *key-str*
  (str "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAtkP4D+a8xsr6W"
       "Shm86vD0msWxKIwgBXwKSHwkiuzVEUPeND9iNXdfTIeDt1tw/IDfFuCq5"
       "ZbSDIl5X5stx3r26ls8s/bFzG7cqJ1W523QmeH+QZWbjcRqdByw48e6Df"
       "mcE2UcWoB/O3TgHqIhfUeRvyfeZ3+hgeJumwsmjKqcCE5sTMPum9OfpKQ"
       "U2KzjdXb/njO10v9g2CzByJd2V9rEp7amTBsexIe2gZ7Oui8or3Op9yls"
       "Gokf8YD5l2NOvlDT2DonSmxWTqcRlTfN44ywXrNfHkzX3qnj9XstbyIf7"
       "F8Ejl9Jhyrpp+ygRuJxg9k2tF7hRiP4ToGtPR340nUNQIDAQAB"))
