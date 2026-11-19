package com.startupmini.nyachat.data.local

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Penyimpanan rahasia (API keys, PIN) menggunakan Android Keystore langsung.
 * Menggantikan `EncryptedSharedPreferences` (deprecated di security-crypto 1.1.0).
 *
 * - Master key AES-256-GCM dibuat/disimpan di Android Keystore (hardware-backed
 *   jika tersedia).
 * - Data disimpan sebagai `Base64(IV || ciphertext)` di SharedPreferences biasa.
 * - Non-secret prefs (dark mode, role, name, timestamps) pindah ke SharedPreferences
 *   biasa tanpa enkripsi — hanya 3 field (2 API key + PIN) yang terenkripsi.
 *
 * L2: charset UTF-8 dipakai eksplisit (default platform bisa berbeda antar
 * perangkat) dan semua ByteArray antara di-zero di `finally`.
 *
 * PENTING (kompatibilitas): JANGAN pernah mengubah [KEYSTORE_ALIAS] — mengganti
 * alias membuat semua secret lama tak bisa dibaca (master key-nya hidup di
 * Keystore, bukan di SharedPreferences). `setUserAuthenticationRequired(false)` juga
 * dipertahankan apa adanya: mengaktifkannya akan mengubah perilaku unlock &
 * bisa membuat secret lama gagal dibaca di perangkat tanpa biometrik.
 */
object SecureStorage {

    private const val KEYSTORE_ALIAS = "money_chat_master_key"
    private const val PREFS_NAME = "secure_store"
    private const val KEY_PREFIX = "enc_"

    /** Initialize master key di Keystore (idempotent). */
    private fun ensureMasterKey(context: Context): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!keyStore.containsAlias(KEYSTORE_ALIAS)) {
            val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            keyGen.init(
                KeyGenParameterSpec.Builder(
                    KEYSTORE_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setUserAuthenticationRequired(false)
                    .build()
            )
            keyGen.generateKey()
        }
        return keyStore.getKey(KEYSTORE_ALIAS, null) as SecretKey
    }

    private fun getPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Simpan rahasia (dienkripsi dulu).
     * Return true kalau sukses.
     *
     * (audit local/ 2026-08-13) Dipanggil dari IO via [putSecretAsync] —
     * Keystore crypto TIDAK boleh di main thread (jank dengan hardware-backed
     * key). Pemanggil UI WAJIB memakai varian *Async.
     */
    fun putSecret(context: Context, key: String, value: String?): Boolean {
        if (value == null || value.isEmpty()) {
            // Null/empty → hapus
            return deleteSecret(context, key)
        }
        var plaintext: ByteArray? = null
        var ciphertext: ByteArray? = null
        var combined: ByteArray? = null
        return try {
            val masterKey = ensureMasterKey(context)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, masterKey)
            val iv = cipher.iv // 12 bytes untuk GCM
            val plainBytes = value.toByteArray(Charsets.UTF_8)
            plaintext = plainBytes
            val cipherBytes = cipher.doFinal(plainBytes)
            ciphertext = cipherBytes
            // Gabung IV + ciphertext → base64
            val combinedBytes = iv + cipherBytes
            combined = combinedBytes
            val encoded = Base64.encodeToString(combinedBytes, Base64.NO_WRAP)
            getPrefs(context).edit().putString(KEY_PREFIX + key, encoded).apply()
            true
        } catch (e: Exception) {
            android.util.Log.w("SecureStorage", "putSecret gagal: ${e.message}")
            false
        } finally {
            plaintext?.fill(0)
            ciphertext?.fill(0)
            combined?.fill(0)
        }
    }

    /**
     * Baca rahasia (dekripsi).
     * Return null kalau tidak ada / gagal dekripsi.
     * Dipanggil dari IO via [getSecretAsync].
     */
    fun getSecret(context: Context, key: String): String? {
        val encoded = getPrefs(context).getString(KEY_PREFIX + key, null)
            ?: return null
        var combined: ByteArray? = null
        var iv: ByteArray? = null
        var ciphertext: ByteArray? = null
        var plaintext: ByteArray? = null
        return try {
            val masterKey = ensureMasterKey(context)
            val combinedBytes = Base64.decode(encoded, Base64.NO_WRAP)
            combined = combinedBytes
            // IV = 12 byte pertama
            val ivBytes = combinedBytes.copyOfRange(0, 12)
            iv = ivBytes
            val cipherBytes = combinedBytes.copyOfRange(12, combinedBytes.size)
            ciphertext = cipherBytes
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, masterKey, GCMParameterSpec(128, ivBytes))
            val plainBytes = cipher.doFinal(cipherBytes)
            plaintext = plainBytes
            String(plainBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            android.util.Log.w("SecureStorage", "getSecret gagal: ${e.message}")
            null
        } finally {
            combined?.fill(0)
            iv?.fill(0)
            ciphertext?.fill(0)
            plaintext?.fill(0)
        }
    }

    fun deleteSecret(context: Context, key: String): Boolean {
        return try {
            getPrefs(context).edit().remove(KEY_PREFIX + key).apply()
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Wrapper suspend untuk penggunaan di coroutine (IO dispatcher). */
    suspend fun putSecretAsync(context: Context, key: String, value: String?): Boolean =
        withContext(Dispatchers.IO) { putSecret(context, key, value) }

    suspend fun getSecretAsync(context: Context, key: String): String? =
        withContext(Dispatchers.IO) { getSecret(context, key) }

    suspend fun deleteSecretAsync(context: Context, key: String): Boolean =
        withContext(Dispatchers.IO) { deleteSecret(context, key) }
}
