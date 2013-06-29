(ns hesokuri.core
  (:use [clojure.java.shell :only [sh]])
  (:use [clojure.string :only [trim split]])
  (:import [java.io File]
           [java.util Date Collections])
  (:gen-class))

(def sources
  "Defines the hesokuri sources. This is the user-configurable settings that
hesokuri needs to discover sources on the network and how to push and pull
them. This can be read from a configuration file. It is a map in the following
form:
 [
   {
     \"host-1\" \"/foo/bar/path1\"
     \"host-2\" \"/foo/bar/path2\"
   }
   {
     \"host-1\" \"/foo/bar/path3\"
     \"host-3\" \"/foo/bar/path4\"
   }
 ]"
  (ref []))

(def peer-hostnames
  "A set of the hostnames of the peers. Updated with sources."
  (ref #{}))

(def local-identity
  "The hostname or IP of this system as known by the peers on the current
network."
  (ref nil))

(def sources-config-file
  "Where to read the hesokuri sources configuration from."
  (str (.get (System/getenv) "HOME") "/.hesokuri/sources"))

(defn -vector-from-enum [enum]
  (vec (Collections/list enum)))

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

(defn -local-identity
  "Returns the identity of this system. It deduces it from the (identities)
vector and the peer-hostnames var."
  []
  (loop [candidates (seq (identities))]
    (cond
     (not candidates) nil
     (@peer-hostnames (first candidates)) (first candidates)
     :else (recur (next candidates)))))

(defn refresh-sources
  "Updates sources and peer-hostnames based on the user's sources config file."
  []
  (dosync
   (ref-set sources (read-string (slurp sources-config-file)))
   (ref-set peer-hostnames (set (apply concat (map keys @sources))))
   (ref-set local-identity (-local-identity))
   (list @sources @peer-hostnames @local-identity)))

(defn sources-on-machine
  "Returns a map of sources on the machine identified with the given MAC
address. Map is in the form of:
 {source-index-1 \"path-string1\", source-index-2 \"path-string2\", ...}
Each path string indicates the location of the source on the machine specified
by peer-name."
  [peer-name]
  (loop [all-sources (seq @sources)
         source-index 0
         res-sources {}]
    (letfn [(this-source-path [] ((first all-sources) peer-name))]
      (cond
       (not all-sources)
       res-sources

       (this-source-path)
       (recur (next all-sources) (inc source-index)
              (conj res-sources [source-index (this-source-path)]))

       :else
       (recur (next all-sources) (inc source-index)
              res-sources)))))

(defn -push! [local-path peer-repo remote-branch]
  (io!
   (println "Pushing " local-path " to " peer-repo)
   (let [res (sh "git" "push" peer-repo (str "master:" remote-branch) :dir local-path)]
     (print (:out res) (:err res))
     (:exit res))))

(defn -pull! [peer-repo local-path]
  (io!
   ; TODO: Do a git fetch and process the branches more intelligently.
   ; TODO: Pull to a different branch if the current one has merge conflicts.
   (println "Pulling " peer-repo " to " local-path)
   (let [res (sh "git" "pull" peer-repo "master" :dir local-path)]
     (print (:out res) (:err res))
     (:exit res))))

(defn -clone! [peer-repo local-path]
  (io!
   (println "Cloning " peer-repo " to " local-path)
   (let [res (sh "git" "clone" peer-repo local-path)]
     (print (:out res) (:err res))
     (:exit res))))

(defn report [results func-name func & args]
  ; TODO: make this a macro, I think
  (let [append-to (if (= 0 (apply func args)) :succeeded :failed)
        report-item (cons func-name (seq args))]
    (conj results
          [append-to (conj (append-to results) report-item)]
          [:last append-to])))

(defn branch-hashes!
  "Gets all of the branches of the local repo at the given string path. It
returns a map of branch names to sha1 hashes."
  [local-path]
  (io!
   (let [local-path-git (File. (str local-path "/.git"))
         git-dir (if (.exists local-path-git) local-path-git local-path)
         heads-dir (File. (str git-dir "/refs/heads"))]
     (loop [files (seq (.listFiles heads-dir))
            branches {}]
       (if (not files) branches
           (let [file (first files)
                 hash (trim (slurp file))]
             (recur (next files)
                    (if (= (count hash) 40)
                      (conj branches [(.getName file) hash])
                      branches))))))))

(defn kuri!
  "A very stupid implementation of the syncing process, ported directly from the
Elisp prototype. This simply pushes and pulls every repo with the given peer."
  [peer-name]
  (io!
   (let [peer-sources (sources-on-machine peer-name)
         me @local-identity
         remote-track-name (str me "_hesokr_master")]
     (cond
      (not peer-sources)
      (println "Could not find any sources on " peer-name)

      (not me)
      (println "Local identity not set - cannot kuri")

      :else
      (println "\n\nkuri operation at " (str (Date.))
               " with peer: " peer-name))
     (let [results
       (loop [local-sources (seq (sources-on-machine me))
              results {:succeeded [] :failed []}]
         (if (not local-sources) results
         (let [local-source (first local-sources)
               local-path (second local-source)
               local-path-file (File. local-path)
               peer-path (peer-sources (first local-source))
               peer-repo (str "ssh://" peer-name peer-path)]
           (cond
            (not peer-path)
            (do (println "not on peer")
            (recur (next local-sources) results))

            (not (.exists local-path-file))
            (do (println "not on me")
            (recur (next local-sources)
                   (report results "clone" -clone! peer-repo local-path)))

            (not (.isDirectory local-path-file))
            (throw (RuntimeException.
                    (str "path for repo is occupied by a non-directory file: "
                         local-path)))

            :else
            (let [results (loop
              [ops [:push-straight :pull] results results]
              (cond
               (not ops) results

               (= :push-straight (first ops))
               (let [results (report results "push"
                                     -push! local-path peer-repo "master")]
                 (if (= :succeeded (results :last))
                   (recur (next ops) results)
                   (recur (cons :push (next ops)) results)))

               (= :push (first ops))
               (recur (next ops)
                      (report results "push"
                              -push! local-path peer-repo remote-track-name))

               :else
               (recur (next ops)
                      (report results "pull" -pull! peer-repo local-path))))]
              (recur (next local-sources) results))))))]
       (when (seq (:succeeded results))
         (println "\nThe following operations succeeded:")
         (doseq [s (:succeeded results)] (println s)))
       (when (seq (:failed results))
         (println "\nThe following operations failed:")
         (doseq [f (:failed results)] (println f)))))))

(defn -main
  "I don't do a whole lot ... yet."
  [& args]
  ;; work around dangerous default behaviour in Clojure
  (alter-var-root #'*read-eval* (constantly false))
  (println "Hello, World!"))
