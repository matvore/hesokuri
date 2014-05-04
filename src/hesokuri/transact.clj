; Copyright (C) 2014 Google Inc.
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

(ns hesokuri.transact
  "This namespace supplies transactions functionality, which solves the problem
of lazy sequences, backed by closeable resources, that are not iterated over
completely before being garbage collected, which may be a result of an
Exception, or buggy code iterating over it.

To use such sequences, you only need to understand the transact function. To
implement such sequences, you should understand the open and close functions.
open should be called to report that a new stream has been opened. close is to
report that a sequence has finished with a stream in a normal fashion, and it
should be closed.

Any stream that is not closed with the close function before the transaction is
over will be closed immediately when the transaction is over. This will cause
problems if streams are still being used in other threads, so if you use these
lazy sequences, you should perform some synchronization in your transaction to
wait for other transactions to finish.")

(def open
  "Adds a closeable to the list of not-yet-closed items."
  conj)

(defn close
  "Closes the given closeable in the given transaction. This function will
call the close method on Closeable itself."
  [opened closeable]
  (.close closeable)
  (disj opened closeable))

(defn transact
  "Runs the given function in a transaction. trans-fn is called with a single
argument: the transaction itself, which should be passed to all functions that
open and close streams in an asynchronous or lazy manner.

This function returns whatever trans-fn returns, or throws whatever trans-fn
throws."
  [trans-fn]
  (let [trans (atom #{})]
    (try (trans-fn trans)
         (finally (doseq [c @trans] (.close c))))))
