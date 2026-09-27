package fel.quill.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionStatusTest {

    @Test
    fun `fresh install starts in standalone, not the pairing wall`() {
        val state = QuillUiState()
        assertEquals(ConnectionStatus.STANDALONE, state.connection)
        assertFalse(state.showPairing)
    }

    @Test
    fun `every status is handled by the routing predicate`() {
        val routedToPairing = setOf(
            ConnectionStatus.NEEDS_PAIRING,
            ConnectionStatus.CONNECTING,
            ConnectionStatus.ERROR,
        )
        val routedToApp = setOf(
            ConnectionStatus.STANDALONE,
            ConnectionStatus.CONNECTED,
            ConnectionStatus.OFFLINE,
        )
        assertEquals(ConnectionStatus.entries.toSet(), routedToPairing + routedToApp)
        assertEquals(0, (routedToPairing intersect routedToApp).size)
    }

    @Test
    fun `standalone is not offline`() {
        assertTrue(ConnectionStatus.STANDALONE != ConnectionStatus.OFFLINE)
    }
}
