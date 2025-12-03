package net.postchain.gtx.extensions.vectordb.config

import net.postchain.common.exception.UserMistake
import net.postchain.config.app.AppConfig
import org.http4k.core.Credentials

data class VectorDbNodeVLLMConfig(
        val url: String,
        val basicAuth: Credentials? = null,
) {
    companion object {
        private const val CONFIG_ENV_PREFIX = "POSTCHAIN_EXTENSION_VECTOR_DB_EMBEDDING_"
        private const val URL = "${CONFIG_ENV_PREFIX}URL"
        private const val BASIC_AUTH_USER = "${CONFIG_ENV_PREFIX}BASIC_AUTH_USER"
        private const val BASIC_AUTH_PASSWORD = "${CONFIG_ENV_PREFIX}BASIC_AUTH_PASSWORD"

        @JvmStatic
        fun fromAppConfig(config: AppConfig): VectorDbNodeVLLMConfig {
            val basicAuthUser = config.getEnvOrString(BASIC_AUTH_USER, "extension.vector_db.embedding.basic_auth_user")
            val basicAuthPassword = config.getEnvOrString(BASIC_AUTH_PASSWORD, "extension.vector_db.embedding.basic_auth_password")
            if (basicAuthUser != null && basicAuthPassword == null) {
                throw UserMistake("If $BASIC_AUTH_USER is set, $BASIC_AUTH_PASSWORD must be set as well")
            }
            if (basicAuthUser == null && basicAuthPassword != null) {
                throw UserMistake("If $BASIC_AUTH_PASSWORD is set, $BASIC_AUTH_USER must be set as well")
            }
            return VectorDbNodeVLLMConfig(
                    url = config.getEnvOrString(URL, "extension.vector_db.embedding.url")
                            ?: throw UserMistake("Vector DB vLLM URL must be configured"),
                    basicAuth = if (basicAuthUser != null && basicAuthPassword != null)
                        Credentials(basicAuthUser, basicAuthPassword)
                    else null,
            )
        }
    }
}