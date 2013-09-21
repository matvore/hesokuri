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
  (:use [clojure.java.io :only [file]]
        hesokuri.util
        hiccup.page
        [hiccup.util :only [escape-html]]
        [noir.core :only [defpage defpartial render]]
        [noir.response :only [redirect]]
        [ring.util.codec :only [url-decode url-encode]])
  (:import [java.text DateFormat]
           [java.util Date])
  (:require clojure.pprint
            [hesokuri.branch :as branch]
            [hesokuri.heso :as heso]))

(defonce ^:dynamic
  ^{:doc "The heso agent that should be shown in the web UI."}
  *web-heso*
  "Must set *web-heso* to heso agent.")

(defpartial -navbar [heso & [url]]
  (let [link (fn [link-url title]
               (cond
                (= url link-url)
                [:div#nav-el title]
                :else
                [:div#nav-el [:a {:href link-url} title]]))]
    [:div#nav
     (link "/" "config")
     (link "/peers" "peers")
     (link "/sources" "sources")
     (link "/dump" "dump")
     [:div#nav-el "local-identity: " (heso :local-identity)]]))

(defpartial -errors [detail-link error]
  (html5
   (cond
    (nil? error) [:div#no-errors "no errors"]
    :else
    [:div#errors [:a#error-link
                  {:href detail-link}
                  (-> error class .getName)]])))

(defpartial -pretty-print [data]
  (html5
   (let [pprint-writer (java.io.StringWriter.)
         dump-str (do (clojure.pprint/pprint data pprint-writer)
                      (.toString pprint-writer))]
     [:pre (escape-html dump-str)])))

(defpage "/" []
  (html5
   (include-css "/css.css")
   [:head [:title "heso main"]]
   [:body
    (let [heso @*web-heso*
          sources (:sources heso)]
      [:div
       (-navbar heso "/")
       [:h1 "config-file"]
       (-pretty-print (:sources heso))])]))

(defpage "/errors/:type/:key" {:keys [type key]}
  (let [heso @*web-heso*
        error (-> heso ((keyword type)) (get (str key)) agent-error)]
    (html5
     (include-css "/css.css")
     [:head [:title (str "errors for " type " " key)]]
     [:body
      (-navbar heso "")
      (if (not error)
        [:div#no-errors "no errors"]
        [:div
         [:form {:id "clear-form", :action "/errors/clear", :method "post"}
          [:input {:type "text", :name "type", :value type, :hidden "true"}]
          [:input {:type "text", :name "key", :value key, :hidden "true"}]
          [:a
           {:href "javascript: document.forms['clear-form'].submit()"}
           "clear"]]
         (let [err-string-writer (java.io.StringWriter.)
               string-printer (java.io.PrintWriter. err-string-writer)]
           (.printStackTrace error string-printer)
           (.println string-printer (apply str (repeat 80 "-")))
           (.flush string-printer)
           [:pre [:div#stack-trace
                  (escape-html (.toString err-string-writer))]])])])))

(defpage [:post "/errors/clear"] {:keys [type key]}
  (let [agt (get-in @*web-heso* [(keyword type) key])]
    (maybe "Remove errors from agent." restart-agent agt @agt)
    (redirect (format "/errors/%s/%s" (url-encode type) (url-encode key)))))

(defpage "/sources" []
  (html5
   (include-css "/css.css")
   [:head [:title "heso sources"]]
   (let [heso @*web-heso*]
     [:body
      (-navbar heso "/sources")
      (for [[source-dir source-agent] (heso :source-agents)
            :let [source @source-agent]]
        [:div#source-info-wrapper
         [:div#source-heading source-dir]
         (-errors (str "/errors/source-agents/" (url-encode source-dir))
                  (agent-error source-agent))
         (for [[branch hash] (source :branches)]
           [:div#branch-info
            [:div#branch-heading
             (branch/underscored-name branch) " "
             [:span#branch-hash hash]]
            [:table#pushed
             (for [[peer-host peer-agent] (heso :peers)
                   :let [peer @peer-agent
                         pushed ((peer :pushed) [(file source-dir) branch])]
                   :when pushed]
               [:tr
                [:td peer-host]
                [:td (if (= hash pushed) "ok" pushed)]])]])])])))

(defpage "/peers" []
  (html5
   (include-css "/css.css")
   [:head [:title "heso peers"]]
   (let [heso @*web-heso*]
     [:body
      (-navbar heso "/peers")
      (for [[peer-id peer-agent] (heso :peers)
            :let [peer @peer-agent
                  form-id (str "push-" peer-id)]]
        [:div#peer-info-wrapper
         [:div#peer-heading peer-id]
         (-errors (str "/errors/peers/" (url-encode peer-id))
                  (agent-error peer-agent))
         (when (:last-fail-ping-time peer)
           [:div#last-fail-ping-time
            "Last failed ping time: "
            (.format (DateFormat/getTimeInstance DateFormat/MEDIUM)
                     (Date. (:last-fail-ping-time peer)))
            [:form {:id form-id :action "/peers/push" :method "post"}
             [:input {:type "text", :name "peer-id",
                      :value peer-id, :hidden true}]
             [:a {:href (str "javascript: document.forms['"
                             form-id
                             "'].submit()")}
              "push now"]]])])])))

(defpage [:post "/peers/push"] {:keys [peer-id]}
  (send *web-heso* heso/push-sources-for-peer peer-id)
  (redirect "/peers"))

(defpage "/dump" []
  (comment
    "Generates a pretty-printed page containing the snapshot of the entire heso
    state. Currently, this is only used for debugging, when the normal web UI
    does not give enough information.")
  (html5
   (include-css "/css.css")
   [:head [:title "heso dump"]]
    (let [heso @*web-heso*]
      [:body
       (-navbar heso "/dump")
       (-pretty-print heso)])))
