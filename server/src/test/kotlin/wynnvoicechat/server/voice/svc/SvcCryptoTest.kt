package wynnvoicechat.server.voice.svc

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class SvcCryptoTest {
    @Test
    fun `new secret is 16 random bytes`() {
        assertEquals(16, SvcCrypto.newSecret().size)
        assertFalse(SvcCrypto.newSecret().contentEquals(SvcCrypto.newSecret()))
    }

    @Test
    fun `encrypt decrypt round trip`() {
        val secret = SvcCrypto.newSecret()
        val plain = byteArrayOf(1, 2, 3, 4, 5)
        val payload = SvcCrypto.encrypt(secret, plain)
        assertEquals(12 + plain.size + 16, payload.size)
        assertContentEquals(plain, SvcCrypto.decrypt(secret, payload))
    }

    @Test
    fun `wrong key tampered or short payloads decrypt to null`() {
        val secret = SvcCrypto.newSecret()
        assertNull(SvcCrypto.decrypt(SvcCrypto.newSecret(), SvcCrypto.encrypt(secret, byteArrayOf(9))))
        val tampered = SvcCrypto.encrypt(secret, byteArrayOf(9, 9, 9))
        tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 1).toByte()
        assertNull(SvcCrypto.decrypt(secret, tampered))
        assertNull(SvcCrypto.decrypt(secret, ByteArray(10)))
    }
}
