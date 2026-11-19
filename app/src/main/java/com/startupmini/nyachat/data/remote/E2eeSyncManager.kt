package com.startupmini.nyachat.data.remote

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.startupmini.nyachat.Constants
import com.startupmini.nyachat.data.backup.BackupCrypto
import com.startupmini.nyachat.data.crypto.WorkspaceCrypto
import com.startupmini.nyachat.data.local.SecureStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.concurrent.atomic.AtomicBoolean

/**
 * r1.7.0 — Siklus hidup kunci E2EE per workspace.
 *
 * Model (terinspirasi Signal, disederhanakan untuk keluarga):
 *  - Setiap perangkat punya keypair EC P-256 (private key di SecureStorage /
 *    Android Keystore; public key ditulis ke member doc Firestore).
 *  - Workspace punya SATU grup key AES-256 (dibuat device OWNER saat marker
 *    `families/{PIN}/e2ee/e2ee` pertama kali dibuat). Grup key di-wrap
 *    (EciesWrap) ke tiap perangkat & disimpan di `families/{PIN}/e2eeKeys/{uid}`.
 *  - Pemilik kunci = siapapun yang punya grup key → bisa meng-wrap untuk member
 *    baru yang sudah mempublikasikan public key-nya (self-heal berkala).
 *
 * Alur aktivasi (migrasi deterministic):
 *  1. Owner membuka app → keypair dibuat, public key di-sync ke member doc.
 *  2. Owner melihat marker belum ada → klaim marker dulu via `create()`, BARU
 *     generate grup key + wrap untuk semua member (M1: atomic, tidak ada kunci
 *     lokal yang terbuang kalau marker ternyata sudah dibuat owner lain).
 *  3. Member membuka app → keypair + public key di-sync → marker sudah ada →
 *     ambil wrap-nya sendiri (`e2eeKeys/{uid}`) → unwrap dengan private key.
 *  4. Setelah ini, FirestoreSyncManager mengenkripsi semua tulis baru
 *     (messages & transactions) dan mendekripsi saat merge (Room tetap polos).
 *
 * Pemulihan perangkat (r1.7.1): wrap `e2eeKeys/{uid}` membawa `e2eeKeyVersion`
 * (versi pubkey saat di-wrap). Self-heal me-REWRAP setiap kali versi pubkey
 * member lebih baru dari versi di wrap — menutup kasus reinstall/perangkat
 * baru. Bila unwrap gagal (H1: wrap corrupt/tampered), keypair LOKAL diputar
 * paksa (pubkey baru + versi naik) satu kali per sesi → self-heal rewrap.
 *
 * Rotasi (H2): [rotateGroupKey] mengganti grup key, menaikkan `keyEpoch` marker,
 * dan me-wrap ulang ke semua member. **Panggil setelah member dihapus/dikeluarkan**
 * agar mantan member tidak bisa mendekripsi pesan baru.
 *
 * Tidak ada kunci = pesan lama plaintext tetap terbaca; hanya pesan BARU
 * setelah aktivasi yang terenkripsi & ephemeral (lihat Constants.MsgVersion).
 *
 * BATASAN (dokumentasi): SATU slot wrap per member → hanya perangkat terbaru
 * yang bisa re-fetch wrap (perangkat lain memakai grup key dari cache lokal).
 * Kasus fatal: SEMUA perangkat ter-wipe → grup key hilang permanen (butuh
 * fitur rotate key — sekarang tersedia via [rotateGroupKey]).
 */
object E2eeSyncManager {

    private const val TAG = "E2eeSync"
    private const val MARKER_DOC = "e2ee"
    /** Jeda self-heal: cek member baru/public key baru untuk di-wrap. */
    private const val HEAL_INTERVAL_MS = 60_000L

    /** Field marker/watch doc (lokal di file ini — Constants sengaja tidak diubah). */
    private const val KEY_EPOCH_FIELD = "keyEpoch"
    private const val MARKER_OWNER_FIELD = "ownerUid"
    private const val AWAIT_TIMEOUT_MS = 5_000L
    private const val AWAIT_POLL_MS = 100L

