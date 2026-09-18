package wynnvoicechat.server.voice

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VoiceConfigTest {
    @Test
    fun `allowlist parses comma separated uuids and blank means open`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        assertEquals(setOf(a, b), VoiceConfig.parseUuids(" $a, $b ,"))
        assertNull(VoiceConfig.parseUuids(null))
        assertNull(VoiceConfig.parseUuids(" "))
    }
}
