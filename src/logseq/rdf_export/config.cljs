(ns logseq.rdf-export.config)

(def default-config
  "Graph-specific config to define behaviors for this export"
  {;; Recommended: Base url for all pages in the graph
   :base-url
   "https://example.com/#/page/"
   ;; Optional: Rdf format to write to. Defaults to turtle.
   ;; Other possible values - n-triples, n-quads, trig
   :format "turtle"
   ;; Optional: Shortens urls in turtle output
   :prefixes
   {:s "https://schema.org/"}
   ;; Optional: Property used to look up urls of property pages. Defaults
   ;; to :url. For example, in order to resolve the description property,
   ;; the description page has a url property with its full url.
   :url-property :url
   ;; Properties that are excluded in export for all entities.
   ;; Useful if some properties cause issues with a rdf consumer
   :exclude-properties []

   ;; The rest of the config determines what pages in your graph are included in
   ;; the output. All triples/properties of a page are included.
   ;; These config keys are all optional as they have useful defaults.
   ;;
   ;; Optional: Useful to add individual pages pages e.g. class and property pages
   :additional-pages #{"Class" "Property"}
   ;; Optional: Query to fetch all pages that are classes
   ;; Defaults to pages with "type:: [[Class]]"
   :class-query
   '[:find (pull ?b [*])
     :in $ %
     :where
     (page-property ?b :type "Class")]
   ;; Optional: Query to fetch all pages that are properties.
   ;; Defaults to pages with "type:: [[Property]]"
   :property-query
   '[:find (pull ?b [*])
     :in $ %
     :where
     (page-property ?b :type "Property")]
   ;; Optional:: Query to fetch all pages that are instances of classes.
   ;; Defaults to pages with "type:: [[X]]" where X are pages with
   ;; "type:: [[Class]]"
   :class-instances-query
   '[:find (pull ?b2 [*])
     :in $ %
     :where
     (page-property ?b :type "Class")
     [?b :block/original-name ?n]
     (page-property ?b2 :type ?n)]})
