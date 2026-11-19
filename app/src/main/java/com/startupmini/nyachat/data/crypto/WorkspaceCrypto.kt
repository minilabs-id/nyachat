package com.startupmini.nyachat.data.crypto

import com.startupmini.nyachat.data.backup.BackupCrypto
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * r1.7.0 — End-to-End Encryption untuk workspace keluarga.
 *
 * Semua kripto berjalan DI PERANGKAT; server (Firestore/Storage) hanya
 * menyimpan ciphertext yang tidak bisa dibaca tanpa kunci klien.
 *
 * Model kunci (terinspirasi Signal, disederhanakan untuk keluarga):
 *  - Setiap perangkat punya keypair EC P-256 (private key dienkripsi oleh
 *    [com.startupmini.nyachat.data.local.SecureStorage] / Android Keystore;
 *    public key ditulis ke member doc Firestore).
 *  - Workspace punya SATU grup key AES-256. Di-wrap (EciesWrap) ke tiap
 *    perangkat dan disimpan di `families/{PIN}/e2eeKeys/{uid}`. Member baru
 *    mendapat wrap-annya saat disetujui owner (self-heal oleh siapa pun yang
 *    memegang grup key).
 *  - Pesan/transaksi/foto dienkripsi AES-GCM dengan grup key, IV acak per item.
 *
 * Format data (semua Base64 RFC 4648, dipisah titik):
 *  - wrap  : `ephemeralPubB64.ivB64.ctB64` (EciesWrap grup key ke publik recipient)
 *  - enc   : `ivB64.ctB64` (konten pesan / hasil AES-GCM grup key)
 * Base64 memakai [BackupCrypto.encodeBase64]/[decodeBase64] (satu sumber,
 * murni JVM → unit-testable).
 *
 * AAD (L1/L8): semua fungsi enkripsi/dekripsi menerima `aad: ByteArray? = null`.
 * - Data LEGACY ditulis TANPA AAD → format lama (3 bagian wrap / 2 bagian enc)
 *   tetap apa adanya dan SELALU bisa didekripsi dengan `aad = null`.
 * - Data BARU yang memakai AAD mendapat penanda `[AAD_FLAG]` di akhir string
 *   (4 bagian wrap / 3 bagian enc). Dekripsi format baru WAJIB diberi AAD yang
 *   sama persis.
 * - Dekripsi format lama mencoba AAD yang diberikan lebih dulu, lalu jatuh ke
 *   `null` (legacy fallback) — sehingga caller boleh tetap mengirim AAD tanpa
 *   merusak data lama. Untuk blob biner (foto) tidak ada flag (IV acak bisa
 *   saja berisi byte penanda), jadi hanya fallback AAD → null yang dipakai.
 * KUNCI TURUNAN & IV TIDAK PERNAH berubah: AAD hanya masuk lewat `Cipher.updateAAD`.
 */
object WorkspaceCrypto {

    private const val EC_CURVE = "secp256r1"
    private const val KEY_AGREEMENT = "ECDH"
    private const val AES_GCM = "AES/GCM/NoPadding"
    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private const val GROUP_KEY_BYTES = 32
    private const val HKDF_HMAC = "HmacSHA256"
    private const val HKDF_INFO_WRAP = "nyachat:e2ee:wrap:v1"
    private val RANDOM = SecureRandom()

    /** Penanda trailing pada output STRING yang dienkripsi dengan AAD. */
    private const val AAD_FLAG = "a1"
    private const val AAD_CTX_TAG = "nyachat:e2ee:ctx:v1"
    private const val AAD_CONTENT_TAG = "nyachat:e2ee:content:v1"
    private const val AAD_SEPARATOR = "\u001f"

    // ======================= AAD (konteks & konten) =======================

    /** AAD mengikat wrap ke workspace + penerima + epoch kunci. */
    fun contextAad(familyId: String, uid: String, epoch: Long): ByteArray =
        listOf(AAD_CTX_TAG, familyId, uid, epoch.toString())
            .joinToString(AAD_SEPARATOR).toByteArray(Charsets.UTF_8)

    /** AAD mengikat ciphertext pesan/transaksi ke workspace + cloud id + pengirim + versi. */
    fun contentAad(
        familyId: String,
        cloudId: String,
        senderUid: String,
        msgVersion: Int
    ): ByteArray = listOf(AAD_CONTENT_TAG, familyId, cloudId, senderUid, msgVersion.toString())
        .joinToString(AAD_SEPARATOR).toByteArray(Charsets.UTF_8)

    // ======================= Keypair perangkat =======================

    /** Generate keypair EC P-256 (software — private key tak pernah disimpan
     *  sebagai plaintext; pemanggil menyimpannya via SecureStorage). */
    fun generateKeyPair(): KeyPair {
        val gen = KeyPairGenerator.getInstance("EC")
        gen.initialize(ECGenParameterSpec(EC_CURVE))
        return gen.generateKeyPair()
    }