    @Volatile private var familyId: String = ""
    @Volatile private var appContext: Context? = null
    @Volatile private var activeRole: String = Constants.Roles.MEMBER
    @Volatile private var markerActive = false
    @Volatile private var groupKeyBytes: ByteArray? = null
    @Volatile private var markerListener: ListenerRegistration? = null
    @Volatile private var keyEpochCache = 0L

    /** H1: rotasi paksa keypair hanya sekali per sesi (anti-loop self-heal). */
    private val forcedRotationDone = AtomicBoolean(false)
    /** M1: CAS mencegah dua aktivasi bersamaan dalam satu proses. */
    private val activationInProgress = AtomicBoolean(false)

    /** M9: status aktivasi yang bisa ditanyakan UI sebelum mengirim pesan. */
    enum class ActivationState { UNKNOWN, ACTIVE, INACTIVE }

    @Volatile var activationState: ActivationState = ActivationState.UNKNOWN
        private set

    /** H2: epoch marker terakhir yang sudah diproses perangkat ini. Dipakai
     *  listener marker untuk mendeteksi rotasi grup key oleh owner. */
    @Volatile private var lastSeenEpoch: Long = -1L

    /** r1.7.1: status E2EE untuk UI (banner "mempersiapkan enkripsi" & gating
     *  kirim). Dipublikasikan tiap perubahan marker/kunci (start/heal/stop). */
    data class E2eeStatus(val active: Boolean = false, val ready: Boolean = false)
    private val _status = MutableStateFlow(E2eeStatus())
    val status: StateFlow<E2eeStatus> = _status.asStateFlow()

    @Volatile private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ======================= Status publik =======================

    /** Grup key workspace aktif (null sebelum aktivasi / belum bisa di-unwrap). */
    fun currentGroupKey(): ByteArray? = groupKeyBytes

    /** Marker E2EE sudah ada di server (workspace sudah "terenkripsi"). */
    fun isActive(): Boolean = when (activationState) {
        ActivationState.ACTIVE -> true
        ActivationState.INACTIVE -> false
        ActivationState.UNKNOWN -> markerActive // fallback saat status belum selesai di-resolve
    }

    /** Siap enkripsi/dekripsi: marker aktif DAN grup key tersedia lokal. */
    fun isReady(): Boolean = markerActive && groupKeyBytes != null

    /** Epoch kunci saat ini (naik setiap [rotateGroupKey]). */
    fun currentKeyEpoch(): Long = keyEpochCache

    /**
     * M9: tunggu sebentar (maks ~5 detik, polling 100ms) sampai pembacaan marker
     * selesai. Dipakai UI untuk gating kirim saat app baru dibuka.
     */
    suspend fun awaitActivationState(): ActivationState {
        if (familyId.isEmpty()) return activationState
        var waited = 0L
        while (activationState == ActivationState.UNKNOWN && waited < AWAIT_TIMEOUT_MS) {
            delay(AWAIT_POLL_MS)
            waited += AWAIT_POLL_MS
            runCatching {
                val snap = markerRef().get().await()
                if (snap.exists()) {
                    markerActive = true
                    activationState = ActivationState.ACTIVE
                    keyEpochCache = snap.getLong(KEY_EPOCH_FIELD) ?: 0L
                } else if (activeRole != Constants.Roles.OWNER) {
                    activationState = ActivationState.INACTIVE
                }
            }.onFailure { Log.w(TAG, "awaitActivationState gagal: ${it.message}") }
        }
        return activationState
    }

    // ======================= Start / stop =======================

    /** Mulai siklus kunci untuk workspace [pin]. Dipanggil SyncLifecycle. */
    suspend fun start(context: Context, pin: String, role: String) {
        stop()
        familyId = pin
        activeRole = role
        appContext = context.applicationContext
        activationState = ActivationState.UNKNOWN
        lastSeenEpoch = -1L
        runCatching {
            // 1) Keypair perangkat (dibuat bila belum ada) & sync public key.
            val pub = ensureKeyPair()
            syncPubKeyToMemberDoc(pub)
            // 2) Grup key lokal (kalau sudah pernah disimpan).
            loadGroupKey()
            // 3) Marker: aktifkan bila OWNER; kalau sudah aktif → ambil kunci.
            ensureActivation()
            if (markerActive && groupKeyBytes == null) fetchAndUnwrapGroupKey()
            // 4) Wrap untuk member yang belum punya (termasuk diri sendiri bila baru).
            selfHealWraps()
            notifyReady()
            publishStatus()
            // 5) Pemantau realtime + self-heal berkala.
            listenForActivation()
            startPeriodicHeal()
        }.onFailure { Log.w(TAG, "E2EE start gagal: ${it.message}") }
    }

