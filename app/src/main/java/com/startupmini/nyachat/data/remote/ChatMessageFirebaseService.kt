package com.startupmini.nyachat.data.remote

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.startupmini.nyachat.Constants
import com.startupmini.nyachat.MainActivity
import com.startupmini.nyachat.R
import com.startupmini.nyachat.data.local.SecureStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * FASE 3 item 3.7 — notifikasi chat real-time (\"seperti WhatsApp\"):
 * pesan baru dari anggota workspace lain dikirim ke perangkat ini via FCM
 * (data message dari Cloud Function `onMessageWrite`), lalu ditampilkan di
 * sini sebagai notifikasi. Kebijakan tampil (toggle user + skip pesan sendiri)
 * dijalankan DI APP, bukan di cloud — cloud hanya menyalurkan payload.
 *
 * r1.6.0: selain chat, service ini menampilkan notifikasi KEANGGOTAAN dari
 * cloud function `handleJoinRequest`:
 *   - type=join_request → pemilik mendapat "X ingin bergabung".
 *   - type=join_decision → pemohon mendapat "disetujui/ditolak".
 * Keduanya di-gate toggle notifikasi chat yang sama (keputusan produk beta).
 */
class ChatMessageFirebaseService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data

        // Toggle notifikasi (Settings) — default ON. Berlaku untuk chat &
        // keanggotaan (r1.6.0).
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        if (!prefs.getBoolean(Constants.Prefs.CHAT_NOTIFICATIONS_ENABLED, true)) return

        createChannels()

        when (data[KEY_TYPE]) {
            KEY_TYPE_JOIN_REQUEST -> {
                val familyName = data[KEY_FAMILY_NAME]?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.topbar_title)
                val requesterUid = data[KEY_REQUESTER_UID]
                // Audit L3: nama pemohon TIDAK dikirim lewat FCM. Pemohon belum
                // menjadi anggota (tidak ada member doc), jadi nama di-resolve
                // dari joinRequests/{requesterUid} yang boleh dibaca owner.
                ioScope.launch {
                    val name = data[KEY_REQUESTER_NAME]?.takeIf { it.isNotBlank() }
                        ?: resolveRequesterName(data[EXTRA_FAMILY_ID], requesterUid)
                    postNotification(
                        channelId = CHANNEL_ID_MEMBERSHIP,
                        // ID per-pemohon: dua permintaan dari orang berbeda tidak
                        // saling menimpa (audit r1.6.0).
                        notificationId = membershipNotificationId(requesterUid),
                        title = getString(R.string.notif_join_request_title),
                        text = getString(R.string.notif_join_request_body, name, familyName),
                        // R2-H1: bawa konteks agar tap langsung membuka Kelola Anggota.
                        type = TYPE_JOIN_REQUEST,
                        familyId = data[EXTRA_FAMILY_ID],
                        requesterUid = requesterUid
                    )
                }
            }
            KEY_TYPE_JOIN_DECISION -> {
                val approved = data[KEY_APPROVED] == "1"
                val familyName = data[KEY_FAMILY_NAME]?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.topbar_title)
                val text = if (approved) {
                    getString(R.string.notif_join_approved_body, familyName)
                } else {
                    getString(R.string.notif_join_rejected_body, familyName)
                }
                postNotification(
                    channelId = CHANNEL_ID_MEMBERSHIP,
                    notificationId = membershipNotificationId(data[KEY_REQUESTER_UID]),
                    title = getString(R.string.notif_join_decision_title),
                    text = text,
                    type = TYPE_JOIN_REQUEST,
                    familyId = data[EXTRA_FAMILY_ID],
                    requesterUid = data[KEY_REQUESTER_UID]
                )
            }
            else -> {
                // Audit L3: payload FCM tidak lagi membawa NAMA pengirim (metadata
                // bocor ke Google). Yang dikirim cloud hanya senderUid + cloudId;
                // nama di-resolve lokal dari member doc Firestore.
                val senderUid = data["senderUid"]?.takeIf { it.isNotBlank() }
                val myUid = FirebaseAuth.getInstance().currentUser?.uid
                if (senderUid != null && myUid != null && senderUid == myUid) return
                val cloudId = data["cloudId"] ?: ""
                val body = data["body"]?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.chat_notif_default_body)
                val notificationId = cloudId.hashCode().takeIf { it != 0 } ?: body.hashCode()

                if (senderUid == null) {
                    // Payload lama (pra-r1.7.4): fallback nama dari payload.
                    val sender = data["sender"]?.takeIf { it.isNotBlank() } ?: return
                    val localName = prefs.getString(Constants.Prefs.USER_NAME, null)
                    if (!localName.isNullOrBlank() && sender == localName) return
                    postNotification(
                        CHANNEL_ID, sender, body, notificationId,
                        type = TYPE_CHAT,
                        familyId = prefs.getString(Constants.Prefs.WORKSPACE_PIN, null)
                    )
                    return
                }

                val familyId = data[EXTRA_FAMILY_ID]?.takeIf { it.isNotBlank() }
                    ?: prefs.getString(Constants.Prefs.WORKSPACE_PIN, null)
                ioScope.launch {
                    val name = resolveMemberName(familyId, senderUid)
                    postNotification(
                        CHANNEL_ID, name, body, notificationId,
                        type = TYPE_CHAT,
                        familyId = familyId,
                        cloudId = cloudId
                    )
                }
            }
        }
    }

    /**
     * Tampilkan notifikasi (chat atau keanggotaan) dengan payload generik.
     *
     * R2-H1: PendingIntent kini membawa extras kontrak handoff (lihat konstanta
     * companion) dan requestCode UNIK per pesan. Sebelumnya requestCode konstan 0
     * + FLAG_UPDATE_CURRENT membuat extras notifikasi terakhir menimpa semua
     * notifikasi lain — tap notifikasi lama membuka chat/workspace yang salah
     * (dead tap).
     */
    private fun postNotification(
        channelId: String,
        title: String,
        text: String,
        notificationId: Int = title.hashCode() xor text.hashCode(),
        type: String = TYPE_CHAT,
        familyId: String? = null,
        cloudId: String? = null,
        requesterUid: String? = null
    ) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_TYPE, type)
            putExtra(EXTRA_FAMILY_ID, familyId)
            putExtra(EXTRA_CLOUD_ID, cloudId)
            putExtra(EXTRA_REQUESTER_UID, requesterUid)
            // Kandidat PIN dikirim, tapi MainActivity WAJIB memastikan user masih
            // member sebelum memakainya (hindari membuka workspace yang salah).
            putExtra(EXTRA_WORKSPACE_PIN, familyId)
        }
        // R2-H1: requestCode UNIK dari cloudId/requesterUid — bukan konstan 0.
        val handoffKey = cloudId?.takeIf { it.isNotBlank() }
            ?: requesterUid?.takeIf { it.isNotBlank() }
            ?: familyId?.takeIf { it.isNotBlank() }
            ?: title
        val requestCode = handoffKey.hashCode() and 0x7fffffff
        val pending = PendingIntent.getActivity(
            this, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // Audit ikon 2026-08-12: small icon harus MONOKROM (ic_stat_logo).
        // ic_logo (berwarna penuh) dirender sebagai kotak putih solid di
        // Android 5+ karena small icon dipakai sebagai alpha mask.
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_stat_logo)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try {
            NotificationManagerCompat.from(this).notify(notificationId, notification)
        } catch (e: SecurityException) {
            // Izin POST_NOTIFICATIONS belum diberikan — abaikan diam-diam.
        }
    }

    /**
     * ID notifikasi keanggotaan — per pemohon (requesterUid) supaya dua
     * permintaan/keputusan dari orang berbeda tidak saling menimpa (audit
     * r1.6.0). Fallback hash title jika uid tidak ada di payload.
     */
    private fun membershipNotificationId(requesterUid: String?): Int {
        val uid = requesterUid?.takeIf { it.isNotBlank() }
        return uid?.hashCode()?.takeIf { it != 0 }
            ?: (getString(R.string.notif_join_request_title).hashCode() and 0x7fffffff)
    }

    /** Audit L3: resolve nama anggota dari Firestore (bukan dari payload FCM). */
    private suspend fun resolveMemberName(familyId: String?, senderUid: String): String {
        val fallback = getString(R.string.sender_anggota)
        if (familyId.isNullOrBlank()) return fallback
        return runCatching {
            val snap = FirebaseFirestore.getInstance()
                .collection(Constants.Collections.FAMILIES).document(familyId)
                .collection(Constants.Collections.MEMBERS).document(senderUid)
                .get().await()
            snap.getString(Constants.Fields.NAME)?.takeIf { it.isNotBlank() } ?: fallback
        }.getOrDefault(fallback)
    }

    /** Audit L3: nama PEMOHON (belum jadi anggota) dari joinRequests doc. */
    private suspend fun resolveRequesterName(familyId: String?, requesterUid: String?): String {
        val fallback = getString(R.string.sender_anggota)
        if (familyId.isNullOrBlank() || requesterUid.isNullOrBlank()) return fallback
        return runCatching {
            val snap = FirebaseFirestore.getInstance()
                .collection(Constants.Collections.FAMILIES).document(familyId)
                .collection(Constants.Collections.JOIN_REQUESTS).document(requesterUid)
                .get().await()
            snap.getString(Constants.Fields.NAME)?.takeIf { it.isNotBlank() } ?: fallback
        }.getOrDefault(fallback)
    }

    override fun onNewToken(token: String) {
        syncFcmToken(applicationContext, token)
    }

    private fun createChannels() {
        // NotificationChannel baru ada sejak API 26 (minSdk 24 → guard).
        if (android.os.Build.VERSION.SDK_INT < 26) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        createChannel(
            manager,
            CHANNEL_ID,
            getString(R.string.chat_notif_channel_name),
            getString(R.string.chat_notif_channel_desc)
        )
        // r1.6.0: kanal keanggotaan — toggle-nya sama dengan chat (keputusan beta).
        createChannel(
            manager,
            CHANNEL_ID_MEMBERSHIP,
            getString(R.string.notif_membership_channel_name),
            getString(R.string.notif_membership_channel_desc)
        )
    }

    private fun createChannel(
        manager: NotificationManager,
        id: String,
        name: String,
        description: String
    ) {
        // NotificationChannel baru ada sejak API 26 (minSdk 24 → guard).
        if (android.os.Build.VERSION.SDK_INT < 26) return
        if (manager.getNotificationChannel(id) != null) return
        val channel = NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH)
            .apply { this.description = description }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "ChatNotifier"
        const val CHANNEL_ID = "chat_messages"
        // r1.6.0: kanal notifikasi keanggotaan (permintaan bergabung & keputusan).
        const val CHANNEL_ID_MEMBERSHIP = "workspace_activity"

        // R2-H1 — KONTRAK EXTRAS HANDOFF notifikasi → MainActivity.
        // Nama kunci sengaja sama dengan payload FCM/cloud function.
        const val EXTRA_TYPE = "type"
        const val EXTRA_FAMILY_ID = "familyId"
        const val EXTRA_CLOUD_ID = "cloudId"
        const val EXTRA_REQUESTER_UID = "requesterUid"
        /** Kandidat PIN workspace; dipakai HANYA bila user masih member. */
        const val EXTRA_WORKSPACE_PIN = "workspacePin"
        const val TYPE_CHAT = "chat"
        const val TYPE_JOIN_REQUEST = "join_request"

        // Payload keys cloud function handleJoinRequest (r1.6.0) — sinkron dengan
        // functions/index.js.
        private const val KEY_TYPE = "type"
        private const val KEY_TYPE_JOIN_REQUEST = "join_request"
        private const val KEY_TYPE_JOIN_DECISION = "join_decision"
        private const val KEY_REQUESTER_NAME = "requesterName"
        private const val KEY_REQUESTER_UID = "requesterUid"
        private const val KEY_FAMILY_NAME = "familyName"
        private const val KEY_APPROVED = "approved"

        /** Scope satu-shot sinkronisasi token (dibatalkan bersama proses app). */
        private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * Simpan token FCM perangkat ini ke dokumen member sendiri di Firestore
         * (families/{PIN}/members/{uid}/fcmToken) — dipakai Cloud Function untuk
         * mengirim notifikasi. Di-skip bila belum login / belum ada workspace.
         */
        fun syncFcmToken(context: Context, token: String) {
            val uid = FirebaseAuth.getInstance().currentUser?.uid ?: run {
                Log.w(TAG, "syncFcmToken: tidak ada currentUser")
                return
            }
            // PIN (secret) dibaca async di IO — Keystore jangan diblokir di main.
            ioScope.launch {
                val pin = SecureStorage.getSecretAsync(context, Constants.Prefs.WORKSPACE_PIN)
                if (pin.isNullOrBlank()) {
                    Log.w(TAG, "syncFcmToken: PIN kosong, uid=$uid")
                    return@launch
                }
                FirebaseFirestore.getInstance()
                    .collection(Constants.Collections.FAMILIES).document(pin)
                    .collection(Constants.Collections.MEMBERS).document(uid)
                    .update(Constants.Fields.FCM_TOKEN, token)
                    .addOnSuccessListener {
                        Log.i(TAG, "Token FCM tersimpan: pin=${pin.take(4)}… uid=${uid.take(8)}…")
                    }
                    .addOnFailureListener { e ->
                        // Wajar bila rules baru belum di-deploy / belum member — token
                        // akan disinkronkan lagi saat onNewToken berikutnya.
                        Log.w(TAG, "Simpan token FCM gagal (uid=${uid.take(8)}…): ${e.message}")
                    }
            }
        }

        /** Pastikan token perangkat aktif tersinkron (dipanggil saat workspace aktif). */
        fun ensureTokenSynced(context: Context) {
            FirebaseMessaging.getInstance().token
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        val token = task.result
                        if (token.isNullOrBlank()) {
                            Log.w(TAG, "ensureTokenSynced: token kosong")
                            return@addOnCompleteListener
                        }
                        Log.i(TAG, "Token FCM didapat (${token.length} chars), sync ke Firestore...")
                        syncFcmToken(context, token)
                    } else {
                        Log.w(TAG, "Gagal dapat token FCM: ${task.exception?.message ?: "unknown"}")
                    }
                }
        }
    }
}
