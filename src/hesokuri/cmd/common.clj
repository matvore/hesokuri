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

(ns hesokuri.cmd.common
  (:require [hesokuri.env :as env]
            [hesokuri.git :as git]
            [hesokuri.heso :as heso]
            [hesokuri.source :as source]
            [hesokuri.util :refer :all]))

(defn update-config [heso config commit-msg]
  (spit env/heso-cfg-file (pretty-printed config))
  (when-let [source-with-config
             (heso/source-containing heso env/heso-cfg-file)]
    (git/invoke+log (:repo (source/init-repo @source-with-config))
                    "commit"
                    [(str env/heso-cfg-file) "-m" commit-msg])))
