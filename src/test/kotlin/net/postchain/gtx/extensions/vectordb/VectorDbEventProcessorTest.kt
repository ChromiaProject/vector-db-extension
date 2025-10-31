package net.postchain.gtx.extensions.vectordb

import assertk.assertThat
import assertk.assertions.isEqualTo
import net.postchain.common.exception.UserMistake
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.mock
import org.mockito.kotlin.whenever

class VectorDbEventProcessorTest {
    private lateinit var dbaMock: VectorDbDatabaseAccess
    private lateinit var context: VectorDbGTXModuleContext
    private lateinit var processor: VectorDbEventProcessor

    @BeforeEach
    fun beforeEach() {
        dbaMock = mock()
        context = mock()
        processor = VectorDbEventProcessor(dbaMock, context)
    }

    @Test
    fun `create collection - throws when dynamic collections are disabled`() {
        whenever(context.dynamicCollectionsEnabled()).thenReturn(false)
        val exception = assertThrows<UserMistake> {
            processor.processEmittedEvent(mock(), EVENT_CREATE_COLLECTION, mock())
        }
        assertThat(exception.message).isEqualTo("Dynamic collection support is disabled in the configuration")
    }


    @Test
    fun `delete collection - throws when dynamic collections are disabled`() {
        whenever(context.dynamicCollectionsEnabled()).thenReturn(false)
        val exception = assertThrows<UserMistake> {
            processor.processEmittedEvent(mock(), EVENT_DELETE_COLLECTION, mock())
        }
        assertThat(exception.message).isEqualTo("Dynamic collection support is disabled in the configuration")
    }
}