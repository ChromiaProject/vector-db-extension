package net.postchain.gtx.extensions.vectordb

import net.postchain.devtools.IntegrationTestSetup
import org.junit.jupiter.api.BeforeEach
import java.sql.DriverManager
import kotlin.use

open class PGVectorBaseTest : IntegrationTestSetup() {

    companion object {
        /** Ensure that the vector extension is installed in the test database. The container creates the extension
         * on startup but for tests however we need to make sure it is in place before a test starts.
         * Previously this was managed by the vector extension, but this is no longer an option and we need to make
         * sure the extension is in place before. We install it in the public schema, or move it there if necessary.
         */
        fun ensureExtension() {
            DriverManager.getConnection(
                    System.getenv("POSTCHAIN_DB_URL") ?: "jdbc:postgresql://localhost:5432/postchain",
                    "postchain",
                    "postchain"
            ).use { conn ->
                val extensionSchema = conn.createStatement().use { stmt ->
                    stmt.executeQuery("""
                        SELECT n.nspname as schema_name
                        FROM pg_extension e
                                 JOIN pg_namespace n ON e.extnamespace = n.oid
                        WHERE e.extname = 'vector';
                    """).use { rs ->
                        if (rs.next()) {
                            rs.getString("schema_name")
                        } else null
                    }
                }

                if (extensionSchema == null) {
                    // Extension does not exist, create it
                    conn.createStatement().use { statement ->
                        statement.execute("CREATE EXTENSION IF NOT EXISTS vector SCHEMA ${VectorDbDatabaseAccess.PG_VECTOR_SCHEMA}")
                    }
                } else if (extensionSchema != VectorDbDatabaseAccess.PG_VECTOR_SCHEMA) {
                    // Extension exists in another schema, move it
                    conn.createStatement().execute("ALTER EXTENSION vector SET SCHEMA ${VectorDbDatabaseAccess.PG_VECTOR_SCHEMA}")
                }
            }
        }
    }

    @BeforeEach
    fun setup() {
        ensureExtension()
    }
}