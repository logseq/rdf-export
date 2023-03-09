(ns logseq.rdf-export
  "This ns converts a subset of a Logseq graph to an rdf file using
https://github.com/rdfjs/N3.js'

All of the above pages can be customized with query config options."
  (:require ["n3" :refer [DataFactory Writer]]
            ["fs" :as fs]
            ["path" :as path]
            [clojure.string :as string]
            [datascript.core :as d]
            [logseq.db.rules :as rules]
            [babashka.cli :as cli]
            [clojure.edn :as edn]
            [logseq.graph-parser.cli :as gp-cli]
            [logseq.rdf-export.config :as config]
            [datascript.transit :as dt]))

(defn- propertify
  [{:keys [exclude-properties]} result]
  (map #(-> (:block/properties %)
            ;; TODO: Add proper tags support
            (dissoc :title :tags)
            ((fn [x] (apply dissoc x exclude-properties)))
            (assoc :block/original-name
                   (or (get-in % [:block/properties :title]) (:block/original-name %))))
       result))

(defn- page-url [page-name config]
  (str (:base-url config) (js/encodeURIComponent page-name)))

(defn- triplify
  "Turns an entity map into a coll of triples"
  [m {:keys [url-property] :as config} property-map]
  (mapcat
   (fn [prop]
     (map #(vector (page-url (:block/original-name m) config)
                   (or (url-property (get property-map prop))
                       (page-url (name prop) config))
                   %)
          (let [v (m prop)]
            ;; If a collection, they are refs/pages
            (if (coll? v) (map #(page-url % config) v) [v]))))
   (keys m)))

(defn- block->label-triple [block]
  [(:url block)
   "http://www.w3.org/2000/01/rdf-schema#label"
   (:block/original-name block)])

(defn- build-alias-triples
  [ents config]
  (->> ents
       (filter :alias)
       (mapcat #(map (fn [alias]
                       (block->label-triple
                        {:url (page-url alias config)
                         :block/original-name (:block/original-name %)}))
                     (:alias %)))))

(defn- add-classes [db config property-map {:keys [add-labels]}]
  (let [ents (->> (d/q (:class-query config)
                       db
                       (vals rules/query-dsl-rules))
                  (map first)
                  (propertify config))]
    (concat (mapcat #(triplify % config property-map) ents)
            (when add-labels
              (build-alias-triples ents config)))))

(defn- add-properties [ents config property-map {:keys [add-labels]}]
  (concat
   (mapcat #(triplify % config property-map) ents)
   (when add-labels
     (build-alias-triples ents config))
   (when add-labels
     (map block->label-triple (filter :url ents)))))

(defn- add-additional-pages [db config property-map {:keys [add-labels]}]
  (let [ents (->> (d/q '[:find (pull ?b [*])
                         :in $ ?names %
                         :where
                         [?b :block/name ?n]
                         [(contains? ?names ?n)]]
                       db
                       (set (map string/lower-case (:additional-pages config)))
                       (vals rules/query-dsl-rules))
                  (map first)
                  (propertify config))]
    (concat
     (mapcat #(triplify % config property-map) ents)
     (when add-labels
       ;; TODO: This makes modeling assumptions and should be made more general
       (->> ents
            (filter :type)
            (map #(block->label-triple
                   {:url (:type %)
                    :block/original-name (:block/original-name %)})))))))

(defn- add-class-instances [db config property-map {:keys [add-labels]}]
  (let [ents (->> (d/q (:class-instances-query config)
                       db
                       (vals rules/query-dsl-rules))
                  (map first)
                  (propertify config))]
    (concat
     (mapcat #(triplify % config property-map) ents)
     (when add-labels
       (build-alias-triples ents config)))))

(defn- create-quads [_writer db config {:keys [add-labels] :as options}]
  (let [properties (->> (d/q (:property-query config)
                             db
                             (vals rules/query-dsl-rules))
                        (map first)
                        (propertify config))
        built-in-properties {:block/original-name
                             {:url (if add-labels
                                     "http://www.w3.org/2000/01/rdf-schema#label"
                                     "https://schema.org/name")}
                             :alias
                             {:url (if add-labels
                                     "http://www.w3.org/2002/07/owl#sameAs"
                                     "https://schema.org/sameAs")}}
        property-map (into built-in-properties
                           (map (juxt (comp keyword :block/original-name) identity)
                                properties))]

    (concat
     (add-additional-pages db config property-map options)
     (add-classes db config property-map options)
     (add-properties properties config property-map options)
     (when add-labels
       (map (fn [[k v]]
              (block->label-triple
               {:url (:url v) :block/original-name (name k)}))
            (dissoc built-in-properties :block/original-name)))
     (add-class-instances db config property-map options))))

(defn- add-quads [writer quads]
  (doseq [[q1 q2 q3]
          (map (fn [q]
                 (map #(if (and (string? %) (string/starts-with? % "http"))
                         (.namedNode DataFactory %)
                         (.literal DataFactory %))
                      q))
               quads)]
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

(defn write-rdf-file
  "Given a graph's dir, covert to rdf and write to given file."
  [dir file & [options]]
  (let [graph-config (get-graph-config dir (:config options))
        db (get-db dir (:cache-dir options))
        writer (Writer. (clj->js {:prefixes (:prefixes graph-config)
                                  :format (:format graph-config)}))
        quads (create-quads writer db graph-config options)]
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
