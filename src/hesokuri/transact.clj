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
  "This namespace models transactions, which are used to gracefully handle
problems that may occur when performing operations on a database (e.g. Git
repo). Operations are assumed to consist of read and write operations which may
occur concurrently, and which must be cleaned up using the Closeable interface.
A transaction keeps track of all Exceptions that occur and all Closeables that
must be closed. Upon normal termination of a transaction, the current thread
blocks until all Closeables have been closed, and the result of the transaction
function and a vector of all Exceptions are returned. If the transaction
function throws an Exception (as opposed to an Exception happening during a
concurrent operation in the transaction), the Exception is added to the end of
the vector of Exceptions, the transaction ends, and all Closeables are closed
immediately.

A transaction is actually an atom which contains information about the opened
Closeables and Exceptions that have occurred. For information on implementing a
function that supports transactions, (i.e. receives a transaction as an argument
and uses it to report events), see the open, close, and error functions in this
namespace. These functions should all be called with swap!. For information on
consuming an API that uses transactions, see the transact function.

If any events are sent to the transaction after it is finished (i.e. after
transact has returned), then the events will behave very simply: open and close
events are ignored, but Closeables will still actually be closed, and errors are
thrown with (throw). This is also what happens if you use a transaction that is
(atom nil), which you can do if you are in the REPL. This is more flexible. If
you accidentally read using the transaction after transact returns, you may not
notice, but this is easy to avoid: simply don't return anything from transact
except for metadata about your completed commit.")

(defn open
  "Adds a closeable to the list of not-yet-closed items."
  [{:keys [opened] :as trans} closeable]
  (when trans (assoc trans :opened (conj opened closeable))))

(defn close
  "Closes the given closeable in the given transaction. This function will
call the close method on Closeable itself."
  [{:keys [opened errors-promise errors] :as trans} closeable]
  (.close closeable)
  (when trans
    (let [opened (disj opened closeable)]
      (if (and errors-promise (empty? opened))
        (do (deliver errors-promise errors)
            nil)
        (assoc trans :opened opened)))))

(defn with-closeables
  "Runs the given function f with some Closeables open in the given transaction.
This automatically invokes open and close on the transaction before and after
invoking the function. The return value of with-closeables is the return of f.
Note this function should not be called with swap!."
  [trans-atom closeables f]
  (swap! trans-atom (partial reduce #(open %1 %2)) closeables)
  (try (f)
       (finally
         (swap! trans-atom (partial reduce #(close %1 %2)) closeables))))

(defn finish
  "Considers the transaction finished. Will deliver the promise specified by
errors-promise once all Closeables have been closed, or immediately if they are
already closed. This will also close the remaining ones immediately if exception
is truthy. (The exception argument indicates what caused the trans-fn to
terminate abruptly, if applicable.)"
  [{:keys [opened errors] :as trans} errors-promise exception]
  {:pre [trans]}
  (cond
   exception (do (doseq [closeable opened]
                   (.close closeable))
                 (deliver errors-promise (conj errors exception))
                 nil)
   (empty? opened) (do (deliver errors-promise errors)
                       nil)
   :else (assoc trans :errors-promise errors-promise)))

(defn error
  "Reports an error to the given transaction. The error should be a Throwable."
  [{:keys [errors-promise errors] :as trans} error]
  (if (not trans)
    (throw error)
    (assoc trans :errors (conj errors error))))

(defn transact
  "Runs the given function in a transaction. trans-fn is called with a single
argument: the transaction itself, which should be passed to all functions that
open/close streams and report errors.

This function will not return until all Closeables opened during the transaction
have been closed, so if you forget to close any (like not reading until the end
of a sequence backed by a Closeable stream), this function will block forever.

This function returns a sequence with at least two items: the value returned by
trans-fn (nil if trans-fn threw an Exception) and a sequence of errors that
occurred, in the order that they were reported. The caller should not commit
any changes, or overwrite or destroy existing data, if any errors occurred."
  [trans-fn]
  (let [trans (atom {:opened #{}, :errors []})]
    (let [[res exception] (try [(trans-fn trans) nil]
                               (catch Throwable e [nil e]))
          errors-promise (promise)
          errors (do (swap! trans finish errors-promise exception)
                     @errors-promise)]
      [res errors])))