    /** Encode public key (X.509/SPKI) → Base64 utk member doc. */
    fun publicKeyBase64(pub: PublicKey): String = BackupCrypto.encodeBase64(pub.encoded)

    /** Parse Base64 public key kembali (dari member doc). */
    fun parsePublicKey(b64: String): PublicKey =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(BackupCrypto.decodeBase64(b64)))

    // ======================= Grup key workspace =======================

    /** Generate grup key AES-256 (32 byte acak) — dibuat device owner. */
    fun generateGroupKey(): ByteArray = ByteArray(GROUP_KEY_BYTES).also { RANDOM.nextBytes(it) }

    /**
     * EciesWrap: bungkus grup key agar hanya bisa dibuka pemegang private key
     * [recipientPubB64]. Skema: ephemeral EC → ECDH → HKDF-SHA256 → AES-GCM.
     * Output `ephemeralPubB64.ivB64.ctB64` (stateless, tanpa perlu simpan kunci),
     * dengan `.a1` tambahan bila [aad] diberikan (format baru, lihat docs objek).
     */
    fun wrapGroupKey(
        groupKey: ByteArray,
        recipientPubB64: String,
        aad: ByteArray? = null
    ): String {
        val recipientPub = parsePublicKey(recipientPubB64)
        val ephemeral = generateKeyPair()
        var shared: ByteArray? = null
        var wrapKey: ByteArray? = null
        return try {
            val sharedSecret = ecdhSharedSecret(ephemeral.private, recipientPub)
            shared = sharedSecret
            val derived = hkdf(sharedSecret, HKDF_INFO_WRAP)
            wrapKey = derived
            val iv = ByteArray(GCM_IV_BYTES).also { RANDOM.nextBytes(it) }
            val ct = aesGcmEncrypt(derived, iv, groupKey, aad)
            val parts = mutableListOf(
                publicKeyBase64(ephemeral.public),
                BackupCrypto.encodeBase64(iv),
                BackupCrypto.encodeBase64(ct)
            )
            if (aad != null) parts.add(AAD_FLAG)
            parts.joinToString(".")
        } finally {
            shared?.fill(0)
            wrapKey?.fill(0)
        }
    }

    /** Buka EciesWrap dengan private key sendiri → kembalikan grup key.
     *  Format lama didekripsi dengan `null`; format ber-flag memakai [aad].
     *  Lihat [aesGcmDecryptWithFallback] untuk perilaku legacy fallback. */
    fun unwrapGroupKey(
        wrapped: String,
        myPrivateKey: PrivateKey,
        aad: ByteArray? = null
    ): ByteArray {
        val parts = wrapped.split(".")
        val flagged = parts.size == 4 && parts[3] == AAD_FLAG
        require(parts.size == 3 || flagged) { "Format wrap tidak valid" }
        if (parts.size == 4 && !flagged) throw IllegalArgumentException("Format wrap tidak valid")
        val effectiveAad = if (flagged) {
            aad ?: throw IllegalArgumentException("AAD dibutuhkan untuk wrap format baru")
        } else aad
        val ephemeralPub = parsePublicKey(parts[0])
        val iv = BackupCrypto.decodeBase64(parts[1])
        val ct = BackupCrypto.decodeBase64(parts[2])
        var shared: ByteArray? = null
        var wrapKey: ByteArray? = null
        return try {
            val sharedSecret = ecdhSharedSecret(myPrivateKey, ephemeralPub)
            shared = sharedSecret
            val derived = hkdf(sharedSecret, HKDF_INFO_WRAP)
            wrapKey = derived
            aesGcmDecryptWithFallback(derived, iv, ct, effectiveAad)
        } finally {
            shared?.fill(0)
            wrapKey?.fill(0)
        }
    }

    // ======================= Enkripsi konten =======================

    /** Enkripsi konten (JSON pesan / transaksi) → `ivB64.ctB64`
     *  (+ `.a1` bila [aad] diberikan). */
    fun encryptContent(groupKey: ByteArray, plaintext: ByteArray, aad: ByteArray? = null): String {
        val iv = ByteArray(GCM_IV_BYTES).also { RANDOM.nextBytes(it) }
        val ct = aesGcmEncrypt(groupKey, iv, plaintext, aad)
        val parts = mutableListOf(BackupCrypto.encodeBase64(iv), BackupCrypto.encodeBase64(ct))
        if (aad != null) parts.add(AAD_FLAG)
        return parts.joinToString(".")
    }

    /** Dekripsi `ivB64.ctB64` → plaintext (null bila kunci salah/format rusak).
     *  Format lama: coba [aad] dulu lalu fallback `null` (data legacy tetap terbaca). */
    fun decryptContent(groupKey: ByteArray, enc: String, aad: ByteArray? = null): ByteArray? =
        runCatching {
            val parts = enc.split(".")
            val flagged = parts.size == 3 && parts[2] == AAD_FLAG
            require(parts.size == 2 || flagged) { "Format enc tidak valid" }
            if (parts.size == 3 && !flagged) throw IllegalArgumentException("Format enc tidak valid")
            val effectiveAad = if (flagged) {
                aad ?: throw IllegalArgumentException("AAD dibutuhkan untuk konten format baru")
            } else aad
            aesGcmDecryptWithFallback(
                groupKey,
                BackupCrypto.decodeBase64(parts[0]),
                BackupCrypto.decodeBase64(parts[1]),
                effectiveAad
            )
        }.getOrNull()

    /** Enkripsi bytes (foto) → `iv(12) || ciphertext` — dipakai blob Storage.
     *  Format biner sengaja TIDAK diberi flag (IV acak bisa menyerupai penanda). */
    fun encryptBytes(groupKey: ByteArray, data: ByteArray, aad: ByteArray? = null): ByteArray {
        val iv = ByteArray(GCM_IV_BYTES).also { RANDOM.nextBytes(it) }
        return iv + aesGcmEncrypt(groupKey, iv, data, aad)
    }

    /** Dekripsi `iv(12) || ciphertext` — null bila kunci salah.
     *  [aad] dicoba dulu lalu fallback `null` → blob lama tanpa AAD tetap terbaca. */
    fun decryptBytes(groupKey: ByteArray, data: ByteArray, aad: ByteArray? = null): ByteArray? =
        runCatching {
            require(data.size > GCM_IV_BYTES) { "Ciphertext terlalu pendek" }
            val iv = data.copyOfRange(0, GCM_IV_BYTES)
            val ct = data.copyOfRange(GCM_IV_BYTES, data.size)
            aesGcmDecryptWithFallback(groupKey, iv, ct, aad)
        }.getOrNull()

    // ======================= Primitif =======================

    private fun ecdhSharedSecret(privateKey: PrivateKey, publicKey: PublicKey): ByteArray {
        val ka = KeyAgreement.getInstance(KEY_AGREEMENT)
        ka.init(privateKey)
        ka.doPhase(publicKey, true)
        return ka.generateSecret()
    }

    /** HKDF-SHA256 (RFC 5869) — info membedakan turunan kunci.
     *  UTF-8 dipakai eksplisit untuk [info] (ASCII konstan → byte identik,
     *  sehingga key derivation tidak berubah). Semua material antara di-zero. */
    private fun hkdf(ikm: ByteArray, info: String): ByteArray {
        val extract = Mac.getInstance(HKDF_HMAC)
        extract.init(SecretKeySpec(ByteArray(32), "HmacSHA256"))
        var prk: ByteArray? = null
        var input: ByteArray? = null
        var expanded: ByteArray? = null
        return try {
            // Extract: PRK = HMAC(salt=zeros, ikm)
            val prkBytes = extract.doFinal(ikm)
            prk = prkBytes
            // Expand: T(1) = HMAC(PRK, info || 0x01) — cukup 1 blok (32 byte)
            val inputBytes = info.toByteArray(Charsets.UTF_8) + byteArrayOf(1)
            input = inputBytes
            val expand = Mac.getInstance(HKDF_HMAC)
            expand.init(SecretKeySpec(prkBytes, "HmacSHA256"))
            val expandedBytes = expand.doFinal(inputBytes)
            expanded = expandedBytes
            expandedBytes.copyOf(GROUP_KEY_BYTES)
        } finally {
            prk?.fill(0)
            input?.fill(0)
            expanded?.fill(0)
        }
    }

    private fun aesGcmEncrypt(
        key: ByteArray,
        iv: ByteArray,
        plain: ByteArray,
        aad: ByteArray? = null
    ): ByteArray {
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        if (aad != null) cipher.updateAAD(aad)
        return cipher.doFinal(plain)
    }

    private fun aesGcmDecrypt(
        key: ByteArray,
        iv: ByteArray,
        ct: ByteArray,
        aad: ByteArray? = null
    ): ByteArray {
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        if (aad != null) cipher.updateAAD(aad)
        return cipher.doFinal(ct)
    }

    /** Coba dekripsi dengan AAD dulu; kalau gagal & AAD diberikan, ulangi dengan
     *  `null` untuk kompatibilitas data LEGACY (yang dibuat tanpa AAD). */
    private fun aesGcmDecryptWithFallback(
        key: ByteArray,
        iv: ByteArray,
        ct: ByteArray,
        aad: ByteArray?
    ): ByteArray {
        if (aad != null) {
            val withAad = runCatching { aesGcmDecrypt(key, iv, ct, aad) }.getOrNull()
            if (withAad != null) return withAad
        }
        return aesGcmDecrypt(key, iv, ct, null)
    }

    /** SHA-256 (dipakai deterministik untuk verifikasi/menandai kunci). */
    fun sha256(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
}