    /** Hentikan semua aktivitas E2EE (saat logout / ganti workspace). */
    fun stop() {
        markerListener?.remove(); markerListener = null
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        familyId = ""
        activeRole = Constants.Roles.MEMBER
        appContext = null
        markerActive = false
        // L8: hapus jejak grup key dari memori, bukan cuma null-kan referensi.
        groupKeyBytes?.fill(0)
        groupKeyBytes = null
        keyEpochCache = 0L
        activationState = ActivationState.UNKNOWN
        forcedRotationDone.set(false)
        activationInProgress.set(false)
        _status.value = E2eeStatus()
    }

    /**
     * Hapus kunci E2EE lokal (keypair + grup key cache) milik workspace [pin].
     * Dipanggil saat keluar/di-kick/ganti workspace agar perangkat tidak menyimpan
     * material kunci workspace yang sudah tidak diikuti — mencegah pemakaian ulang
     * cache lama dan mengurangi jejak rahasia di perangkat. Aman dipanggil kapan pun
     * (idempoten; tidak bergantung pada state aktif).
     */
    fun clearLocalKeys(context: Context, pin: String) {
        if (pin.isBlank()) return
        // L8: zero-kan dulu grup key yang masih di-cache.
        groupKeyBytes?.fill(0)
        groupKeyBytes = null
        secretKeyNamesFor(pin).forEach { SecureStorage.deleteSecret(context, it) }
        _status.value = E2eeStatus(active = markerActive, ready = false)
    }

    internal fun safePinFor(pin: String): String = pin.replace(Regex("[^A-Za-z0-9_-]"), "_")

    internal fun secretKeyNamesFor(pin: String): List<String> {
        val safePin = safePinFor(pin)
        return listOf("e2ee_priv_$safePin", "e2ee_pub_$safePin", "e2ee_group_$safePin")
    }

    /**
     * Self-heal sekali jalan (dipanggil dari start & marker listener): pastikan
     * setiap member yang sudah punya public key punya wrap grup key, dan jika
     * grup key belum ada tapi marker sudah aktif → ambil dari wrap sendiri.
     *
     * [healWithContextAad] menulis wrap baru dengan [WorkspaceCrypto.contextAad]
     * (format ber-flag); [heal()] memakai wrap legacy tanpa AAD → perangkat
     * versi lama tetap bisa membuka.
     */
    suspend fun heal() = healInternal(null, null)

    /** Varian self-heal yang menulis wrap dengan AAD konteks (L1).
     *  [heal()] tetap memakai format legacy untuk kompatibilitas. */
    suspend fun healWithContextAad(aadFamilyId: String, aadEpoch: Long) =
        healInternal(aadFamilyId, aadEpoch)

    private suspend fun healInternal(aadFamilyId: String?, aadEpoch: Long?) {
        if (familyId.isEmpty()) return
        runCatching {
            val uid = uid() ?: return@runCatching
            ensureActivation()
            if (markerActive && groupKeyBytes == null) fetchAndUnwrapGroupKey()
            selfHealWraps(aadFamilyId, aadEpoch)
            notifyReady()
            publishStatus()
        }.onFailure { Log.w(TAG, "E2EE heal gagal: ${it.message}") }
    }

    /** Wrap grup key untuk member baru (dipanggil MembershipManager.approveJoin).
     *  Parameter AAD opsional — default null menjaga kompatibilitas wrap lama. */
    suspend fun wrapForNewMember(
        uid: String,
        aadFamilyId: String? = null,
        aadEpoch: Long? = null
    ) {
        if (familyId.isEmpty() || !markerActive || groupKeyBytes == null) return
        runCatching {
            val memberDoc = membersRef().document(uid).get().await()
            val pub = memberDoc.getString(Constants.Fields.E2EE_PUB_KEY) ?: return
            val memberVersion = memberDoc.getLong(Constants.Fields.E2EE_KEY_VERSION)
            val wrapSnap = e2eeKeysRef().document(uid).get().await()
            if (wrapSnap.exists() && !needsRewrap(
                    wrapSnap.getLong(Constants.Fields.E2EE_KEY_VERSION), memberVersion
                )
            ) return
            writeWrap(uid, pub, memberVersion ?: 0L, aadFamilyId, aadEpoch)
        }.onFailure { Log.w(TAG, "wrapForNewMember gagal: ${it.message}") }
    }

