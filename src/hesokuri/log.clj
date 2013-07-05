(ns hesokuri.log)

(defonce logger (agent *out*))

(defn -log [self line]
  (.write self line)
  (.write self "\n")
  (.flush self)
  self)

(defn log [fmt & args]
  "Logs a formatted string, avoiding simultaneous logging by using an agent.
  The arguments passed to this function are passed directly to 'format' to
  format the string before logging it. A newline is appended automatically."
  (send logger -log (apply format fmt args)))
