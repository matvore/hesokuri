(ns hesokuri.core
  (:use [clojure.java.shell :only [sh]])
  (:use [clojure.string :only [trim split]])
  (:gen-class))

(def sources
  "Defines the hesokuri sources. This is the user-configurable settings that
hesokuri needs to discover sources on the network and how to push and pull
them. This can be read from a configuration file. It is a map in the following
form:
{
  \"source-name-1\" {
    \"host-1\" \"/foo/bar/path1\"
    \"host-2\" \"/foo/bar/path2\"
  }
  \"source-name-2\" {
    \"host-1\" \"/foo/bar/path3\"
    \"host-3\" \"/foo/bar/path4\"
  }
}"
  (ref {}))

(def sources-config-file
  "Where to read the hesokuri sources configuration from."
  (str (.get (System/getenv) "HOME") "/.hesokuri_sources"))

(defn -vector-from-enum [enum]
  (loop [v []]
    (if (.hasMoreElements enum)
      (recur (conj v (.nextElement enum)))
      v)))

(defn identities
  "Returns a vector of the possible identities this system may have on the
network, which includes its hostname and the IP address of all network
interfaces. Each identity is a string."
  []
  (conj
  (let [interfaces (-vector-from-enum
                    (java.net.NetworkInterface/getNetworkInterfaces))
        address-of (fn [addr]
                     (first (split (.getHostAddress (.getAddress addr)) #"%")))]
    (loop [res [] i (seq interfaces)]
      (cond
       (not i) res
       (nil? (first i)) (recur res (next i))
       :else
       (recur (into res
         (loop [subres [] addrs (seq (.getInterfaceAddresses (first i)))]
           (cond
            (not addrs) subres
            (nil? (first addrs)) (recur subres (next addrs))
            :else (recur (conj subres (address-of  (first addrs)))
                         (next addrs)))))
              (rest i)))))
  (trim (:out (sh "hostname")))))

(defn refresh-sources []
  (dosync
   (alter sources (fn [_] (read-string (slurp sources-config-file))))))

(defn -main
  "I don't do a whole lot ... yet."
  [& args]
  ;; work around dangerous default behaviour in Clojure
  (alter-var-root #'*read-eval* (constantly false))
  (println "Hello, World!"))
