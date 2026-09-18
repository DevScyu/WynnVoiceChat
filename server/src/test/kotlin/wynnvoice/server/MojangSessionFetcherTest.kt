package wynnvoice.server

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MojangSessionFetcherTest {
    @Test
    fun `parses undashed profile id`() {
        val json = """{"id":"f7c77d999f154a66a87dc4a51ef30d19","name":"Player","properties":[]}"""
        assertEquals(UUID.fromString("f7c77d99-9f15-4a66-a87d-c4a51ef30d19"), MojangSessionFetcher.parseProfileId(json))
    }

    @Test
    fun `missing id is null`() {
        assertNull(MojangSessionFetcher.parseProfileId("""{"error":"nope"}"""))
    }
}
