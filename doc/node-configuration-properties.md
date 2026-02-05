This page documents all node configuration properties related to this extension.

### Embedding models

A node can be configured to use multiple embedding models by configuring multiple `<model>` properties. The `<model>` is
the name to be used on the blockchain, and is used for the node to lookup the model API configuration. This also helps
us use different services for different nodes since the model name often is named slightly different.

A blockchain is then using one of the supported models:

```yaml
vector_db_extension:
  embedding_compute:
    model: "qwen3_embedding_0_6b"
```

| Name                                                        | Description                                                  | Type     | Required | Default  | Environment Variable                                                  |
|-------------------------------------------------------------|--------------------------------------------------------------|----------|----------|----------|-----------------------------------------------------------------------|
| `extension.vector_db.embedding.<model>.api_type`            | Type of service API, either `openai` or `gcp`.               | String   | No       | "openai" | `POSTCHAIN_EXTENSION_VECTOR_DB_EMBEDDING_<model>_API_TYPE`            |
| `extension.vector_db.embedding.<model>.basic_auth_user`     | Basic http auth username.                                    | String   | No       |          | `POSTCHAIN_EXTENSION_VECTOR_DB_EMBEDDING_<model>_BASIC_AUTH_USER`     |
| `extension.vector_db.embedding.<model>.basic_auth_password` | Basic http auth password.                                    | String   | No       |          | `POSTCHAIN_EXTENSION_VECTOR_DB_EMBEDDING_<model>_BASIC_AUTH_PASSWORD` |
| `extension.vector_db.embedding.<model>.auth_bearer`         | Token for `Authorization: Bearer <token>` format.            | String   | No       |          | `POSTCHAIN_EXTENSION_VECTOR_DB_EMBEDDING_<model>_AUTH_BEARER`         |
| `extension.vector_db.embedding.<model>.x_api_key`           | Value for a `X-API-Key` header (used for `GCP`).             | String   | No       |          | `POSTCHAIN_EXTENSION_VECTOR_DB_EMBEDDING_<model>_X_API_KEY`           |
| `extension.vector_db.embedding.<model>.model`               | The name to use in request against the model service API.    | String   | Yes      |          | `POSTCHAIN_EXTENSION_VECTOR_DB_EMBEDDING_<model>_MODEL`               |
| `extension.vector_db.embedding.<model>.url`                 | The URL of the model service API.                            | String   | Yes      |          | `POSTCHAIN_EXTENSION_VECTOR_DB_EMBEDDING_<model>_URL`                 |
| `extension.vector_db.embedding.<model>.retry_count`         | How many times to retry embedding HTTP request if it fails . | Integer  | No       | 2        | `POSTCHAIN_EXTENSION_VECTOR_DB_EMBEDDING_<model>_RETRY_COUNT`         |
| `extension.vector_db.embedding.<model>.retry_delay`         | How long to wayt between each ettempt in milliseconds.       | Integer  | No       | 1000     | `POSTCHAIN_EXTENSION_VECTOR_DB_EMBEDDING_<model>_RETRY_DELAY`         |
