(ns hesokuri.core
  (:gen-class))

(use '[clojure.java.shell :only [sh]])

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

(defn refresh-sources []
  (dosync
   (alter sources (fn [_] (read-string (slurp sources-config-file))))))

(defn -main
  "I don't do a whole lot ... yet."
  [& args]
  ;; work around dangerous default behaviour in Clojure
  (alter-var-root #'*read-eval* (constantly false))
  (println "Hello, World!"))
