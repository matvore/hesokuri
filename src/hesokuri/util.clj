(ns hesokuri.util
  (:use [clojure.java.shell :only [sh]]
        [clojure.string :only [trim join]]))

(defn vector-from-enum [enum]
  (vec (java.util.Collections/list enum)))

(defmacro lint-and
  "Performs a short-circuited logical int-based and. If the first expression is
  0, then the next expression is not evaluated. Returns the last expression
  evaluated."
  [x y]
  (let [x-res (gensym)]
    `(let [~x-res ~x]
       (if (= 0 ~x-res) ~x-res ~y))))

(defmacro lint-or
  "Performs a short-circuited logical int-based or. If the first expression is
  non-zero, then the next expression is not evaluated. Returns the last
  expression evaluated."
  [x y]
  (let [x-res (gensym)]
    `(let [~x-res ~x]
       (if (not= 0 ~x-res) ~x-res ~y))))

(defn sh-print-when
  "Runs the command-line given in args using clojure.java.shell/sh. Returns
  the exit code. If the 'print-when' argument (a function) returns truthy when
  passed the result of sh, it prints out the stderr and stdout of the process to
  this process' stderr and stdout."
  [print-when & args]
  (println (join " " args))
  (let [result (apply sh args)]
    (when (print-when result)
      (.print *err* (:err result))
      (.print *out* (:out result)))
    (:exit result)))

(defn sh-print
  [& args]
  (apply sh-print-when (constantly true) args))

(defn is-ff!
  "Returns true iff the second hash is a fast-forward of the first hash. When
  the hashes are the same, returns when-equal."
  [source-dir from-hash to-hash when-equal]
  (if (= from-hash to-hash) when-equal
      (= from-hash (trim (:out (sh "git" "merge-base"
                                   from-hash to-hash :dir source-dir))))))
