(ns hesokuri.branch-name
  (:use [clojure.string :only [split]]))

(defrecord BranchName [branch peer]
  Object
  (toString [_]
    (if peer
      (str branch "_hesokr_" peer)
      (str branch))))

(defn parse-branch-name [name]
  (let [s (split name #"_hesokr_" 2)]
    (BranchName. (first s) (second s))))

(def canonical-branch-name
  "This is the name of the only branch that is aggressively synced between
  clients. This branch has the property that it cannot be deleted, and automatic
  updates must always be a fast-forward."
  (BranchName. "hesokuri" nil))
