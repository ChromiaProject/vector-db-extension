package net.postchain.gtx.extensions.vectordb.config

import net.postchain.common.exception.UserMistake
import net.postchain.config.app.AppConfig
import net.postchain.gtx.extensions.vectordb.embedding.client.EmbeddingApiType
import org.http4k.core.Credentials

data class VectorDbEmbeddingNodeConfig(
        val model: String,
        val apiType: EmbeddingApiType,
        val url: String,
        val basicAuth: Credentials? = null,
        val authBearer: String? = null,
        val xApiKey: String? = null,
) {
    companion object {
        private const val CONFIG_ENV_PREFIX = "POSTCHAIN_EXTENSION_VECTOR_DB_EMBEDDING_"
        private const val CONFIG_PROPERTY_PREFIX = "extension.vector_db.embedding."

        @JvmStatic
        fun fromAppConfig(config: AppConfig, model: String): VectorDbEmbeddingNodeConfig {
            val apiType = config.getEmbeddingEnvOrString(model, "api_type")?.uppercase()?.let { EmbeddingApiType.valueOf(it) }
            val basicAuthUser = config.getEmbeddingEnvOrString(model, "basic_auth_user")
            val basicAuthPassword = config.getEmbeddingEnvOrString(model, "basic_auth_password")
            if (basicAuthUser != null && basicAuthPassword == null) {
                throw UserMistake("If user is set, password must be set as well for model $model")
            }
            if (basicAuthUser == null && basicAuthPassword != null) {
                throw UserMistake("If password is set, user must be set as well for model $model")
            }
            return VectorDbEmbeddingNodeConfig(
                    model = config.getEmbeddingEnvOrString(model, "model")
                            ?: throw UserMistake("Vector DB embedding model must be configured"),
                    apiType = apiType ?: EmbeddingApiType.OPENAI,
                    url = config.getEmbeddingEnvOrString(model, "url")
                            ?: throw UserMistake("Vector DB embedding url must be configured"),
                    basicAuth = if (basicAuthUser != null && basicAuthPassword != null)
                        Credentials(basicAuthUser, basicAuthPassword)
                    else null,
                    config.getEmbeddingEnvOrString(model, "auth_bearer"),
                    config.getEmbeddingEnvOrString(model, "x_api_key"),
            )
        }

        private fun embeddingPropertyName(model: String, name: String) = "${CONFIG_PROPERTY_PREFIX}${model}.$name"

        private fun embeddingEnvVarName(model: String, name: String) = "${CONFIG_ENV_PREFIX}${model.uppercase()}_$name"

        fun AppConfig.getEmbeddingEnvOrString(model: String, key: String) =
                getEnvOrString(embeddingEnvVarName(model, key.uppercase()), embeddingPropertyName(model, key.lowercase()))
    }
}