(ns hesokuri.log)

(defonce logger (agent *out*))

(defn -log [self line]
  (.println self line)
  self)

(defn log [fmt & args]
  (send logger -log (apply format fmt args)))
