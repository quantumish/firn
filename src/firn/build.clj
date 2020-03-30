(ns firn.build
  "Functions related to the building of a static site."
  (:require [cheshire.core :as json]
            [clojure.java.shell :as sh]
            [clojure.java.io :as io]
            [clojure.string :as s]
            [firn.config :as config]
            [firn.layout :as layout]
            [me.raynes.fs :as fs]
            [firn.util :as u])
  (:gen-class))

(defn prepare-config
  "Takes a path to files (or CWD) and makes a config with it."
  [{:keys [path]}]
  (let [path   (if (empty? path) (.getPath fs/*cwd*) path)
        config (config/default path)]
    config))

(defn- build-file-outpath
  "For the current file, build it's output filename.
  Because the users's content might not be a flat-wiki, we must account
  for cases where a file is `nested/several/layers/deep.org.`
  Basically, swaps out the `.org` -> `.html` and orig-dir -> orig-dir+output-dir.
  Returns the file name as a string."
  [{:keys [out-dirname files-dirname curr-file]}]
  (let [curr-file-path (-> curr-file :original .getPath)
        out-comb       (str files-dirname "/" out-dirname)]
    (-> curr-file-path
        (s/replace #"\.org" ".html")
        (s/replace (re-pattern files-dirname) (str out-comb))))) ;; < str to make linter happy.


(defn new-site
  "Creates the folders needed for a new site in your wiki directory.
  Copies the _firn_starter from resources, into where you are running the cmd.
  FIXME: This does not work with JARs - it's complicated to copy entire directories from a jar.
  possible solution: https://stackoverflow.com/a/28682910"
  [cmds & args]
  (let [new-config      (-> cmds prepare-config)
        existing-config (first args)
        config          (if (nil? cmds) existing-config new-config)]
     (if (fs/exists? (config :firn-dir))
       (u/print-err! "A _firn directory already exists.")
       (do
         (fs/copy-dir (io/resource "_firn_starter") (config :firn-dir))
         ;; used to be doing the following, when just copying the parser and
         ;; manually mkdirs... might have to revert to this:
         ;; b9259f7 * origin/feat/improve-templating Fix: vendor parser + move it to _firn/bin in setup
         ;; (-> "parser/bin/parser" io/resource io/input-stream (io/copy parser-out-path))))
         (fs/chmod "+x" (config :parser-path))))))


(defn setup
  "Creates folders for output, slurps in layouts and partials.
  NOTE: should slurp/mkdir/copy-dir be wrapped in try-catches? if-err handling?"
  [{:keys [layouts-dir partials-dir files-dir] :as config}]
  (when-not (fs/exists? (config :firn-dir)) (new-site nil config))

  (let [layout-files  (u/find-files-by-ext layouts-dir "clj")
        partial-files (u/find-files-by-ext partials-dir "clj")
        partials-map  (u/file-list->key-file-map partial-files)
        org-files     (u/find-files-by-ext files-dir "org") ;; could bail if this is empty...
        layouts-map   (u/file-list->key-file-map layout-files)]

    (fs/mkdir (config :out-dirname)) ;; make _site

    ;; FIXME: These are not good - copying the entire attachment directory and the static folder.
    (when-not (fs/exists? (config :out-attach-dir))
      (fs/copy-dir (config :attach-dir) (config :out-attach-dir)))

    (when-not (fs/exists? (config :static-out-dir))
      (fs/copy-dir (config :static-dir) (config :static-out-dir)))

    (assoc
     config :org-files org-files :layouts layouts-map :partials partials-map)))

(defn parse!
  "Shells out to the rust binary to parse the org-mode file."
  [config file-str]
  (let [parser (config :parser-path)
        res    (sh/sh parser file-str)]
    (if-not (= (res :exit) 0)
      (prn "Orgize failed to parse file." file-str res)
      (res :out))))

(defn read-file
  "Pulls :curr-file from config > parses > put into config with new vals"
  [config]
  (let [file-orig   (-> config :curr-file :original)
        file-parsed (->> file-orig slurp (parse! config))
        file-name   (-> file-orig .getName (s/split #"\.") (first))]
    (config/update-curr-file config {:name file-name :as-json file-parsed})))

(defn dataify-file
  "Converts an org file into a bunch of data."
  [config]
  (let [file-json (-> config :curr-file :as-json)
        file-edn  (-> file-json (json/parse-string true))]
    (config/update-curr-file config {:as-edn file-edn})))

(defn munge-file
  "After dataify-file,  we extract information and store it in curr-file."
  [config]
  (config/update-curr-file
   config
   {:keywords    (config/get-keywords config)
    :org-title   (config/get-keyword config "TITLE")}))

(defn htmlify-file
  "Renders files according to their `layout` keyword."
  [config]
  (let [layout   (keyword (config/get-keyword config "FIRN_LAYOUT"))
        as-html  (when-not (config/file-is-private? config)
                   (layout/apply-template config layout))]

    (config/update-curr-file config {:as-html as-html})))

(defn write-file
  "Takes (file-)config input and writes html to output."
  [{:keys [curr-file] :as config}]
  (let [out-file-name  (build-file-outpath config)
        out-html       (curr-file :as-html)]
    (when-not (config/file-is-private? config)
      (io/make-parents out-file-name)
      (spit out-file-name out-html))))

(defn single-file
  "Processes a single file, as stored in the config :org-files"
  [config f]
  (-> config
     (config/set-curr-file-original f)
     (read-file)
     (dataify-file)
     (munge-file)
     (htmlify-file)
     (write-file)))

(defn all-files
  "Processes all files in the org-directory"
  [opts]
  (let [config (-> opts prepare-config setup)]
    (doseq [f (config :org-files)]
      (single-file config f))))