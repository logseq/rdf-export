## Unreleased

* Add support for block level exports
  * Use a a block's page name as an identity when no name is found
  * Add :unique-id-properties config to uniquely identify a block when no
    :block/name exists
  * Add warnings when a block can't be exported and a :classes-without-ids to
    silence them for certain classes
* Add :expand-macros config to expand macros in export
* Add --add-labels CLI option to add labels to all entities and use more
  standard vocabs like rdfs and owl
* Add :exclude-properties config to exclude certain properties from export
* Add :type-property config to look up an entity's class

## 0.1.0

* Initial release with rdf being exported to a file and validated
