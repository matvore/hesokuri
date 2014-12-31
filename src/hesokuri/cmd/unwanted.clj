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

(ns hesokuri.cmd.unwanted
  (:require [clojure.pprint :as cppr]
            [hesokuri.branch :as branch]
            [hesokuri.config :as config]
            [hesokuri.env :as env]
            [hesokuri.git :as git]
            [hesokuri.heso :as heso]
            [hesokuri.repo :as repo]
            [hesokuri.source :as source]
            [hesokuri.util :refer :all]))

(defn invoke [branch-name]
  (let [config (config/from-file env/heso-cfg-file)
        heso (heso/with-config config)
        source-with-unwanted (heso/source-containing heso env/startup-dir)]
    (if (nil? source-with-unwanted)
      [(str "Current directory (" env/startup-dir ") is not in any source in "
            "the configuration.\n")
       *err* 1]

      (let [source-with-unwanted (source/init-repo @source-with-unwanted)
            unwanted-shas (source/branch-shas source-with-unwanted branch-name)
            new-config (into-in config
                                [:sources
                                 (:hesokuri.heso/source-def-index
                                  source-with-unwanted)
                                 :unwanted-branches
                                 branch-name]
                                unwanted-shas
                                [])]
        (if (empty? unwanted-shas)
          [(str "No branches with name '" branch-name "'\n")
           *err* 1]

          (do (spit env/heso-cfg-file (pretty-printed new-config))
              (when-let [source-with-config
                         (heso/source-containing heso env/heso-cfg-file)]
                (git/invoke+log (:repo (source/init-repo @source-with-config))
                                "commit"
                                [(str env/heso-cfg-file)
                                 "-m"
                                 (str "Mark branch unwanted: " branch-name)]))
              ["" *out* 0]))))))
