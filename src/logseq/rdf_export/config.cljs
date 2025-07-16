(ns logseq.rdf-export.config)

(def default-config
  "Graph-specific config to define behaviors for this export"
  {;; Recommended: Base url for all pages in the graph
   :base-url
   "https://example.com/#/page/"

   ;; The rest of these config keys are optional:

   ;; Rdf format to write to. Defaults to turtle.
   ;; Other possible values - n-triples, n-quads, trig
   :format "turtle"
   ;; Shortens urls in turtle output
   :prefixes
   {:s "https://schema.org/"}
   ;; Property used to look up urls of property pages. Defaults
   ;; to :url. For example, in order to resolve the description property,
   ;; the description page has a url property with its full url.
   :url-property :url
   ;; Property used to look up a Class of an entity. Default to :type
   :type-property :type
   ;; When an entity doesn't have a unique string to identify it, usually through
   ;; :block/title, look up these properties in order
   :unique-id-properties []
   ;; Properties that are excluded in export for all entities.
   ;; Useful if some properties cause issues with a rdf consumer
   :exclude-properties []
   ;; When enabled expands macros in entities if they exist
   :expand-macros false
   ;; Silences warning messages for classes of entities that don't have a unique id
   :classes-without-ids #{}

   ;; The rest of the config determines what pages in your graph are included in
   ;; the output. All triples/properties of a page are included.
   ;; These config keys are all optional as they have useful defaults.
   ;;
   ;; Query to fetch all pages that are properties.
   ;; Defaults to pages with "type:: [[Property]]"
   :property-query
   '[:find (pull ?b [*])
     :in $ %
     :where
     (page-property ?b :type "Property")]
   ;; Query to fetch all entities that are instances of classes.
   ;; Defaults to pages with "type:: [[X]]" where X are pages with
   ;; "type:: [[Class]]"
   :class-instances-query
   '[:find (pull ?b2 [*])
     :in $ %
     :where
     (page-property ?b :type "Class")
     [?b :block/title ?n]
     (page-property ?b2 :type ?n)]
   ;; Query to fetch additional instances.
   ;; Useful for instances that aren't fetched by :class-instances-query
   ;; e.g. block-level instances.
   ;; Defaults to nil
   :additional-instances-query nil})