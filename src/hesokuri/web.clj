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

(ns hesokuri.web
  "Defines web pages that show and allow manipulation of hesokuri state."
  (:import [java.text DateFormat]
           [java.util Date])
  (:require [clojure.java.io :refer [file]]
            clojure.pprint
            [clojure.string :as cstr]
            [compojure.core :refer [defroutes GET POST]]
            [compojure.route :refer [not-found resources]]
            [hesokuri.branch :as branch]
            [hesokuri.heso :as heso]
            [hesokuri.see :as see]
            [hesokuri.util :refer :all]))

(defn attr
  "Escapes text for use as an HTML attribute, and adds quotes."
  [value]
  (concat "\""
          (cstr/escape value {\" "&quot;"})
          "\""))

(defn text
  "Escapes text for use in the body of an HTML element."
  [t]
  (cstr/escape t
               {\< "&lt;"
                \> "&gt;"
                \& "&amp;"
                \" "&quot;"}))

(defn page [title & code]
  (concat
   "<!DOCTYPE html>"
   "<html>"
   "  <link href='/css.css' rel='stylesheet' type='text/css'>"
   "<head><title>" (text title) "</title></head>"
   "  <body>"
   (apply concat code)
   "  </body>"
   "</html>"))

(defn url-encode [s] (java.net.URLEncoder/encode s "UTF-8"))

(defn redirect [url]
  {:status 302
   :headers {"Location" url}
   :body ""})

(defonce ^:dynamic
  ^{:doc "The heso agent that should be shown in the web UI."}
  *web-heso*
  "Must set *web-heso* to heso agent.")

(defn navbar [heso & [url]]
  (let [link (fn [link-url title]
               (concat "<div id='nav-el'>"
                       (cond
                         (= url link-url) title
                         :else (concat "<a href='" link-url "'>" title "</a>"))
                       "</div>"))]
    (concat
     "<div id='nav'>"
     (link "/" "config")
     (link "/peers" "peers")
     (link "/sources" "sources")
     (link "/dump" "dump")
     (concat "<div id='nav-el'>local-identity: "
             (text (heso :local-identity))
             "</div>")
     "</div>")))

(defn errors [detail-link error]
  (if (nil? error)
    "<div id='no-errors'>no errors</div>"
    (concat "<div id='errors'>"
            "  <a id='error-link' href=" (attr detail-link) ">"
            "    " (-> error class .getName text)
            "  </a>"
            "</div>")))

(defn pretty-print [data]
  (let [pprint-writer (java.io.StringWriter.)
        dump-str (do (clojure.pprint/pprint data pprint-writer)
                     (.toString pprint-writer))]
    (concat "<pre>"
            (text dump-str)
            "</pre>")))

(defn stack-trace [error]
  (let [writer (java.io.StringWriter.)
        printer (java.io.PrintWriter. writer)]
    (.printStackTrace error printer)
    (.flush printer)
    (.toString writer)))

(defn pushed-table-row [pushed-key sha peer-host peer-agent]
  (let [peer @peer-agent
        pushed ((peer :pushed) pushed-key)]
    (if (not pushed)
      ""
      (concat
       "<tr>"
       "  <td>" (text peer-host) "</td>"
       "  <td>" (if (= sha pushed) "ok" (text pushed)) "</td>"
       "</tr>"))))

(defn source-branch-info [peers dir branch sha]
  (concat
   "<div id='branch-info'>"
   "  <div id='branch-heading'>"
   "    " (text (branch/underscored-name branch))
   "    <span id='branch-hash'>" sha "</span>"
   "  </div>"
   "  <table id='pushed'>"
   (mapcat #(apply pushed-table-row [(file dir) branch] sha %) peers)
   "  </table>"
   "</div>"))

(defn source-info [peers dir agt]
  (let [source @agt]
    (concat
     "<div id='source-info-wrapper'>"
     "<div id='source-heading'>" (text dir) "</div>"
     (errors (str "/errors/source-agents/" (url-encode dir))
             (agent-error agt))
     (mapcat #(apply source-branch-info peers dir %) (source :branches))
     "</div>")))

(defn peer-info [id agt]
  (concat
   "<div id='peer-info-wrapper'>"
   "  <div id='peer-heading'>" (text id) "</div>"
   (errors (str "/errors/peers/" (url-encode id))
           (agent-error agt))
   (let [peer @agt
         form-id (str "push-" id)]
     (when (:last-fail-ping-time peer)
       (concat
        "<div id='last-fail-ping-time'>Last failed ping time: "
        (.format (DateFormat/getTimeInstance DateFormat/MEDIUM)
                 (Date. (:last-fail-ping-time peer)))
        "  <form id=" (attr form-id) " action='/peers/push' method='post'>"
        "    <input type='text' name='peer-id' value=" (attr id)
        "        hidden='true'>"
        "    <a href=" (attr (str "javascript: document.forms['"
                                  form-id
                                  "'].submit()"))
        "        >"
        "      push now"
        "    </a>"
        "  </form>"
        "</div>")))
   "</div>"))

(defroutes heso-web-routes
  (GET "/" []
    (let [heso @*web-heso*]
      (page
       "heso main"

       (navbar heso "/")
       "<h1>config-file</h1>"
       (pretty-print (:config heso)))))

  (GET "/errors/:type/:key" [type key]
    (let [heso @*web-heso*
          error (-> heso ((keyword type)) (get (str key)) agent-error)]
      (page
       (str "errors for " type " " key)

       (navbar heso "")
       (if (not error)
         "<div id='no-errors'>no errors</div>"
         (concat
          "<form id='clear-form' action='/errors/clear' method='post'>"
          "  <input type='text' name='type'"
          "      value=" (attr type) " hidden='true'>"
          "  <input type='text' name='key'"
          "      value=" (attr key) " hidden='true'>"
          "  <a href=\"javascript: document.forms['clear-form'].submit()\">"
          "    clear"
          "  </a>"
          "</form>"
          "<pre><div id='stack-trace'>"
          (text (stack-trace error))
          "</div></pre>")))))

  (POST "/errors/clear" [type key]
    (let [agt (get-in @*web-heso* [(keyword type) key])]
      (maybe (str "Remove errors from agent: " [type key])
             (restart-agent agt @agt))
      (redirect (format "/errors/%s/%s" (url-encode type) (url-encode key)))))

  (GET "/sources" []
    (let [heso @*web-heso*]
      (page
       "heso sources"

       (navbar heso "/sources")
       (mapcat #(apply source-info (heso :peers) %)
               (heso :source-agents)))))

  (GET "/peers" []
    (let [heso @*web-heso*]
      (page
       "heso peers"

       (navbar heso "/peers")
       (mapcat #(apply peer-info %) (heso :peers)))))

  (POST "/peers/push" [peer-id]
    (send *web-heso* heso/push-sources-for-peer peer-id)
    (redirect "/peers"))

  (GET "/dump" []
    (comment
      "Generates a pretty-printed page containing the snapshot of the entire
      heso state. This can be used for debugging.")
    (let [heso @*web-heso*]
      (page
       "heso dump"

       (navbar heso "/dump")
       (pretty-print (see/shrink heso)))))

  (resources "/")
  (not-found "<h1>Page not found</h1>"))
