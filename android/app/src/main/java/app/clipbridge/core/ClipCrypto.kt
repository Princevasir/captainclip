package app.clipbridge.core

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** AES-256-GCM. Box = nonce(12) ‖ ciphertext ‖ tag(16). AAD = "clipbridge-v1". Matches the Mac's CryptoKit. */
class ClipCrypto(key: ByteArray) {
    private val keySpec: SecretKeySpec

    init {
        require(key.size == 32) { "key must be 32 bytes" }
        keySpec = SecretKeySpec(key, "AES")
    }

    fun seal(plain: ByteArray, nonce: ByteArray? = null): ByteArray {
        val iv = nonce ?: ByteArray(NONCE_BYTES).also { random.nextBytes(it) }
        require(iv.size == NONCE_BYTES) { "nonce must be 12 bytes" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(AAD)
        return iv + cipher.doFinal(plain)
    }

    fun open(box: ByteArray): ByteArray {
        require(box.size >= NONCE_BYTES + TAG_BITS / 8) { "box too short" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(TAG_BITS, box, 0, NONCE_BYTES))
        cipher.updateAAD(AAD)
        return cipher.doFinal(box, NONCE_BYTES, box.size - NONCE_BYTES)
    }

    companion object {
        private const val NONCE_BYTES = 12
        private const val TAG_BITS = 128
        private val AAD = "clipbridge-v1".toByteArray()
        private val random = SecureRandom()

        fun randomKey(): ByteArray = ByteArray(32).also { random.nextBytes(it) }
    }
}
