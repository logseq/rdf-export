(ns logseq.rdf-export
  "This ns converts a subset of a Logseq graph to an rdf file using
https://github.com/rdfjs/N3.js'

All of the above pages can be customized with query config options."
  (:require ["n3" :refer [DataFactory Writer]]
            ["fs" :as fs]
            ["path" :as path]
            [clojure.string :as string]
            [clojure.edn :as edn]
            [clojure.set :as set]
            [datascript.core :as d]
            [logseq.db.file-based.rules :as file-rules]
            [babashka.cli :as cli]
            [logseq.graph-parser.cli :as gp-cli]
            [logseq.rdf-export.config :as config]
            [datascript.transit :as dt]))

(defn- create-entities
  "Given a seq of block maps and config options, create entities which consist
  of the block's properties and a few built in block attributes"
  [{:keys [exclude-properties expand-entity-fn]} result]
  (map
   (fn [block]
     (let [block-name (or (get-in block [:block/properties :title])
                          (:block/title block)
                          ;; Use page name for non-journal pre-blocks
                          (when-not (get-in block [:block/page :block/journal?])
                            (get-in block [:block/page :block/title])))]
       (cond->
        (-> (:block/properties block)
            ;; TODO: Add proper tags support
            (dissoc :title :tags)
            ((fn [x] (apply dissoc x exclude-properties)))
            expand-entity-fn)
         (some? block-name)
         (assoc :block/title block-name))))
   result))

(defn- page-url [page-name config]
  (str (:base-url config) (js/encodeURIComponent page-name)))

