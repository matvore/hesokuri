(ns hesokuri.web
  "Defines web pages that show and allow manipulation of hesokuri state."
  (:use hesokuri.core
        hiccup.page
        [hiccup.util :only [escape-html]]
        [noir.core :only [defpage defpartial]]
        [ring.util.codec :only [url-decode url-encode]]))

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
     [:div#nav-el "local-identity: " (heso :local-identity)]]))

(defpartial -errors [detail-link errs]
  (html5
   (cond
    (-> errs count zero?) [:div#no-errors "no errors"]
    :else
    [:div#errors [:a#error-link {:href detail-link} "errors!"]])))

(defpage "/" []
  (html5
   (include-css "/css.css")
   [:head [:title "heso main"]]
   [:body
    (let [heso (heso-snapshot @heso)]
      [:div
       (-navbar heso "/")
       [:h1 "config-file"]
       [:div#config-file (heso :config-file)]
       (for [source-index (-> :sources heso count range)
             :let [source ((heso :sources) source-index)]]
         [:div#source-map
           (for [[host dir] source]
             (format "%s %s<br>" host dir))])])]))

(defpage "/errors/:type/:key" {:keys [type key]}
  (let [heso (heso-snapshot @heso)
        errors (get-in heso [(keyword type) key :errors])]
    (html5
     (include-css "/css.css")
     [:head [:title (str "errors for " type " " key)]]
     [:body
      (-navbar heso "")
      [:form {:id "clear-form", :action "/errors/clear", :method "post"}
       [:input {:type "text", :name "type", :value type, :hidden "true"}]
       [:input {:type "text", :name "key", :value key, :hidden "true"}]
       [:a {:href "javascript: document.forms['clear-form'].submit()"} "clear"]]
      [:pre
       (for [error errors
               :let [err-string-writer (java.io.StringWriter.)
                     string-printer (java.io.PrintWriter. err-string-writer)]]
         (do
           (.printStackTrace error string-printer)
           (.println string-printer (apply str (repeat 80 "-")))
           (.flush string-printer)
           [:div#stack-trace (escape-html (.toString err-string-writer))]))]])))

(defpage "/sources" []
  (html5
   (include-css "/css.css")
   [:head [:title "heso sources"]]
   (let [heso (heso-snapshot @heso)]
     [:body
      (-navbar heso "/sources")
      (for [[source-dir source] (heso :source-info)]
        [:div#source-info-wrapper
         [:div#source-heading source-dir]
         (-errors (str "/errors/source-info/" (url-encode source-dir))
                  (source :errors))
         (for [[branch hash] (source :branches)]
           [:div#branch-info
            [:div#branch-heading
             (str branch) " "
             [:span#branch-hash hash]]
            [:table#pushed
             (for [[peer-host peer] (heso :peer-info)
                   :let [pushed ((peer :pushed) [source-dir branch])]]
               [:tr
                [:td peer-host]
                [:td (if (= hash pushed) "ok" (or pushed "none"))]])]])])])))

(defpage "/peers" []
  (html5
   (include-css "/css.css")
   [:head [:title "heso peers"]]
   (let [heso (heso-snapshot @heso)]
     [:body
      (-navbar heso "/peers")])))
