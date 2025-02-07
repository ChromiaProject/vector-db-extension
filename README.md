# Vector DB Extension

## Registration

```shell
pmc subnode-image add --name vector_db_extension \
  --url registry.gitlab.com/chromaway/core/vector-db-extension/chromaway/vector-db-extension-chromia-subnode \
  --digest <digest> \
  --image-description "Extension to Postchain for Postgres Vector DB support" \
  -gtx net.postchain.gtx.extensions.vectordb.VectorDbGTXModule
```

This will generate a proposal which need to be voted on.

## Configuration

Update your blockchain config to include the following:

```yaml
blockchains:
  my_chain:
    module: my_module
    config:
      gtx:
        modules:
          - "net.postchain.gtx.extensions.vectordb.VectorDbGTXModule"
      vector_db_extension:
        dimensions: 300 # Set number of dimensions to use
```

## Usage

Add the vector db library to your chain:

```yaml
  vector_db:
    registry: https://gitlab.com/chromaway/core/vector-db-extension.git
    path: rell/src/lib/
    tagOrBranch: <version>
    rid: x"C99366A7BB02F549D15F7388DC1647F3B01188924A56872365A35071E0F27255" # Update to match version
    insecure: false
```

Import and store vectors:

```
import lib.vector_db.*;

operation add_vector(context: integer, vector: text, id: integer) {
    store_vector(context, vector, id);
}
```

Query vectors:

Call `query_closest_objects` to search vectors. The query supports the following parameters:

- `context`: The context of the vector.
- `q_vector`: The vector to search for.
- `max_distance`: The maximum distance to search for.
- `max_vectors`: The maximum number of vectors to return.
- `query_template`: The query template function to use, specified in the sub attribute `type`. Must accept `closest_results: list<object_distance>` but can return anything.

`query_closest_objects` will return a list of `list<object_distance>` unless a `query_template` is provided which can return anything.