    /** L1: enkripsi konten dengan AAD bila keempat ID diberikan; otherwise legacy
     *  (aad = null) supaya ciphertext lama & caller lama tetap kompatibel. */
    fun encryptContentForSync(
        groupKey: ByteArray,
        plaintext: ByteArray,
        aadFamilyId: String? = null,
        aadCloudId: String? = null,
        aadSenderUid: String? = null,
        aadMsgVersion: Int? = null
    ): String = WorkspaceCrypto.encryptContent(groupKey, plaintext, contentAadOrNull(
        aadFamilyId, aadCloudId, aadSenderUid, aadMsgVersion
    ))

    /** Pasangan dekripsi dari [encryptContentForSync] (legacy fallback otomatis). */
    fun decryptContentForSync(
        groupKey: ByteArray,
        enc: String,
        aadFamilyId: String? = null,
        aadCloudId: String? = null,
        aadSenderUid: String? = null,
        aadMsgVersion: Int? = null
    ): ByteArray? = WorkspaceCrypto.decryptContent(groupKey, enc, contentAadOrNull(
        aadFamilyId, aadCloudId, aadSenderUid, aadMsgVersion
    ))

    private fun contentAadOrNull(
        familyId: String?,
        cloudId: String?,
        senderUid: String?,
        msgVersion: Int?
    ): ByteArray? = if (familyId != null && cloudId != null && senderUid != null) {
        WorkspaceCrypto.contentAad(familyId, cloudId, senderUid, msgVersion ?: 0)
    } else null

    // ======================= Aktivasi & pengambilan kunci =======================

    /** Kalau marker sudah ada di server (dibuat owner) → tandai aktif. Semua
     *  peran membaca marker (tidak hanya owner) supaya keputusan "workspace
     *  terenkripsi" segera akurat — menutup race di start: member tidak boleh
     *  mengirim plaintext hanya karena marker listener belum menembak.
     *
     *  M1: kalau marker ADA (milik siapa pun), cukup muat kunci lokal — JANGAN
     *  pernah generate grup key baru di sini. */
    private suspend fun ensureActivation() {
        if (markerActive) return
        val snap = markerRef().get().await()
        if (snap.exists()) {
            markerActive = true
            activationState = ActivationState.ACTIVE
            keyEpochCache = snap.getLong(KEY_EPOCH_FIELD) ?: 0L
            lastSeenEpoch = keyEpochCache
            loadGroupKey()
            return
        }
        if (activeRole != Constants.Roles.OWNER) {
            activationState = ActivationState.INACTIVE
            return
        }
        activateNow()
    }

    /** OWNER mengklaim marker dulu dengan `create()` (atomik). Hanya setelah
     *  klaim BERHASIL grup key dibuat/disimpan. Kalau create gagal karena marker
     *  sudah ada (owner lain / race), marker dibaca ulang & kunci lokal dipakai. */
    private suspend fun activateNow() {
        if (!activationInProgress.compareAndSet(false, true)) {
            // Aktivasi lain di proses ini sedang berjalan — sinkronkan dari marker.
            runCatching {
                val snap = markerRef().get().await()
                if (snap.exists()) {
                    markerActive = true
                    activationState = ActivationState.ACTIVE
                    keyEpochCache = snap.getLong(KEY_EPOCH_FIELD) ?: 0L
                    loadGroupKey()
                }
            }
            return
        }
        try {
            val owner = uid() ?: return
            val marker = hashMapOf<String, Any>(
                Constants.Fields.E2EE_ACTIVATED to FieldValue.serverTimestamp(),
                MARKER_OWNER_FIELD to owner,
                KEY_EPOCH_FIELD to 0L
            )
            val claimed = runCatching { markerRef().create(marker).await() }.isSuccess
            if (!claimed) {
                // Baca ULANG marker setelah create gagal — jangan asumsikan kita owner.
                val snap = runCatching { markerRef().get().await() }.getOrNull()
                if (snap != null && snap.exists()) {
                    markerActive = true
                    activationState = ActivationState.ACTIVE
                    keyEpochCache = snap.getLong(KEY_EPOCH_FIELD) ?: 0L
                    loadGroupKey() // marker milik pihak lain → pakai kunci yang sudah ada
                } // baca gagal (offline) → biarkan UNKNOWN, dicoba lagi self-heal berikutnya
                return
            }
            // Claim sukses → BARU sekarang buat & simpan grup key workspace.
            val groupKey = WorkspaceCrypto.generateGroupKey()
            storeGroupKey(groupKey)
            groupKey.fill(0)
            markerActive = true
            activationState = ActivationState.ACTIVE
            keyEpochCache = 0L
            Log.d(TAG, "E2EE diaktifkan untuk workspace (marker dibuat owner)")
        } finally {
            activationInProgress.set(false)
        }
    }

