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
  (:use [clojure.string :only [trim]]
        clojure.tools.logging)
  (:require clojure.java.shell))

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
  "This lets us create maps which show all the information to interpret the
  meaning of a function in a dump of application state.

  For instance, when we have a function literal (fn [y] (+ x y)), the
  representation of this function in a pretty-printed dump does not tell us the
  value of x in the closure, or even where the function is defined in code.
  However, if we use this macro, we get a map that indicates the location
  of the function literal and the name and value of the variables in the
  closure. For instance:

  (let [x 10]
    (cb [x] [y] (+ x y)))

  will yield code like this:

  {:fn (fn [y] (+ x y))
   :closure {:x 10}
   :at {:file \"source.clj\"
        :line 213
        :column 6}}"
  [from-closure & fn-body]
  (let [{:keys [line column]} (meta &form)]
    `{:fn (fn ~@fn-body)
      :closure (letmap [:keep ~from-closure])
      :at {:file ~*file*
           :line ~line
           :column ~column}}))

(defn cbinvoke
  "Invokes a callback constructed by the cb macro, passing the given arguments."
  [cb & args]
  (-> cb :fn (apply args)))

(defn serialize
  "Serializes value, returns a byte array. Taken from
http://stackoverflow.com/questions/7701634/efficient-binary-serialization-for-clojure-java"
  [v]
  (let [buff (java.io.ByteArrayOutputStream. 1024)]
    (with-open [dos (java.io.ObjectOutputStream. buff)]
      (.writeObject dos v))
    (.toByteArray buff)))

(defn deserialize
  "Accepts a byte array, returns deserialized value. Taken from
http://stackoverflow.com/questions/7701634/efficient-binary-serialization-for-clojure-java"
  [bytes]
  (with-open [dis (java.io.ObjectInputStream.
                   (java.io.ByteArrayInputStream. bytes))]
    (.readObject dis)))
