package net.postchain.gtx.extensions.vectordb.config

import net.postchain.common.exception.UserMistake
import net.postchain.config.app.AppConfig
import net.postchain.gtx.extensions.vectordb.embedding.client.EmbeddingApiType
import org.http4k.core.Credentials

data class VectorDbEmbeddingNodeConfig(
        /** The name of the model to request from embedding API - this is not the same name as used in bc config */
        val model: String,

        /** The type of embedding API to use */
        val apiType: EmbeddingApiType,

        /** The URL of the embedding API */
        val url: String,

        /** Basic auth credentials */
        val basicAuth: Credentials? = null,

        /** Bearer token for Authorization header*/
        val authBearer: String? = null,

        /** API key for a `X-API-Key` header */
        val xApiKey: String? = null,
) {
    companion object {
        private const val CONFIG_ENV_PREFIX = "POSTCHAIN_EXTENSION_VECTOR_DB_EMBEDDING_"
        private const val CONFIG_PROPERTY_PREFIX = "extension.vector_db.embedding."

        @JvmStatic
        fun fromAppConfig(config: AppConfig, bcModel: String): VectorDbEmbeddingNodeConfig {
            val model = (config.getEmbeddingEnvOrString(bcModel, "model")
                    ?: throw UserMistake("Vector DB embedding model must be configured"))
            val url = (config.getEmbeddingEnvOrString(bcModel, "url")
                    ?: throw UserMistake("Vector DB embedding url must be configured"))
            val apiType = config.getEmbeddingEnvOrString(bcModel, "api_type")?.uppercase()?.let { EmbeddingApiType.valueOf(it) }
            val basicAuthUser = config.getEmbeddingEnvOrString(bcModel, "basic_auth_user")
            val basicAuthPassword = config.getEmbeddingEnvOrString(bcModel, "basic_auth_password")

            if (basicAuthUser != null && basicAuthPassword == null) {
                throw UserMistake("If user is set, password must be set as well for model $bcModel")
            }
            if (basicAuthUser == null && basicAuthPassword != null) {
                throw UserMistake("If password is set, user must be set as well for model $bcModel")
            }

            return VectorDbEmbeddingNodeConfig(
                    model = model,
                    apiType = apiType ?: EmbeddingApiType.OPENAI,
                    url = url,
                    basicAuth = if (basicAuthUser != null && basicAuthPassword != null)
                        Credentials(basicAuthUser, basicAuthPassword)
                    else null,
                    config.getEmbeddingEnvOrString(bcModel, "auth_bearer"),
                    config.getEmbeddingEnvOrString(bcModel, "x_api_key"),
            )
        }

        private fun embeddingPropertyName(bcModel: String, name: String) = "${CONFIG_PROPERTY_PREFIX}${bcModel}.$name"

        private fun embeddingEnvVarName(bcModel: String, name: String) = "${CONFIG_ENV_PREFIX}${bcModel.uppercase()}_$name"

        fun AppConfig.getEmbeddingEnvOrString(bcModel: String, key: String): String? {
            return getEnvOrString(embeddingEnvVarName(bcModel, key.uppercase()),
                    embeddingPropertyName(bcModel, key.lowercase()))
        }
    }
}