    /** Ambil wrap grup key milik perangkat ini (`e2eeKeys/{uid}`) lalu unwrap. */
    private suspend fun fetchAndUnwrapGroupKey() {
        val myUid = uid() ?: return
        val wrapSnap = runCatching { e2eeKeysRef().document(myUid).get().await() }.getOrNull()
            ?: return // network error → retry self-heal berikutnya
        val wrapped = wrapSnap.getString(Constants.Fields.E2EE_KEY_BYTES)
        val priv = loadPrivateKey()
        // AAD untuk wrap format baru diambil dari epoch yang TERSIMPAN DI WRAP
        // (bukan marker) → tidak ada race saat owner menaikkan epoch saat rotasi.
        val wrapEpoch = wrapSnap.getLong(KEY_EPOCH_FIELD)
        val aad = wrapEpoch?.let { WorkspaceCrypto.contextAad(familyId, myUid, it) }
        val key = if (wrapped != null && priv != null) {
            runCatching { WorkspaceCrypto.unwrapGroupKey(wrapped, priv, aad) }.getOrNull()
        } else null
        if (key == null) {
            // H1: wrap tidak bisa dibuka (corrupt/tampered/keypair lama). Putar
            // paksa keypair LOKAL sekali per sesi → pubkey baru + versi naik →
            // self-heal device lain me-REWRAP. Guard mencegah loop tak berujung.
            Log.w(TAG, "Grup key gagal dibuka — rotasi paksa keypair untuk memicu rewrap")
            if (forcedRotationDone.compareAndSet(false, true)) forceKeyPairRotation()
            return
        }
        storeGroupKey(key)
        key.fill(0)
        Log.d(TAG, "Grup key workspace diterima & dibuka")
    }

    /**
     * H1: hasilkan keypair BARU, simpan di SecureStorage, publikasikan pubkey
     * dengan `e2eeKeyVersion = versi lama + 1` ke member doc, hapus wrap stale,
     * lalu (bila grup key tersedia) segera tulis ulang wrap sendiri.
     * Dipanggil otomatis oleh [fetchAndUnwrapGroupKey] pada kegagalan unwrap,
     * atau bisa dipanggil manual oleh caller yang mendeteksi kompromi kunci.
     * @return public key baru (Base64), atau "" bila konteks tidak tersedia.
     */
    suspend fun forceKeyPairRotation(): String {
        val ctx = appContext ?: return ""
        val kp = WorkspaceCrypto.generateKeyPair()
        val newPub = WorkspaceCrypto.publicKeyBase64(kp.public)
        val privEncoded = kp.private.encoded
        try {
            SecureStorage.putSecretAsync(ctx, privKeyKey(), BackupCrypto.encodeBase64(privEncoded))
        } finally {
            privEncoded.fill(0)
        }
        SecureStorage.putSecretAsync(ctx, pubKeyKey(), newPub)
        val myUid = uid() ?: return newPub
        // Rules kini mengizinkan owner/self menghapus wrap miliknya sendiri.
        runCatching { e2eeKeysRef().document(myUid).delete().await() }
            .onFailure { Log.w(TAG, "Gagal hapus wrap stale: ${it.message}") }
        // force = true → lewati early-return "pubkey sama" yang dulu membuat
        // DoS permanen; increment(1) = versi lama + 1 secara atomik.
        syncPubKeyToMemberDoc(newPub, force = true)
        groupKeyBytes?.let { gk ->
            val memberSnap = runCatching { membersRef().document(myUid).get().await() }.getOrNull()
            val version = memberSnap?.getLong(Constants.Fields.E2EE_KEY_VERSION) ?: 0L
            runCatching {
                e2eeKeysRef().document(myUid).set(mapOf(
                    Constants.Fields.E2EE_KEY_BYTES to WorkspaceCrypto.wrapGroupKey(gk, newPub),
                    Constants.Fields.E2EE_KEY_VERSION to version
                )).await()
            }.onFailure { Log.w(TAG, "Gagal tulis ulang wrap sendiri: ${it.message}") }
        }
        return newPub
    }

