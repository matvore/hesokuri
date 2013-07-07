(ns hesokuri.web
  "Defines web pages that show and allow manipulation of hesokuri state."
  (:use hesokuri.core
        hiccup.page
        [noir.core :only [defpage]]))

(defpage "/" []
  (html5
   (include-css "/css.css")
   [:head [:title "heso main"]]
   [:body
    (let [heso (heso-snapshot @heso)]
      [:div
       [:div#nav-el [:a {:href "/peers"} "peers"]]
       [:div#nav-el [:a {:href "/sources"} "sources"]]
       [:div#nav-el "local-identity: " (heso :local-identity)]
       [:h1 "config-file"]
       [:div#config-file (heso :config-file)]
       (for [source-index (-> :sources heso count range)
             :let [source ((heso :sources) source-index)]]
         [:div#source-map
           (for [[host dir] source]
             (format "%s %s<br>" host dir))])])]))

(defpage "/sources" []
  (html5
   (include-css "/css.css")
   [:head [:title "heso sources"]]
   (let [heso (heso-snapshot @heso)]
     [:body
      [:div#nav-el [:a {:href "/"} "config"]]
      [:div#nav-el [:a {:href "/peers"} "peers"]]
      [:div#nav-el "local-identity: " (heso :local-identity)]
      (for [[source-dir source] (heso :source-info)]
        [:div
         [:h1 source-dir]
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
      [:div#nav-el [:a {:href "/"} "config"]]
      [:div#nav-el [:a {:href "/sources"} "sources"]]
      [:div#nav-el "local-identity: " (heso :local-identity)]])))