;; overly permissive but nothing harmful yet
(defn- url? [s]
  (re-matches #"\S+://\S+" s))

(defn- triplify
  "Turns an entity map into a coll of triples"
  [m
   {:keys [url-property type-property classes-without-ids unique-id-properties] :as config}
   property-map]
  (if-let [subject (if (:block/title m)
                     (page-url (:block/title m) config)
                     (some #(when-let [v (m %)]
                              (if (url? v) v (page-url v config)))
                           unique-id-properties))]
    (mapcat
     (fn [prop]
       (map #(vector
              subject
              ;; predicate
              (or (url-property (get property-map prop))
                  (page-url (name prop) config))
              ;; object
              %)
            (let [v (m prop)]
              ;; If a collection, they are refs/pages
              (if (coll? v) (map #(page-url % config) v) [v]))))
     (keys m))
    (do
      (when (empty? (set/intersection classes-without-ids (m type-property)))
        (println "Skip entity b/c no name or url for entity - " (pr-str m)))
      [])))

(defn- block->label-triple [block]
  [(:url block)
   "http://www.w3.org/2000/01/rdf-schema#label"
   (:block/title block)])

(defn- build-alias-triples
  [ents config]
  (->> ents
       (filter :alias)
       (mapcat #(map (fn [alias]
                       (block->label-triple
                        {:url (page-url alias config)
                         :block/title (:block/title %)}))
                     (:alias %)))))

(defn- add-class-instances [db config property-map {:keys [add-labels]}]
  (let [ents (->> (d/q (:class-instances-query config)
                       db
                       [(:page-property file-rules/query-dsl-rules)])
                  (map first)
                  (create-entities config))]
    (concat
     (mapcat #(triplify % config property-map) ents)
     (when add-labels
       (build-alias-triples ents config)))))

(defn- add-additional-instances [db config property-map {:keys [add-labels]}]
  (let [ents (->> (d/q (:additional-instances-query config)
                       db
                       [])
                  (map first)
                  (create-entities config))]
    (concat
     (mapcat #(triplify % config property-map) ents)
     (when add-labels
       (build-alias-triples ents config)))))

(defn- create-quads [_writer db config {:keys [add-labels] :as options}]
  (let [properties (->> (d/q (:property-query config)
                             db
                             [(:page-property file-rules/query-dsl-rules)])
                        (map first)
                        (create-entities config))
        built-in-properties {:block/title
                             {:url (if add-labels
                                     "http://www.w3.org/2000/01/rdf-schema#label"
                                     "https://schema.org/name")}
                             :alias
                             {:url (if add-labels
                                     "http://www.w3.org/2002/07/owl#sameAs"
                                     "https://schema.org/sameAs")}}
        property-map (into built-in-properties
                           (map (juxt (comp keyword :block/title) identity)
                                properties))]
    (set
     (concat
      (add-class-instances db config property-map options)
      (when (seq (:additional-instances-query config))
        (add-additional-instances db config property-map options))
      (when add-labels
        (map (fn [[k v]]
               (block->label-triple
                {:url (:url v) :block/title (name k)}))
             (dissoc built-in-properties :block/title)))))))

(defn- add-quads [writer quads]
  (doseq [[q1 q2 q3]
          (map (fn [q]
                 (map #(if (and (string? %) (url? %))
                         (.namedNode DataFactory %)
                         (.literal DataFactory %))
                      q))
               (sort quads))]
    (.addQuad writer (.quad DataFactory q1 q2 q3))))

(defn- read-config [config]
  (try
    (edn/read-string config)
    (catch :default _
      (println "Error: Failed to parse config. Make sure it is valid EDN")
      (js/process.exit 1))))

(defn get-graph-config [dir user-config]
  (merge-with (fn [v1 v2]
                (if (and (map? v1) (map? v2))
                  (merge v1 v2) v2))
              config/default-config
              (when (fs/existsSync (path/join dir ".rdf-export" "config.edn"))
                (read-config
                 (str (fs/readFileSync (path/join dir ".rdf-export" "config.edn")))))
              (when (seq user-config)
                (read-config user-config))))

(def spec
  "Options spec"
  ;; Adds labels for all entities that are missing them e.g. properties and
  ;; aliased entities
  {:add-labels {:coerce :boolean
                :alias :a
                :desc "Add rdfs:labels for all entities"}
   :cache-dir {:alias :C
               :desc "Cache directory for db"}
   :config {:alias :c
            :desc "Edn config map"}
   :directory {:desc "Graph directory to export"
               :alias :d
               :default "."}
   :help   {:alias :h
            :coerce :boolean
            :desc "Print help"}})

(defn- get-db
  "If cached db exists get it, otherwise parse for a fresh db"
  [graph-dir cache-dir]
  ;; cache-db from https://github.com/logseq/bb-tasks
  (let [cache-file (path/join (or cache-dir ".") ".cached-db-transit.json")]
    (if (fs/existsSync cache-file)
      (do
        (println "Reading from cached db")
        (dt/read-transit-str (fs/readFileSync cache-file)))
      (let [{:keys [conn]} (gp-cli/parse-graph graph-dir {:verbose false})] @conn))))

(defn- macro-subs
  [macro-content arguments]
  (loop [s macro-content
         args arguments
         n 1]
    (if (seq args)
      (recur
       (string/replace s (str "$" n) (first args))
       (rest args)
       (inc n))
      s)))

(defn- macro-expand-entity
  "Checks each ent value for a macro and expands it if there's a logseq config for it"
  [m logseq-config]
  (update-vals m
               #(if-let [[_ macro args] (and (string? %)
                                             (seq (re-matches #"\{\{(\S+)\s+(.*)\}\}" %)))]
                  (if-let [content (get-in logseq-config [:macros macro])]
                    (macro-subs content (string/split args #"\s+"))
                    %)
                  %)))

(defn write-rdf-file
  "Given a graph's dir, covert to rdf and write to given file."
  [dir file & [options]]
  (let [graph-config (get-graph-config dir (:config options))
        db (get-db dir (:cache-dir options))
        logseq-config (if (fs/existsSync (path/join dir "logseq" "config.edn"))
                        (-> (path/join dir "logseq" "config.edn") fs/readFileSync str edn/read-string)
                        {})
        writer (Writer. (clj->js {:prefixes (:prefixes graph-config)
                                  :format (:format graph-config)}))
        graph-config' (if (:expand-macros graph-config)
                        (assoc graph-config :expand-entity-fn #(macro-expand-entity % logseq-config))
                        (assoc graph-config :expand-entity-fn identity))
        quads (create-quads writer db graph-config' options)]
    (add-quads writer quads)
    (.end writer (fn [_err result]
                   (println "Writing" (count quads) "triples to file" file)
                   (fs/writeFileSync file result)))))

(defn -main [& args]
  (let [{:keys [directory help] :as options} (cli/parse-opts args {:spec spec})
        _ (when (or help (zero? (count args)))
            (println (str "Usage: logseq-rdf-export FILE [OPTIONS]\nOptions:\n"
                          (cli/format-opts {:spec spec})))
            (js/process.exit 1))
        ;; In CI, move up a directory since the script is run in subdirectory of
        ;; a project
        directory' (if js/process.env.CI (path/join ".." directory) directory)]
    (write-rdf-file directory' (first args) (select-keys options [:cache-dir :config :add-labels]))))

#js {:main -main}
