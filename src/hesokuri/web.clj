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
    (let [heso @heso]
      [:div#wrapper
       [:div "local-identity: " (heso :local-identity)]
       [:h1 "more"]
       [:div [:a {:href "/sources"} "sources"]]
       [:h1 "config-file"]
       [:div#config-file (heso :config-file)]
       (for [source-index (-> :sources heso count range)
             :let [source ((heso :sources) source-index)]]
         `[:div#source-map
           ~@(concat (for [[host dir] source]
                       (format "%s %s<br>" host dir)))])])]))

(defpage "/sources" []
  (html5
   (include-css "/css.css")
   [:head [:title "heso sources"]]
     `[:body
       ~@(for [[source-dir source-agent] (@heso :source-agents)]
           [:h1 source-dir])]))
