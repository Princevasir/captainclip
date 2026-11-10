package app.clipbridge.core

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Which way a frame travels; bound into the AAD so frames can't be reflected back. */
enum class Direction(val byte: Byte) {
    PHONE_TO_MAC(0x50), // "P"
    MAC_TO_PHONE(0x4D), // "M"
}

/**
 * AES-256-GCM. Box = nonce(12) ‖ ciphertext ‖ tag(16). Matches the Mac's CryptoKit.
 * On a connection, AAD = "clipbridge-v1" ‖ direction ‖ the Mac's 16-byte challenge.
 */
class ClipCrypto(key: ByteArray) {
    private val keySpec: SecretKeySpec

    init {
        require(key.size == 32) { "key must be 32 bytes" }
        keySpec = SecretKeySpec(key, "AES")
    }

    fun seal(plain: ByteArray, nonce: ByteArray? = null, aad: ByteArray = AAD): ByteArray {
        val iv = nonce ?: ByteArray(NONCE_BYTES).also { random.nextBytes(it) }
        require(iv.size == NONCE_BYTES) { "nonce must be 12 bytes" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(aad)
        return iv + cipher.doFinal(plain)
    }

    fun open(box: ByteArray, aad: ByteArray = AAD): ByteArray {
        require(box.size >= NONCE_BYTES + TAG_BITS / 8) { "box too short" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(TAG_BITS, box, 0, NONCE_BYTES))
        cipher.updateAAD(aad)
        return cipher.doFinal(box, NONCE_BYTES, box.size - NONCE_BYTES)
    }

    companion object {
        private const val NONCE_BYTES = 12
        private const val TAG_BITS = 128
        private val AAD = "clipbridge-v1".toByteArray()
        private val random = SecureRandom()

        fun sessionAad(direction: Direction, challenge: ByteArray): ByteArray =
            AAD + byteArrayOf(direction.byte) + challenge

        fun randomKey(): ByteArray = ByteArray(32).also { random.nextBytes(it) }
    }
}
