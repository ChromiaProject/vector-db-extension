# Vector DB Extension

📝 **Note:** This documentation is for version 2. You can find the documentation for version 1 [here](https://gitlab.com/chromaway/core/vector-db-extension/-/tree/support/v1).

## Setup

### Register extension in directory-chain

The nodes in the network must add the extension to make it available for containers to use. This is already done on official networks, but in case you run your own node(s) you will need to run this command:

```shell
pmc subnode-image add --name vector_db_extension \
  --url registry.gitlab.com/chromaway/core/vector-db-extension/chromaway/vector-db-extension-chromia-subnode \
  --digest <digest> \
  --image-description "Extension to Postchain for Postgres Vector DB support" \
  -gtx net.postchain.gtx.extensions.vectordb.VectorDbGTXModule,net.postchain.hybridcompute.HybridComputeGTXModule \
  -sync net.postchain.hybridcompute.HybridComputeSynchronizationInfrastructureExtension
```

Replace the `<digest>` with the latest `2.x.x` image version found [here](https://gitlab.com/chromaway/core/vector-db-extension/container_registry/8296249).

### Blockchain configuration

You can manage vector collections in two ways:
- **Static**: Collections are defined in the blockchain configuration. Update the configuration to add new collections or disable existing ones (not delete).
- **Dynamic**: Collections are created, updated, and deleted by the dApp through the library functions. This gives you full control and also supports removing a collection completely.

You should decide which mode you prefer, since you won't be able to switch modes or use both later.
 
Update your blockchain config to include the module and collections configuration (omit if you prefer using dynamic mode):
```yaml
blockchains:
  my_chain:
    module: my_chain_module
    config:
      gtx:
        modules:
          - "net.postchain.gtx.extensions.vectordb.VectorDbGTXModule"
      # Static collections defined below, omit if using dynamic mode
      vector_db_extension:
        collections:
          messages:
            dimensions: 300 # Number of dimensions for vectors stored in this collection
            query_max_vectors: 10 # Optional: Upper limit for per-query results. Default is 10 if not set.
            store_batch_size: 300 # Optional: Batch size used when inserting into the database.
            index: HNSW_COSINE # Optional: distance/metric type. HNSW_L1, HNSW_L2 and HNSW_IP are also supported.
```

And make sure you deploy your chain to a container with the extension supported.

#### Query compute

**⚠️ Warning:** This feature is experimental and not ready for production use. It is a proof of concept and may change in the future.

The extension supports computing queries asynchronously via the hybrid compute infrastructure. Update the blockchain configuration to enable this:

```yaml
blockchains:
  my_chain:
    module: my_chain_module
    config:
      gtx:
        modules:
          - "net.postchain.gtx.extensions.vectordb.VectorDbGTXModule"
          - "net.postchain.hybridcompute.HybridComputeGTXModule"
      sync_ext:
        - "net.postchain.hybridcompute.HybridComputeSynchronizationInfrastructureExtension"
      hybridcompute:
        engines:
        - "net.postchain.gtx.extensions.vectordb.VectorDBQueryComputeEngine"
      vector_db_extension:
        query_compute:
            timeout_seconds: 3 # Optional: Timeout used for both compute and validate. Default is 3.
```

Then use the [Rell library](#query-compute-library) to request query computations.

#### Compute embeddings

The extension supports computing embeddings via the hybrid compute infrastructure by calling an
external service and then executing a Rell function with the results.  Update the blockchain configuration to enable this:

```yaml
blockchains:
  my_chain:
    module: my_chain_module
    config:
      gtx:
        modules:
          - "net.postchain.gtx.extensions.vectordb.VectorDbGTXModule"
          - "net.postchain.hybridcompute.HybridComputeGTXModule"
      sync_ext:
        - "net.postchain.hybridcompute.HybridComputeSynchronizationInfrastructureExtension"
      hybridcompute:
        engines:
        - "net.postchain.gtx.extensions.vectordb.VectorDBEmbeddingComputeEngine"
      vector_db_extension:
        embedding_compute:
            model: "<model>"
            timeout_seconds: 3 # Optional: Timeout used for both compute and validate to retrieve result from the model. Default is 3.
```

The model can be any model supported by the cluster. Then use the [Rell library](#embedding-compute-library) to request embeddings.

## How to use in the dApp

### Rell libraries

#### Vector management library

There is an optional but recommended library available to manage vectors:

```yaml
libs:
  com.chromia.vector_db:
    version: 2.2.0 # Set to version you want to use
```

Available versions can be found by running `chr library versions com.chromia.vector_db`.

Run `chr install` to install the library. Once installed you can manage dynamic vector collections and vectors for each collection.

#### Query compute library

**⚠️ Warning:** This feature is experimental and not ready for production use. It is a proof of concept and may change in the future.

To compute vector queries you need to add the following libraries:

```yaml
libs:
  com.chromia.vector_db_query_compute:
    version: 2.3.2 # Set to the version you want to use
  com.chromia.hybridcompute: # vector_db_query_compute depends on this library
    version: 3.35.4
```

Available versions can be found by running `chr library versions com.chromia.vector_db_query_compute`.

Run `chr install` to install the library. Once installed you can submit vector queries to be processed asynchronously:

```rell
operation submit_query_request(
    id: text,
    q_vector: text,
    max_distance: decimal,
    max_vectors: integer? = null
) {
    submit_vector_db_query_request(id, "my-collection", q_vector, max_distance, max_vectors, null);
}

@extend(hc.on_compute_result)
function (id: text, type: text, result: hc.compute_result) {
    if (type != my_type) return;

    log("Query computation for query id %s completed".format(id));
}
```

#### Embedding compute library

To compute embeddings you need to add the following libraries:

```yaml
libs:
  com.chromia.vector_db_embedding_compute:
    version: 0.1.0 # Set to the version you want to use
  com.chromia.hybridcompute: # vector_db_query_compute depends on this library
    version: 3.35.4
```

Run `chr install` to install the library. Once installed you can submit embedding requests to be processed asynchronously:

```rell
operation embed(id: text, text: text) {
    create text_embedding(id, text);
    // Generate embeddings for one or multiple texts in the same request
    submit_text_embedding_request(id, [text]);
}

// Process the embedding result
@extend(on_embedding_result)
function (id: text, embedding_result: embedding_result) {
    /* `embedding_result.result.embeddings` contains one embedding per text submitted in the request and in the same order */
    /* Embeddings can be stored as vectors: store_vector("my_collection", contextId, vector, id); */
}
```

### Insert vectors

Simple dapp to store and remove vectors:

```rell
import lib.vector_db.*;

/**
 * Add a message to the vector database
 *
 * @param collection The name of the collection to store the message in, must match one defined in blockchain config.
 * @param context The context grouping key used by dApp.
 * @param text The text message represented by this vector
 * @param vector The vector on format [1.0,2.0,...]
 */
operation add_message(collection: text, context: integer, vector: text, id: integer) {
    store_vector(collection, context, vector, id);
}

/**
 * Delete a message from the vector database
 *
 * @param collection The name of the collection to store the message in, must match one defined in blockchain config.
 * @param context The context grouping key used by dApp.
 * @param id The id of the message to delete
 */
operation delete_message(collection: text, context: integer, id: integer) {
    delete_vector(collection, context, id);
}
```

### Querying vectors

The extension will add a query function named `query_closest_objects` which can be called to search vectors.

It supports the following parameters:

| Name                 | Type                                 | Required | Default | Description                                                                                                |
|----------------------|--------------------------------------|----------|---------|------------------------------------------------------------------------------------------------------------|
| `collection`         | `text`                               | true     |         | Name of the collection to search (must match one defined in blockchain config).                            |
| `context`            | `integer`                            | false    |         | Optional context grouping key used by dApp. If omitted, search runs across all contexts in the collection. |
| `q_vector`           | vector as `text`                     | true     |         | The query vector as `text` on format `[1,2,3]`.                                                            |
| `max_distance`       | `decimal`                            | true     |         | The max distance from `q_vector` to stored vectors.                                                        |
| `query_max_vectors`  | `integer`                            | false    | 10      | The max number of vectors to return (cannot exceed the limit defined in collection configuration).         |
| `query_template`     | `(name: text, args: map<text, gtv>)` | false    | Not set | Provide a Rell query function to transform the results (see below).                                        |

### Query template

When no `query_template` is provided to `query_closest_objects` the result returned is a list of vector ids and their distance. 
This can however be transformed by providing a Rell query function:

```
query get_messages(closest_results: list<object_distance>): list<text> {
    val closest_result_ids = closest_results @ {} ( @set(rowid(.id)) );
    return message @ { .rowid in closest_result_ids } ( .text );
}
```

This function will transform the vector search result `closest_results: list<object_distance>` into a list of text. 
When `query_template=(name: "get_messages")` is provided to `query_closest_objects` the result will be a list of text. 

## Local run and example

This requires:
 - Docker
 - `chr`
 - `pmc`

Setup a node locally by using the [directory1-example image](https://gitlab.com/chromaway/example-projects/directory1-example/-/blob/dev/docs/images.md?ref_type=heads).

```bash
docker run --rm -it -p 7740:7740 registry.gitlab.com/chromaway/example-projects/directory1-example/managed-single:latest
```

In a separate terminal with `pmc` setup:

```bash
# Build the demo dapp
cd vector-db-extension/rell
chr build

# Add the demo dapp
pmc blockchain add -bc vector-db-extension/rell/build/vector_example.xml -c dapp -n vector_blockchain

# Get the blockchain rid - can be found manually from "pmc blockchains"
vector_brid=$(pmc blockchains | jq -r '.[] | select(.Name == "vector_blockchain") | .Rid')

```

Add some messages:

```bash
chr tx -brid $vector_brid add_message hej '"[1.0, 2.0, 3.0]"'
chr tx -brid $vector_brid add_message hello '"[1.0, 2.5, 3.0]"'
chr tx -brid $vector_brid add_message hei '"[1.0, 2.0, 3.1]"'
chr tx -brid $vector_brid add_message "guten tag" '"[1.0, 1.5, 3.5]"'
```

A few example queries:

```bash
# Plain query with no query_template:
chr query -brid $vector_brid query_closest_objects collection=messages context=0 'q_vector="[1.0, 2.0, 3.0]"' max_distance=1.0 query_max_vectors=2
[
  [
    "distance": "0",
    "id": 1
  ],
  [
    "distance": "0.0001212999220387978",
    "id": 3
  ]
]

# Basic query_template provided to return the text messages:
chr query -brid $vector_brid query_closest_objects collection=messages context=0 'q_vector="[1.0, 2.5, 3.0]"' max_distance=1.0 query_max_vectors=2 'query_template=["name":"get_messages"]'
[
  "hello",
  "hej"
]

# Another query_template which returns text and distance:
chr query -brid $vector_brid query_closest_objects collection=messages context=0 'q_vector="[1.0, 2.5, 3.0]"' max_distance=1.0 query_max_vectors=2 'query_template=["name":"get_messages_with_distance"]'
[
  [
    "distance": "0",
    "text": "hello"
  ],
  [
    "distance": "0.005509683802306209",
    "text": "hej"
  ]
]

# Additional arguments passed to the query_template function
chr query -brid $vector_brid query_closest_objects collection=messages context=0 'q_vector="[1.0, 2.5, 3.0]"' max_distance=1.0 query_max_vectors=2 'query_template=["name":"get_messages_with_filter", "args":["text_filter": "j"]]'
[
  "hej",
]
```
