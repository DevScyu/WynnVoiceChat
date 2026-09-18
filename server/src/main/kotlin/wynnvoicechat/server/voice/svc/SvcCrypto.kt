package wynnvoicechat.server.voice.svc

import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Simple Voice Chat compat-20 payload encryption: AES-128-GCM, random 12-byte IV prepended, 128-bit tag.
 */
object SvcCrypto {
    const val SECRET_SIZE = 16
    private const val IV_SIZE = 12
    private const val TAG_BITS = 128
    private const val CIPHER = "AES/GCM/NoPadding"
    private val random = SecureRandom()

    fun newSecret(): ByteArray = ByteArray(SECRET_SIZE).also(random::nextBytes)

    fun encrypt(secret: ByteArray, plain: ByteArray): ByteArray {
        val iv = ByteArray(IV_SIZE).also(random::nextBytes)
        val cipher = Cipher.getInstance(CIPHER)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(secret, "AES"), GCMParameterSpec(TAG_BITS, iv))
        return iv + cipher.doFinal(plain)
    }

    fun decrypt(secret: ByteArray, payload: ByteArray): ByteArray? {
        if (payload.size < IV_SIZE + TAG_BITS / 8) return null
        return try {
            val cipher = Cipher.getInstance(CIPHER)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(secret, "AES"), GCMParameterSpec(TAG_BITS, payload, 0, IV_SIZE))
            cipher.doFinal(payload, IV_SIZE, payload.size - IV_SIZE)
        } catch (e: GeneralSecurityException) {
            null
        }
    }
}
