(ns hesokuri.test-hesokuri.mock)

(defn mock [arg-sets]
  (let [arg-sets (ref arg-sets)]
    (fn [& args]
      (dosync
       (let [entry (seq (get @arg-sets args))]
         (if entry
           (do
             (alter arg-sets assoc args (next entry))
             (first entry))
           (throw (Exception. (str "Mock can't respond for args " args
                                   ". Remaining arg-sets: " @arg-sets)))))))))
