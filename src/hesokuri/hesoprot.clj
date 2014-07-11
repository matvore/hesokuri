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

(ns hesokuri.hesoprot
  "Implements the server and client of the Hesokuri protocol. The Hesoprot is a
  protocol for two machines to share a pre-defined set of Git repositories.

  A connection consists of one or more channels, which correspond to channels in
  an SSH session. Each channel hosts a single command initiated by the SSH
  client. The client sends a command name followed by a 0 byte to initiate the
  communication. The following commands are supported:

  'receive-pack' - requests other machine to run receive-pack. The receive-pack
      command string is followed by a 0-byte-terminated name of the source for
      which to run git-receive-pack."
  (:require [clojure.java.io :as cjio]
            [hesokuri.git :as git]
            [hesokuri.util :refer :all])
  (:import [java.io InputStream OutputStream]))

;;; TODO: Handle IO errors better here. Currently, if we don't read or write
;;; enough bytes with an InputStream or OutputStream, we ignore it. Either
;;; throw or return meaningful error codes.

(defn read-hex-chars
  "Reads some number of hex characters from a stream and parses them into a
  Long."
  [count ^InputStream in]
  (let [buffer (make-array Byte/TYPE count)
        amount-read (.read in buffer)
        s (String. buffer 0 amount-read "UTF-8")]
    (Long/parseLong s 16)))

(defn pipe-packets
  "Pipes Git protocol packets from one stream to another. A packet consists of
  a size specification (4 zero-padded hex chars) and the actual data. In some
  cases, the actual git process that reads the data does not expect the size
  specification, in which case include-size should be false. True means the size
  will be written along with the actual data."
  [^InputStream in ^OutputStream out include-size]
  (let [packet-size (read-hex-chars 4 in)]
    (when include-size
      (cjio/copy (format "%04x" packet-size) out))
    (if (zero? packet-size)
      (.flush out)
      (let [buffer (make-array Byte/TYPE (- packet-size 4))]
        (.read in buffer)
        (.write out buffer)
        (recur in out include-size)))))

;;; TODO: Remove magic lines in receive-pack logic. In particular, the calls to
;;; pipe-packets in respond and push are magical and determined by trial and
;;; error. There may be scenarios where this does not work.

;;; There are three pipe-packets calls on each side:
;;; 1. advertise-refs data, sent from server to client
;;; 2. actual packed data, sent from client to server
;;; 3. summary, sent from server to client

(defn respond
  "Responds to a command sent by a client. The server does this by default
  whenever a channel is opened. Returns 0 for success, non-zero for error.

  src-name->git-dir - function that takes a source name and returns the .git
      directory as a String or java.io.File
  in, out - InputStream and OutputStream corresponding to the channel"
  [src-name->git-dir in out]
  (case (first (read-until in zero?))
    "receive-pack"
    ,(let-try [[src-name] (read-until in zero?)
               git-dir (str (src-name->git-dir src-name))
               [^OutputStream recv-in ^InputStream recv-out :as recv]
               ,(git/invoke-streams "git" ["receive-pack" git-dir])]
       (pipe-packets recv-out out true)
       (pipe-packets in recv-in false)
       (pipe-packets recv-out out true)
       (git/log recv)
       (finally (.close recv-in)
                (.close recv-out)))))

(defn push
  "Uses a Hesoprot channel to push. Returns the exit code of the send-pack
  sub-process as an integer.

  src-name - name of the source to push
  git-dir - .git directory of the source to push
  send-pack-args - arguments to git-send-pack as a sequence of Strings, which
      should include the '--force' flag (if desired) and the refs to push, such
      as 'master:master_hesokr_192.168.1.1'
  in, out - InputStream and OutputStream corresponding to the channel"
  [src-name git-dir send-pack-args in ^OutputStream out]
  (copy+ (str "receive-pack\0" src-name "\0") out .flush)
  (let-try [send-pack-args
            ,(concat ["--stateless-rpc" src-name] send-pack-args)
            [^OutputStream send-in ^InputStream send-out :as send]
            ,(git/invoke-streams git-dir "send-pack" send-pack-args)]
    (pipe-packets in send-in true)
    (pipe-packets send-out out true)
    (pipe-packets in send-in true)
    (git/log send)
    (finally (.close send-in)
             (.close send-out))))
