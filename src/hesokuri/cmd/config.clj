; Copyright (C) 2015 Google Inc.
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

(ns hesokuri.cmd.config
  (:require [hesokuri.cmd.common :as cmd.common]
            [hesokuri.config :as config]
            [hesokuri.env :as env]
            [hesokuri.heso :as heso]))

(defn invoke []
  (let [config (config/from-file)]
    (if (nil? config)
      ["Error reading configuration.\n" *err* 1]
      (let [heso (heso/with-config config)]
        (cmd.common/update-config heso config "Normalize configuration format")
        [(str "Rewrote configuration in " env/heso-cfg-file "\n") *err* 0]))))
