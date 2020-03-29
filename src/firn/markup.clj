(ns firn.markup
  "Namespace responsible for converted org-edn into html."
  (:require [clojure.string :as s]
            [firn.util :as u]))


;; Renderers

(defn date->html
  [v]
  (let [{:keys [year month day hour minute]} (v :start)]
    [:span (str year "/" month "/" day
                (when (and hour minute) hour ":" minute))]))

(defn- src-block->html
  "Formats a org-mode src block.
  NOTE: Has additional :keys `language` and `arguments`
  that could be used for syntax highlighting"
  [{:keys [contents _language _arguments] :as _src-block}]
  [:pre contents])

(defn link->html
  "Parses links from the org-tree.
  Checks if a link is an HTTP link or File link.
  FIXME: This is ripe for a refactor. But not too clever a refactor. Maybe."
  [v]
  (println "V is" v)
  (let [img-file-regex        #"(file:)(.*)\.(jpg|JPG|gif|GIF|png)"
        img-http-regex        #"(http:\/\/|https:\/\/)(.*)\.(jpg|JPG|gif|GIF|png)"
        img-rel-regex         #"(\.(.*))\.(jpg|JPG|gif|GIF|png)"
        img-attach-regex      #"(download:)(.*)\.(jpg|JPG|gif|GIF|png)"
        org-file-regex        #"(file:)(.*)\.(org)"
        http-link-regex       #"https?:\/\/(?![^\" ]*(?:jpg|png|gif))[^\" ]+"
        ;; final src/href concat. This is a bit clunkly.
        local-img-path        #(str "./" (nth %  2) "." (nth % 3))
        local-img-attach-path #(str "/" (nth % 2) "." (nth % 3))
        file-path             #(str "./" (nth %  2) ".html")
        ;; html values
        link-val              (get v :desc "Missing link value.")
        link-href             (get v :path "Missing HREF attribute.")]
    (cond
      (re-matches img-file-regex link-href)
      [:img {:src (local-img-path (re-matches img-file-regex link-href))}]

      (re-matches img-rel-regex link-href)
      [:img {:src link-href}]

      (re-matches org-file-regex link-href)
      [:a.internal {:href (file-path (re-matches org-file-regex link-href))} link-val]

      (re-matches img-http-regex link-href)
      [:img {:src link-href}]

      (re-matches img-attach-regex link-href)
      [:img {:src (local-img-attach-path (re-matches img-attach-regex link-href))}]

      (re-matches http-link-regex link-href)
      [:a.external {:href link-href :target "_blank"} link-val]

      :else
      [:a {:href link-href}])))

(link->html {:type "link", :path "download:attach/92/7B7DB1-4B0B-4189-8398-0495CFED0257/_20200328_193912Screen Shot 2020-03-28 at 7.39.03 PM.png"})

(clojure.string/replace "The color is red" #" " "%20")

(defn- title->html
  "Constructs a headline title - with possible additional values
  (keywords, priorities, timestamps -> can all be found in a headline.)
  That aren't found in the `children` values and so need special parsing."
  [v]
  (let [level          (v :level)
        typ            (v :type)
        children       (v :children)
        keywrd         (v :keyword)
        priority       (v :priority)
        value          (v :value)
        title-priority (u/str->keywrd "span.title-priority.title-priority__" priority)
        title-keyword  (u/str->keywrd "span.title-keyword.title-keyword__" keywrd)
        h-level        (case level 1 :h1 2 :h2 3 :h3 4 :h4 5 :h5 :h6)
        make-child     #(into [%] (map title->html children))]
    (case typ
      "headline"  (make-child :div)
      "title"     [h-level
                   (when keywrd [title-keyword (str keywrd " ")])
                   (when priority [title-priority (str priority " ")])
                   (make-child :span)]
      "text"      [:span value]
      "cookie"    [:span.heading-cookie value]
      "timestamp" [:span.heading-timestamp (date->html v)]
      "code"      [:code value]
      "verbatim"  [:code value]
      "link"      (link->html v)
      "underline" (make-child :i)
      "italic"    (make-child :em)
      "bold"      (make-child :strong)
      "")))

(defn to-html
  "Recursively Parses the org-edn into hiccup.
  Some values don't get parsed (drawers) - yet. They return empty strings.
  Don't destructure! - it can create uneven maps from possible nil vals on `V`"
  [v]
  (let [type           (get v :type)
        children       (get v :children)
        value          (get v :value)
        ordered        (get v :ordered)                               ;; for lists
        val            (if value (s/trim-newline value) value)
        headline-level (get v :level)
        headline-el    (u/str->keywrd "div.headline-" headline-level) ;; => :div.headline-n
        make-child     #(into [%] (map to-html children))]
    (case type
      "document"      (make-child :div)
      "headline"      (make-child headline-el)
      "title"         (title->html v)
      "section"       (make-child :section)
      "paragraph"     (make-child :p)
      "underline"     (make-child :u)
      "italic"        (make-child :em)
      "bold"          (make-child :strong)
      "list"          (make-child (if ordered :ol :ul))
      "list-item"     (make-child :li)
      "quote-block"   (make-child :div.quote-block)
      "table"         (make-child :table)
      "table-row"     (make-child :tr)
      "table-cell"    (make-child :td)
      "source-block"  (src-block->html v)
      "link"          (link->html v)
      "code"          [:code val]
      "verbatim"      [:code val]
      "rule"          [:hr]
      "cookie"        [:span.cookie val]
      "text"          [:span val]
      "timestamp"     (date->html v)
      "keyword"       "" ;; Don't parse
      "comment-block" "" ;; Don't parse
      "drawer"        "" ;; Don't parse
      ;; default value. FIXME: Should have a debug value for verbose mode.
      "")))
