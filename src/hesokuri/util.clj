; Copyright (C) 2013 Google Inc.
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

(ns hesokuri.util
  (:import [java.io ByteArrayOutputStream ObjectInputStream ObjectOutputStream
            OutputStream OutputStreamWriter]
           [java.net URLDecoder URLEncoder])
  (:require [clojure.string :refer [trim]]
            [clojure.tools.logging :refer :all]))

(defmacro letmap
  "A macro that behaves like let, creating temporary bindings for variables, and
  also creates a map containing the bindings. An abbreviated form is supplied
  which simply evaluates to the map, which is useful for creating maps where
  some entries are used to calculate other entries. For instance, the
  abbreviated form:
    (letmap [a 10, b (* a 20)])
  evaluates to:
    {:a 10, :b 200}

  In the full form, the symbol immediately after the macro name is the name of
  the map that can be used in the let body:
    (letmap m [a 10, b (* a 20)]
      (into m [[:c m]]))
  evaluates to:
    {:a 10, :b 200, :c {:a 10, :b 200}}

  Bindings can be preceded by omit to create a let binding but to not put the
  value in the map. This is useful for values that store intermediate results.
    (letmap [:omit a 10, b (* a a)])
  evaluates to:
    {:b 100}

  In place of a binding you can use the :keep modifier, which indicates that the
  variable is already bound in this scope and you just want to add it to the map
  with a key of the same name:
  (defn new-foo [x y]
   (letmap [:keep [x y], z (+ x y)]))
  Then (new-foo 5 10) evaluates to: {:x 5, :y 10, :z 15}
  Instead of a vector after :keep you can specify a single symbol, it which case
  it would be treated as if it were a vector containing only that symbol."
  ([map-name bindings & body]
     (loop [bindings (seq bindings)
            let-bindings []
            map-expr {}]
       (cond
        (nil? bindings)
        `(let ~let-bindings
           (let [~map-name ~map-expr] ~@body))

        (= (first bindings) :omit)
        (let [[id expr & next] (next bindings)]
          (recur next
                 (into let-bindings [id expr])
                 map-expr))

        (= (first bindings) :keep)
        (let [[ids & next] (next bindings)
              ids (if (symbol? ids) [ids] ids)]
          (recur next
                 let-bindings
                 (into map-expr (for [id ids] [(keyword id) id]))))

        :else
        (let [[id expr & next] bindings]
          (recur next
                 (into let-bindings [id expr])
                 (assoc map-expr (keyword id) id))))))
  ([bindings]
     (let [map-name (gensym)]
       `(letmap ~map-name ~bindings ~map-name))))

(defn getenv
  "Gets the environment variable of the given name. Returns nil if the
  environment variable of that name does not exist."
  [variable-name]
  (.get (System/getenv) variable-name))

(defn current-time-millis
  "Returns the value returned by System/currentTimeMillis"
  []
  (System/currentTimeMillis))

(defmacro maybe
  "Runs the given body (wrapping in do) and returns the value returned by the
  body. If the body throws an exception, logs it and return nil."
  [description & body]
  `(try
     (do ~@body)
     (catch Exception e#
       ;; For some reason, log needs *read-eval* enabled.
       (binding [*read-eval* true]
         (error e# "Error when: " ~description))
       nil)))

(defmacro cb
  "This just resolves to a plain anonymous function. Do not use in new code."
  [_ & fn-tail]
  `(fn ~@fn-tail))

(defn cbinvoke
  "Do not use in new code."
  [cb & args]
  (apply cb args))

(defn read-until
  "Reads bytes into a String until a terminator byte is reached. in is a
java.io.InputStream. term? is a function that takes a byte as an int and
returns truthy if it is a terminator. baos is a java.io.ByteArrayOutputStream to
append the read bytes to. Returns a sequence with at least two elements: the
conversion of baos to a string, and the terminator that was reached as an int
(-1 for EOF).
If term? is omitted, reads until EOF."
  ([in] (read-until in (constantly false)))
  ([in term?] (read-until in term? (ByteArrayOutputStream. 128)))
  ([in term? baos]
     (let [b (.read in)]
       (if (or (= b -1) (term? b))
         [(.toString baos "UTF-8") b]
         (do (.write baos b)
             (recur in term? baos))))))

(defn write-bytes
  "Writes the UTF-8 bytes of a string to the given OutputStream. s is coerced to
a String with str if it is not a String already."
  [^OutputStream out s]
  (let [s (str s)]
    (doto (OutputStreamWriter. out "UTF-8")
      (.write s 0 (count s))
      (.flush)))
  nil)

(defn serialize
  "Uses Java serialization to serialize x to the OutputStream specified by out."
  [^OutputStream out x]
  (doto (ObjectOutputStream. out)
    (.writeObject x)
    .flush)
  nil)

(defn %-decode [s] (URLDecoder/decode (str s) "UTF-8"))
(defn %-encode [s] (URLEncoder/encode (str s) "UTF-8"))

(defn conj-in
  "Similar to assoc-in, but the deepest substructure is a collection that
  supports conj rather than a map.
  m - Nested associative structure to alter.
  ks - Sequences of keys representing path to the collection to conj to.
  v - The new value to conj into the collection.
  default-coll - What to conj into if the collection is not present. Typically
      this is [], #{}, or ()."
  [m ks v default-coll]
  (let [orig-coll (get-in m ks default-coll)]
    (assoc-in m ks (conj orig-coll v))))

(defn like
  "Converts all args using convert, then calls f with them. For
  instance:
  (like int + \\a 1) => (+ (int \\a) (int 1)) => 98"
  [convert f & args]
  (apply f (map convert args)))
