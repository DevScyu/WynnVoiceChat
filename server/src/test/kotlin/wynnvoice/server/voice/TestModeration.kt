package wynnvoice.server.voice

import java.io.File

/** A fresh SQLite file under build/tmp, schema created, blocks loaded. */
fun testModeration(name: String, clock: () -> Long = System::currentTimeMillis): VoiceModeration {
    val file = File("build/tmp/$name.db").apply { parentFile.mkdirs(); delete() }
    return VoiceModeration(VoiceModeration.openSqlite(file.path), clock).also { it.init() }
}
