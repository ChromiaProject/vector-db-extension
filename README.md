# Vector DB Extension

TODO

## Registration

```shell
pmc subnode-image add --name vector_db_extension \
  --url registry.gitlab.com/chromaway/core/vector-db-extension/chromaway/vector-db-extension-chromia-subnode \
  --digest sha256:TODO \
  --image-description "Extensions to Postchain for Postgres Vector DB support" \
  -gtx net.postchain.vectordb.VectorDBGTXModule \
  -sync net.postchain.vectordb.VectorDBSynchronizationInfrastructureExtension
```

This will generate a proposal which need to be voted on.
