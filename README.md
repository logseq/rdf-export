## Description

This is a [github action](https://github.com/features/actions) to export a
configurable portion of a Logseq graph to [RDF](https://www.w3.org/RDF/). Some
basic validation is also done on the exported rdf file. This action can also be
run as a [CLI](#cli).

## Usage

### Setup

By default, this action is configured to export the portion of your Logseq graph that:

* has class pages with properties `type:: [[Class]]`
* has property pages with properties `type:: [[Property]]`
* has pages with properties `type:: [[X]]` where X are pages with `type:: [[Class]]`

For an example of such a graph, see https://github.com/logseq/docs. If you would like
to organize your graph differently, you can [configure it](#configuration).

### Github Action

To setup this action, add the file `.github/workflows/ci.yml` to your graph's
github repository with the following content:

``` yaml
on: [push]

jobs:
  rdf-export:
    runs-on: ubuntu-latest
    name: Test rdf export
    steps:
      - name: Checkout
        uses: actions/checkout@v3

      - name: Export graph as RDF
        uses: logseq/rdf-export@main
```

That's it! This job will then run on future git pushes and build RDF files from your graph.

NOTE: The above example defaults to picking up new changes. If you'd prefer to stay on a stable version use the format `logseq/rdf-export@VERSION` e.g. `logseq/rdf-export@v0.1.0`. See CHANGELOG.md for released versions.

#### Action Inputs

This action can take inputs e.g.:

```yaml
- name: Export graph as RDF
  uses: logseq/rdf-export@main
  with:
    rdfFile: my-graph-name.ttl
    directory: logseq-graph-directory
```

This action has the following inputs:

##### `rdfFile`

**Required:** Name of the exported rdf file. Defaults to `test.ttl`.

##### `directory`

Optional: The directory of the graph to rdf. Defaults to `.`.

#### Use with other actions

Since this action produces a valid RDF file, it is easy to save this file for
later usage using [upload-artifact](https://github.com/actions/upload-artifact):

```yaml
...
- name: Export graph as RDF
  uses: logseq/rdf-export@main
  with:
    rdfFile: my-graph.ttl

- uses: actions/upload-artifact@v3
  with:
    name: Exported RDF
    path: my-graph.ttl
```

### CLI

To use this as a CLI locally, first install
[babashka](https://github.com/babashka/babashka#installation) and
[clojure](https://clojure.org/guides/install_clojure). Then:

```sh
$ git clone https://github.com/logseq/rdf-export
$ cd rdf-export && yarn install
$ yarn global add $PWD
```

Then use it from any logseq graph directory!
```sh
$ logseq-rdf-export docs.ttl
Parsing 301 files...
...
Writing 295 triples to file docs.ttl
```

## Configuration

To configure how and what is exported to RDF, create a `.export-rdf/config.edn`
file in your graph's directory. It's recommended to configure the `:base-url`
key so that urls point to your Logseq graph. To configure what is exported,
knowledge of [advanced
queries](https://docs.logseq.com/#/page/advanced%20queries) is required. See
[the config
file](https://github.com/logseq/rdf-export/blob/main/src/logseq/rdf_export/config.cljs)
for the full list of configuration keys.

## Development

This github action use [nbb-logseq](https://github.com/logseq/nbb-logseq) and the [graph-parser
library](https://github.com/logseq/logseq/tree/master/deps/graph-parser) to export a Logseq graph
from its database.

## LICENSE
See LICENSE.md

## Additional Links
* https://github.com/logseq/graph-validator - Github action that this one is modeled after
* https://github.com/rdfjs/N3.js - Library that handles the rdf exporting
* https://github.com/logseq/docs - Logseq graph that uses this action
