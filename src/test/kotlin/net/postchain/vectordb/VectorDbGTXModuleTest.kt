package net.postchain.vectordb

import assertk.assertThat
import assertk.assertions.isEqualTo
import net.postchain.common.exception.ProgrammerMistake
import net.postchain.common.exception.UserMistake
import net.postchain.core.EContext
import net.postchain.gtv.GtvFactory.gtv
import net.postchain.gtx.extensions.vectordb.VectorDbConfig
import net.postchain.gtx.extensions.vectordb.VectorDbDatabaseOperations
import net.postchain.gtx.extensions.vectordb.VectorDbGTXModule
import net.postchain.gtx.extensions.vectordb.VectorDbGTXModuleContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.sql.Connection

class VectorDbGTXModuleTest{

    open class TestEContext() : EContext {
        override val chainID: Long
            get() = TODO("Not yet implemented")
        override val conn: Connection
            get() = TODO("Not yet implemented")

    }

    private val defaultArgs = mapOf(
            "context" to gtv(0),
            "q_vector" to gtv("[1, 2, 3]"),
            "max_distance" to gtv("0.1"),)

    @Test
    fun `validate max vectors`() {
        val context = VectorDbGTXModuleContext(VectorDbDatabaseOperations())
        context.module = VectorDbGTXModule()
        context.vectorDbConfig = VectorDbConfig(100, 10, 300)

        // More than limit set in blockchain config - reject
        assertThat(assertThrows<UserMistake> {
            VectorDbGTXModule.queryClosestObjects(context, TestEContext(),
                    gtv(defaultArgs + mapOf("max_vectors" to gtv(11L))))
        }.message).isEqualTo("max_vectors (11) exceeds the maximum of 10")

        // Max length - accepted - the exception is OK, it passed the validation
        assertThat(assertThrows<ProgrammerMistake> {
            VectorDbGTXModule.queryClosestObjects(context, TestEContext(),
                    gtv(defaultArgs + mapOf("max_vectors" to gtv(10L))))
        }.message).isEqualTo("DatabaseAccess not accessible through EContext")

        // Default value - accepted - the exception is OK, it passed the validation
        assertThat(assertThrows<ProgrammerMistake> {
            VectorDbGTXModule.queryClosestObjects(context, TestEContext(),
                    gtv(defaultArgs))
        }.message).isEqualTo("DatabaseAccess not accessible through EContext")
    }
}
