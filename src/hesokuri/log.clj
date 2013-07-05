(ns hesokuri.log)

(defonce logger (agent *out*))

(defn -log [self line]
  (.write self line)
  (.write "\n")
  self)

(defn log [fmt & args]
  (send logger -log (apply format fmt args)))