    /**
     * H2: rotasi grup key workspace — OWNER saja. Grup key baru dibuat,
     * `keyEpoch` marker dinaikkan (dibaca dulu), lalu wrap baru ditulis ke
     * semua member yang punya public key dengan `e2eeKeyVersion` yang cocok.
     *
     * CATATAN UNTUK CALLER: panggil ini setelah member dihapus/dikeluarkan
     * (member removal) agar mantan member tidak bisa membaca pesan baru.
     *
     * @return true bila rotasi berhasil, false bila bukan owner / gagal.
     */
    suspend fun rotateGroupKey(): Boolean {
        if (activeRole != Constants.Roles.OWNER || familyId.isEmpty()) return false
        return runCatching {
            val snap = markerRef().get().await()
            if (!snap.exists()) return@runCatching false
            val newEpoch = (snap.getLong(KEY_EPOCH_FIELD) ?: 0L) + 1L
            val newKey = WorkspaceCrypto.generateGroupKey()
            val docs = membersRef().get().await()
            // Audit H2 (race): TULIS SEMUA WRAP DULU, baru naikkan keyEpoch.
            // Kalau epoch dinaikkan lebih dulu, listener perangkat lain bisa
            // membaca wrap lama & menyimpan grup key lama — rotasi gagal total.
            for (doc in docs.documents) {
                val pub = doc.getString(Constants.Fields.E2EE_PUB_KEY) ?: continue
                val version = doc.getLong(Constants.Fields.E2EE_KEY_VERSION) ?: 0L
                // Format legacy sengaja dipertahankan → perangkat lama tetap
                // bisa unwrap. AAD (bila diinginkan) lewat selfHealWraps/
                // wrapForNewMember dengan `aadFamilyId`, bukan di sini.
                val wrapped = WorkspaceCrypto.wrapGroupKey(newKey, pub)
                e2eeKeysRef().document(doc.id).set(mapOf(
                    Constants.Fields.E2EE_KEY_BYTES to wrapped,
                    Constants.Fields.E2EE_KEY_VERSION to version
                )).await()
            }
            markerRef().update(mapOf(KEY_EPOCH_FIELD to newEpoch)).await()
            keyEpochCache = newEpoch
            storeGroupKey(newKey)
            newKey.fill(0)
            notifyReady()
            publishStatus()
            Log.d(TAG, "Grup key dirotasi ke epoch $newEpoch")
            true
        }.getOrElse {
            Log.w(TAG, "Rotasi grup key gagal: ${it.message}")
            false
        }
    }

    /**
     * Pastikan SEMUA member yang sudah punya public key memiliki wrap grup key
     * yang sesuai dengan pubkey-nya. Re-wrap bila: wrap belum ada, ATAU versi
     * pubkey member lebih baru dari versi yang tersimpan di wrap (reinstall /
     * perangkat baru → pubkey berubah). Idempoten.
     */
    private suspend fun selfHealWraps(aadFamilyId: String? = null, aadEpoch: Long? = null) {
        if (groupKeyBytes == null || familyId.isEmpty()) return
        val myUid = uid() ?: return
        val docs = membersRef().get().await()
        val canWrapOtherMembers = activeRole == Constants.Roles.OWNER
        for (doc in docs.documents) {
            val pub = doc.getString(Constants.Fields.E2EE_PUB_KEY) ?: continue
            val memberUid = doc.id
            // Members may maintain their own wrap. Cross-member wraps are reserved
            // for owners, matching the Firestore authorization rule.
            if (memberUid != myUid && !canWrapOtherMembers) continue
            val memberVersion = doc.getLong(Constants.Fields.E2EE_KEY_VERSION)
            val wrapRef = e2eeKeysRef().document(memberUid)
            val wrapSnap = wrapRef.get().await()
            if (wrapSnap.exists() && !needsRewrap(
                    wrapSnap.getLong(Constants.Fields.E2EE_KEY_VERSION), memberVersion
                )
            ) continue
            writeWrap(memberUid, pub, memberVersion ?: 0L, aadFamilyId, aadEpoch)
        }
    }

