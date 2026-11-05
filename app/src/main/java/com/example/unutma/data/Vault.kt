package com.example.unutma.data

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

/**
 * Encryption for locked notes.
 *
 * Each locked note's text is encrypted with its own random AES key. That key is stored next to it,
 * wrapped with an RSA key kept in the Android Keystore. Wrapping uses the public half, so locking a
 * note needs no fingerprint; unwrapping needs the private half, which Android only allows for 30
 * seconds after you unlock with your fingerprint or screen lock — exactly what opening a locked note
 * already asks for.
 *
 * Stored body: {"enc":1,"k":wrapped key,"iv":…,"c":ciphertext,"m":[picture/voice file paths]}.
 * The file paths stay readable so the tidy-up never deletes a locked note's pictures; the title also
 * stays readable (it's shown in the list and used by search).
 *
 * If the phone's screen lock is removed, Android deletes the keystore key and locked notes can no
 * longer be opened — the Help page and the lock switch say so.
 */
object Vault {
    private const val ALIAS = "unutma_locked_notes"
    private const val RSA = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"
    private val oaep = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT)

    fun isSealed(description: String) = description.trimStart().startsWith("{\"enc\"")

    /** Encryption works only with a screen lock (fingerprint, PIN, pattern or password) set. */
    fun available(ctx: Context): Boolean =
        ctx.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true && ensureKey()

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun ensureKey(): Boolean = try {
        if (!keyStore().containsAlias(ALIAS)) {
            val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(2048)
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA1)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                .setUserAuthenticationRequired(true)
                .setInvalidatedByBiometricEnrollment(false) // adding a fingerprint mustn't lock you out
                .apply {
                    if (Build.VERSION.SDK_INT >= 30) {
                        setUserAuthenticationParameters(30, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
                    } else {
                        @Suppress("DEPRECATION") setUserAuthenticationValidityDurationSeconds(30)
                    }
                }
                .build()
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply { initialize(spec) }.generateKeyPair()
        }
        true
    } catch (_: Exception) { false }

    /** A fresh random key for a note. */
    fun newKey(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }

    /** Encrypts [plain] with [key] (no fingerprint needed). */
    fun seal(plain: String, mediaPaths: List<String>, key: ByteArray): String {
        // The public key is used outside the keystore, which is what lets this work without unlocking.
        val pub = keyStore().getCertificate(ALIAS).publicKey
        val publicKey = KeyFactory.getInstance(pub.algorithm).generatePublic(X509EncodedKeySpec(pub.encoded))
        val wrap = Cipher.getInstance(RSA).apply { init(Cipher.ENCRYPT_MODE, publicKey, oaep) }.doFinal(key)
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val aes = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        }
        val c = aes.doFinal(plain.toByteArray(Charsets.UTF_8))
        return JSONObject()
            .put("enc", 1)
            .put("k", b64(wrap)).put("iv", b64(iv)).put("c", b64(c))
            .put("m", JSONArray(mediaPaths))
            .toString()
    }

    /**
     * Decrypts a sealed body. Needs a fingerprint/screen-lock unlock in the last 30 seconds.
     * Returns the plain body and the note's key (kept to re-encrypt while you edit), or null.
     */
    fun open(sealed: String): Pair<String, ByteArray>? = try {
        val o = JSONObject(sealed)
        val priv = keyStore().getKey(ALIAS, null) as PrivateKey
        val key = Cipher.getInstance(RSA).apply { init(Cipher.DECRYPT_MODE, priv, oaep) }.doFinal(unb64(o.getString("k")))
        val aes = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, unb64(o.getString("iv"))))
        }
        String(aes.doFinal(unb64(o.getString("c"))), Charsets.UTF_8) to key
    } catch (_: Exception) { null }

    /** Picture and voice file paths kept beside the encrypted text. */
    fun mediaPaths(sealed: String): List<String> = try {
        val a = JSONObject(sealed).optJSONArray("m") ?: JSONArray()
        (0 until a.length()).map { a.getString(it) }
    } catch (_: Exception) { emptyList() }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)
}
