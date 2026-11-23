package ai.jarvis.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Secrets at rest, with the Android Keystore as the root of trust.
 *
 * The API key is encrypted with a non-exportable AES-256-GCM key held by the
 * Keystore. Never written to plain SharedPreferences, logs or the Rust side.
 *
 * The transcript store needs raw bytes in Rust, so we use envelope encryption:
 * a random 32-byte data key is encrypted by the Keystore key and stored; Rust
 * receives the decrypted data key once at engine construction. Rotating the
 * Keystore key therefore only costs a re-wrap, not a re-encryption of history.
 */
class SecureStore(
    context: Context,
) {
    private val prefs = context.getSharedPreferences("jarvis_secure", Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    /**
     * Keys are kept per provider, so switching between, say, Groq and OpenAI
     * does not discard the other key. `providerId` comes from the preset list
     * (`custom` for a hand-typed endpoint).
     */
    fun hasApiKey(providerId: String): Boolean = rawApiKey(providerId) != null

    fun apiKey(providerId: String): String = rawApiKey(providerId) ?: ""

    private fun rawApiKey(providerId: String): String? {
        val scoped = prefs.getString(keyName(providerId), null)
        // v0.1.x stored a single key for whichever endpoint was active. Keep it
        // as the BlackPig key so an existing install does not lose it.
        val legacy = if (providerId == LEGACY_PROVIDER_ID) prefs.getString(KEY_API_LEGACY, null) else null
        val blob = scoped ?: legacy ?: return null
        return runCatching { String(decrypt(blob), Charsets.UTF_8) }.getOrNull()
    }

    private fun keyName(providerId: String): String =
        KEY_PREFIX + providerId.lowercase().replace(Regex("[^a-z0-9_-]"), "_")

    /**
     * Stores the key and proves it can be read back.
     *
     * Keystore failures (an entry invalidated by a lock-screen change, a vendor
     * keymaster fault) throw instead of silently dropping the write, so the UI
     * can report what happened. A key that cannot be unwrapped is discarded and
     * re-encrypted under a fresh master key rather than left unusable.
     */
    fun saveApiKey(
        providerId: String,
        value: String,
    ): Result<Unit> =
        runCatching {
            val trimmed = value.trim()
            val name = keyName(providerId)
            if (trimmed.isEmpty()) {
                prefs.edit().remove(name).commit()
                return@runCatching
            }
            val blob =
                try {
                    encrypt(trimmed.toByteArray(Charsets.UTF_8))
                } catch (error: GeneralSecurityException) {
                    Log.w(TAG, "keystore unwrap failed; regenerating master key", error)
                    resetMasterKey()
                    encrypt(trimmed.toByteArray(Charsets.UTF_8))
                }
            check(prefs.edit().putString(name, blob).commit()) { "could not write preferences" }
            val readBack =
                prefs.getString(name, null)?.let { runCatching { decrypt(it) }.getOrNull() }
            check(readBack != null && String(readBack, Charsets.UTF_8) == trimmed) {
                "the stored key could not be read back"
            }
        }

    /** 32-byte AES key for Rust's encrypted transcript/tool-trace log. */
    fun dataKey(): ByteArray {
        val existing = prefs.getString(KEY_DATA, null)
        if (existing != null) {
            return runCatching { decrypt(existing) }.getOrElse { newDataKey() }
        }
        return newDataKey()
    }

    private fun newDataKey(): ByteArray {
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        prefs.edit().putString(KEY_DATA, encrypt(key)).apply()
        return key
    }

    private fun resetMasterKey() {
        runCatching { keyStore.deleteEntry(ALIAS) }
        prefs.edit().remove(KEY_DATA).commit()
    }

    private fun masterKey(): SecretKey {
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec
                .Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, masterKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plain)
        val packed = ByteArray(1 + iv.size + ciphertext.size)
        packed[0] = iv.size.toByte()
        iv.copyInto(packed, 1)
        ciphertext.copyInto(packed, 1 + iv.size)
        return Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): ByteArray {
        val packed = Base64.decode(encoded, Base64.NO_WRAP)
        require(packed.size > 13) { "corrupt secret" }
        val ivSize = packed[0].toInt()
        require(ivSize in 12..16 && packed.size > 1 + ivSize) { "corrupt secret" }
        val iv = packed.copyOfRange(1, 1 + ivSize)
        val ciphertext = packed.copyOfRange(1 + ivSize, packed.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext)
    }

    private companion object {
        const val TAG = "SecureStore"
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "jarvis_master_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_PREFIX = "provider_key_"
        const val KEY_API_LEGACY = "qwen_api_key"
        const val LEGACY_PROVIDER_ID = "blackpig"
        const val KEY_DATA = "store_data_key"
    }
}