    /** Satu tempat menulis wrap (dipakai self-heal & wrapForNewMember). */
    private suspend fun writeWrap(
        memberUid: String,
        pub: String,
        version: Long,
        aadFamilyId: String?,
        aadEpoch: Long?
    ) {
        val groupKey = groupKeyBytes ?: return
        val aad = aadFamilyId?.let { WorkspaceCrypto.contextAad(it, memberUid, aadEpoch ?: 0L) }
        val wrapped = runCatching { WorkspaceCrypto.wrapGroupKey(groupKey, pub, aad) }.getOrNull()
            ?: return
        val data = mutableMapOf<String, Any>(
            Constants.Fields.E2EE_KEY_BYTES to wrapped,
            Constants.Fields.E2EE_KEY_VERSION to version
        )
        // Epoch ikut ditulis supaya sisi unwrap bisa membangun AAD yang sama
        // tanpa bergantung pada marker (menghindari race saat rotasi).
        if (aad != null) data[KEY_EPOCH_FIELD] = aadEpoch ?: 0L
        e2eeKeysRef().document(memberUid).set(data).await()
    }

    /** true bila wrap perlu ditulis ulang: versi pubkey di wrap != versi pubkey
     *  member saat ini (wrap legacy tanpa versi dianggap 0 → di-rewrap). */
    internal fun needsRewrap(wrapVersion: Long?, memberVersion: Long?): Boolean =
        (wrapVersion ?: 0L) != (memberVersion ?: 0L)

    private suspend fun ensureKeyPair(): String {
        val ctx = appContext ?: return ""
        val existingPub = SecureStorage.getSecretAsync(ctx, pubKeyKey())
        if (!existingPub.isNullOrBlank()) return existingPub
        val kp = WorkspaceCrypto.generateKeyPair()
        val pub = WorkspaceCrypto.publicKeyBase64(kp.public)
        val privEncoded = kp.private.encoded
        try {
            SecureStorage.putSecretAsync(ctx, privKeyKey(), BackupCrypto.encodeBase64(privEncoded))
        } finally {
            privEncoded.fill(0)
        }
        SecureStorage.putSecretAsync(ctx, pubKeyKey(), pub)
        return pub
    }

    private suspend fun syncPubKeyToMemberDoc(pub: String, force: Boolean = false) {
        val uid = uid() ?: return
        val memberRef = membersRef().document(uid)
        val snap = memberRef.get().await()
        if (!snap.exists()) return // belum jadi anggota (masih pending) — tunggu
        // H1: tanpa `force`, early-return ini membuat pubkey rusak permanen
        // (wrap tidak bisa dibuka tapi pubkey di doc "sudah benar").
        if (!force && snap.getString(Constants.Fields.E2EE_PUB_KEY) == pub) return
        memberRef.update(
            mapOf(
                Constants.Fields.E2EE_PUB_KEY to pub,
                // Naik tiap regenerasi kunci (ganti perangkat / reset / rotasi paksa) —
                // penanda "kunci baru" untuk self-heal & debugging.
                Constants.Fields.E2EE_KEY_VERSION to FieldValue.increment(1)
            )
        ).await()
    }

    private suspend fun loadGroupKey() {
        if (groupKeyBytes != null) return
        val ctx = appContext ?: return
        val b64 = SecureStorage.getSecretAsync(ctx, groupKeyKey()) ?: return
        groupKeyBytes = runCatching { BackupCrypto.decodeBase64(b64) }.getOrNull()
    }

