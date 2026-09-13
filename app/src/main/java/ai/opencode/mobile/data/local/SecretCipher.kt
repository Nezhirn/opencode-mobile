package ai.opencode.mobile.data.local

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts small secrets (the server password) with an AES/GCM key held in the
 * Android Keystore, so the value persisted in DataStore is not readable as
 * plaintext. The key never leaves the keystore.
 */
class SecretCipher {

    private val keyStore: KeyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }

    private fun secretKey(): SecretKey {
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    /**
     * Encrypts [plainText]. Failures are propagated (rather than swallowed into
     * an empty string) so the caller can tell the user the password was not
     * saved instead of silently losing it.
     */
    fun encrypt(plainText: String): String {
        if (plainText.isEmpty()) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.encodeToString(cipher.iv + cipherText, Base64.NO_WRAP)
    }

    /**
     * Returns the decrypted value, an empty string for empty input, or null when
     * the stored value cannot be decrypted. Values written before the versioned
     * prefix was introduced are still decrypted, so an upgrade does not turn a
     * stored password into literal ciphertext.
     */
    fun decrypt(encoded: String): String? {
        if (encoded.isEmpty()) return ""
        val payload = encoded.removePrefix(PREFIX)
        return runCatching {
            val combined = Base64.decode(payload, Base64.NO_WRAP)
            val iv = combined.copyOfRange(0, IV_LENGTH)
            val cipherText = combined.copyOfRange(IV_LENGTH, combined.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        }.getOrNull()
    }

    companion object {
        /** Marks the storage format; lets callers tell legacy plaintext from a broken secret. */
        const val PREFIX = "v1:"

        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "opencode_mobile_connection"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_LENGTH = 12
        private const val GCM_TAG_BITS = 128
    }
}