    private suspend fun storeGroupKey(key: ByteArray) {
        val ctx = appContext
        if (ctx != null) SecureStorage.putSecretAsync(ctx, groupKeyKey(), BackupCrypto.encodeBase64(key))
        val old = groupKeyBytes
        groupKeyBytes = key.copyOf()
        // L8: zero-kan cache lama sebelum referensinya dilepas.
        if (old !== key) old?.fill(0)
    }

    private suspend fun loadPrivateKey(): PrivateKey? {
        val ctx = appContext ?: return null
        val privB64 = SecureStorage.getSecretAsync(ctx, privKeyKey()) ?: return null
        val privBytes = BackupCrypto.decodeBase64(privB64)
        return try {
            KeyFactory.getInstance("EC")
                .generatePrivate(PKCS8EncodedKeySpec(privBytes))
        } catch (e: Exception) {
            Log.w(TAG, "Gagal memuat private key: ${e.message}")
            null
        } finally {
            // PKCS8EncodedKeySpec meng-clone array → aman di-zero di sini.
            privBytes.fill(0)
        }
    }

    // ======================= Real-time & berkala =======================

    /** Pantau marker: begitu owner mengaktifkan, langsung ambil kunci. */
    private fun listenForActivation() {
        markerListener?.remove()
        markerListener = markerRef().addSnapshotListener { snap, err ->
            if (err != null || familyId.isEmpty()) return@addSnapshotListener
            if (snap != null && snap.exists()) {
                val epoch = snap.getLong(KEY_EPOCH_FIELD) ?: 0L
                // H2: rotasi kunci menaikkan keyEpoch. Semua perangkat HARUS
                // mengambil ulang wrap & grup key, kalau tidak mantan anggota
                (yang masih punya kunci lama) tetap bisa membaca pesan baru.
                val rotated = lastSeenEpoch >= 0L && epoch != lastSeenEpoch
                val firstSeen = !markerActive
                markerActive = true
                activationState = ActivationState.ACTIVE
                keyEpochCache = epoch
                if (!firstSeen && !rotated) return@addSnapshotListener
                lastSeenEpoch = epoch
                scope.launch {
                    // Ambil ulang wrap (termasuk saat rotasi) tanpa syarat
                    // groupKeyBytes == null.
                    fetchAndUnwrapGroupKey()
                    selfHealWraps()
                    notifyReady()
                    publishStatus()
                }
            }
        }
    }

    /** Self-heal berkala: tangkap member/public-key baru, me-rewrap bila versi
     *  berubah, DAN retry ambil kunci bila perangkat ini belum punya grup key
     *  (kunci bisa datang belakangan — mis. setelah perangkat lain me-rewrap
     *  pasca-reinstall). */
    private fun startPeriodicHeal() {
        scope.launch {
            while (familyId.isNotEmpty()) {
                delay(HEAL_INTERVAL_MS)
                heal()
            }
        }
    }

    /** Beri tahu FirestoreSyncManager bahwa kunci siap → proses ulang yang tertunda. */
    private fun notifyReady() {
        if (isReady()) FirestoreSyncManager.onE2eeKeyReady()
    }

    /** Publikasikan status E2EE untuk UI (banner & gating kirim). */
    private fun publishStatus() {
        _status.value = E2eeStatus(active = markerActive, ready = isReady())
    }

    // ======================= Referensi & util =======================

    private fun db() = FirebaseFirestore.getInstance()
    private fun familyRef() = db().collection(Constants.Collections.FAMILIES).document(familyId)
    private fun membersRef() = familyRef().collection(Constants.Collections.MEMBERS)
    private fun e2eeKeysRef() = familyRef().collection(Constants.Collections.E2EE_KEYS)
    private fun markerRef() = familyRef().collection(Constants.Collections.E2EE).document(MARKER_DOC)

    private fun uid(): String? =
        runCatching { FirebaseAuth.getInstance().currentUser?.uid }.getOrNull()

    private fun safePin(): String = familyId.replace(Regex("[^A-Za-z0-9_-]"), "_")

    private fun privKeyKey() = "e2ee_priv_${safePin()}"
    private fun pubKeyKey() = "e2ee_pub_${safePin()}"
    private fun groupKeyKey() = "e2ee_group_${safePin()}"
}
