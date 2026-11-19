/**
 * Nyachat — Cloud Functions (Firebase Functions v2).
 *
 * onMessageWrite (FASE 3 item 3.7):
 * Setiap pesan baru di families/{familyId}/messages/{cloudId} dikirim sebagai
 * FCM *data message* ke semua anggota workspace (kecuali yang tidak punya
 * token). Kebijakan TAMPIL dijalankan di app (FirebaseMessagingService):
 *   - toggle "Notifikasi chat" di Settings,
 *   - skip pesan dari diri sendiri (payload FCM `senderUid` == uid lokal).
 * Cloud hanya menyalurkan payload — tidak menentukan tampilan/suara.
 */
const { onDocumentWritten } = require('firebase-functions/v2/firestore');
const { onCall, HttpsError } = require('firebase-functions/v2/https');
const { defineSecret } = require('firebase-functions/params');
// CATATAN: firebase-functions/logger mengekspor objek logger LANGSUNG, BUKAN
// properti bernama `logger` — `const { logger } = require(...)` menghasilkan
// undefined dan membuat logger.warn/error crash di runtime (bug FASE 4
// ditemukan saat live test: TypeError reading 'warn').
const logger = require('firebase-functions/logger');
const admin = require('firebase-admin');
const crypto = require('crypto'); // reservePin: token klaim acak (modul built-in Node, bukan dependency baru)

admin.initializeApp();

/**
 * L5a: jangan pernah meneruskan objek Error mentah ke logger — objek tersebut
 * bisa memuat URL/body/token. Cukup `e.code || e.message` yang dipangkas dan
 * disanitasi (query `?key=` tidak pernah ikut tercatat).
 */
function errDetail(e) {
  const raw = (e && (e.code || e.message)) || 'unknown';
  return String(raw)
    .replace(/[?&]key=[^&\s]*/gi, 'key=REDACTED')
    .slice(0, 200);
}

// ============================================================================
// RELAY AI (server-owned API keys) — FASE 4
// ----------------------------------------------------------------------------
// Latar belakang: app memakai BYOK (Bring Your Own Key) — user mengisi kunci
// Gemini/OpenRouter sendiri di Pengaturan. Kalau user TIDAK mengisi kunci,
// deteksi transaksi jatuh ke mesin heuristik offline yang kaku.
//
// Solusi: Cloud Function `aiComplete` memegang KUNCI MILIK SERVER (disimpan
// sebagai Firebase Functions secrets — TIDAK pernah dikompilasi ke APK). App
// yang sudah login cukup memanggil fungsi ini (auth Firebase diverifikasi
// otomatis oleh protokol callable); server yang memanggil AI (OpenRouter gratis
// → Gemini), lalu mengembalikan teks mentah. App tetap yang membangun prompt &
// mem-parse JSON — satu fungsi generik untuk parse transaksi, saran cepat,
// laporan audit, analisis bulanan, dan tanya AI.
//
// Setup satu kali:
//   firebase functions:secrets:set OPENROUTER_API_KEY
//   firebase functions:secrets:set GEMINI_API_KEY
//   firebase deploy --only functions
//
// Model "opencode zen" yang diminta user tidak ditemukan di katalog OpenRouter
// (per 2026-08-10) — daftar model gratis terverifikasi dipakai sebagai gantinya.
// ============================================================================

const OPENROUTER_SECRET = defineSecret('OPENROUTER_API_KEY');
const GEMINI_SECRET = defineSecret('GEMINI_API_KEY');

/** Model gratis OpenRouter (terverifikasi aktif per 2026-08-10). */
const FREE_MODELS = [
  'google/gemma-4-31b-it:free',
  'google/gemma-4-26b-a4b-it:free',
  'openai/gpt-oss-20b:free',
  'nvidia/nemotron-3-ultra-550b-a55b:free',
  'inclusionai/ling-3.0-tiny:free',
  'poolside/laguna-xs-2.1:free',
  'openrouter/free',
];

/**
 * Baca secret Firebase dengan fallback ke env var dengan nama yang sama
 * (memudahkan dev lokal / CI tanpa Secret Manager — deploy biasa cukup
 * `firebase functions:secrets:set`). defineSecret.value() melempar bila
 * secret belum diset.
 */
function readSecret(secret) {
  let configured = false;
  try {
    const v = secret.value();
    if (typeof v === 'string' && v.length > 0) {
      configured = true;
      return v;
    }
  } catch (e) {
    // L5b: di produksi JANGAN diam-diam jatuh ke env var — secret yang gagal
    // dibaca harus membuat relay GAGAL TUTUP (fail closed), bukan memakai
    // fallback yang bisa jadi kunci usang / tidak sengaja terekspos.
    if (process.env.FUNCTIONS_EMULATOR !== 'true') {
      logger.error('Gagal baca secret ' + secret.name + ' code=' + errDetail(e));
      return null;
    }
    // Emulator/CI lokal: env var masih diizinkan sebagai fallback dev.
    logger.warn('Secret ' + secret.name + ' belum diset (emulator), cek env var');
  }
  // L5b: secret.value() mengembalikan string KOSONG juga harus fail-closed di
  // produksi — jangan jatuh ke env var hanya karena tidak melempar exception.
  if (!configured && process.env.FUNCTIONS_EMULATOR !== 'true') {
    logger.error('Secret ' + secret.name + ' kosong di produksi — relay ditolak');
    return null;
  }
  const env = process.env[secret.name];
  return typeof env === 'string' && env.length > 0 ? env : null;
}

async function callOpenRouter(apiKey, model, prompt, imageBase64) {
  const content = imageBase64
    ? [
        { type: 'text', text: prompt },
        { type: 'image_url', image_url: { url: `data:image/jpeg;base64,${imageBase64}` } },
      ]
    : prompt;
  const resp = await fetch('https://openrouter.ai/api/v1/chat/completions', {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${apiKey}`,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({
      model,
      messages: [{ role: 'user', content }],
      temperature: 0.2,
    }),
  });
  if (!resp.ok) throw new Error(`OpenRouter HTTP ${resp.status}`);
  const json = await resp.json();
  const text = json.choices && json.choices[0] && json.choices[0].message
    ? String(json.choices[0].message.content || '')
    : '';
  return text.trim() || null;
}

async function callGemini(apiKey, prompt, imageBase64) {
  const parts = [{ text: prompt }];
  if (imageBase64) {
    parts.push({ inline_data: { mime_type: 'image/jpeg', data: imageBase64 } });
  }
  // L4: kunci API JANGAN masuk URL (query string mudah bocor ke log/proxy) —
  // Gemini menerima kunci lewat header `x-goog-api-key`.
  const url =
    'https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent';
  const resp = await fetch(url, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'x-goog-api-key': apiKey,
    },
    body: JSON.stringify({
      contents: [{ parts }],
      generationConfig: { temperature: 0.2 },
    }),
  });
  if (!resp.ok) throw new Error(`Gemini HTTP ${resp.status}`);
  const json = await resp.json();
  const text =
    json.candidates &&
    json.candidates[0] &&
    json.candidates[0].content &&
    json.candidates[0].content.parts
      ? String(json.candidates[0].content.parts[0].text || '')
      : '';
  return text.trim() || null;
}

// ============================================================================
// BATAS PENGGUNAAN AI (P1#2 — audit 2026-08-14, diperkuat M8)
// ----------------------------------------------------------------------------
// aiComplete hanya butuh auth Google (bukan keanggotaan keluarga mana pun) —
// tanpa batas, siapa pun bisa memanggil berulang kali dan menguras kuota AI
// server (biaya tak terkendali). M8: pembatas WAJIB durable di Firestore,
// bukan Map in-memory per-instance (abuse lintas instance masih mungkin dulu).
// Counter disimpan di `rateLimits/{key}` dengan key = uid + bucket waktu.
// ============================================================================
const MAX_PROMPT_CHARS = 6000;            // ~1.500 token — prompt AI keluarga
const MAX_IMAGE_BASE64_CHARS = 3_000_000; // hard cap ~2,2 MB biner foto nota
const MAX_IMAGE_BASE64_GUARDED_CHARS = 1_500_000; // guard biaya: ~1,1 MB biner
const AI_IMAGE_MODEL_FANOUT = 2;          // M8: gambar → maks 2 model, bukan semua
const RATE_LIMIT_MINUTE_MAX = 30;         // maks 30 panggilan/menit/uid
const RATE_LIMIT_DAY_MAX = 200;           // maks 200 panggilan/hari/uid
const RATE_LIMIT_GLOBAL_MINUTE_MAX = 2000;// maks 2000 panggilan/menit (semua uid)

/**
 * M8: pembatas laju DURABLE. Satu transaksi Firestore membaca + menaikkan tiga
 * counter sekaligus: per-menit/uid, per-hari/uid, dan global per-menit.
 * Doc yang belum ada dianggap count 0. Bila salah satu batas terlampaui,
 * transaksi dibatalkan dan HttpsError('resource-exhausted') dilempar.
 */
async function allowCallDurable(uid, now) {
  const minuteBucket = Math.floor(now / 60_000);
  const dayBucket = Math.floor(now / 86_400_000);
  const db = admin.firestore();
  const specs = [
    { key: `ai_min_${uid}_${minuteBucket}`, max: RATE_LIMIT_MINUTE_MAX },
    { key: `ai_day_${uid}_${dayBucket}`, max: RATE_LIMIT_DAY_MAX },
    { key: `ai_global_${minuteBucket}`, max: RATE_LIMIT_GLOBAL_MINUTE_MAX },
  ];
  const refs = specs.map((s) => db.collection('rateLimits').doc(s.key));

  try {
    await db.runTransaction(async (tx) => {
      // SEMUA baca dulu, baru tulis — aturan wajib Firestore transaction.
      const snaps = await Promise.all(refs.map((ref) => tx.get(ref)));
      const counts = snaps.map((snap) => {
        if (!snap.exists) return 0; // doc belum ada → count 0
        const c = (snap.data() || {}).count;
        return typeof c === 'number' && Number.isFinite(c) && c >= 0 ? c : 0;
      });
      for (let i = 0; i < specs.length; i++) {
        if (counts[i] >= specs[i].max) {
          throw new HttpsError('resource-exhausted', 'Terlalu banyak panggilan AI. Coba lagi sebentar.');
        }
      }
      counts.forEach((c, i) => {
        tx.set(refs[i], {
          count: c + 1,
          updatedAt: admin.firestore.FieldValue.serverTimestamp(),
        }, { merge: true });
      });
    });
  } catch (e) {
    // HttpsError dari batas kita diteruskan apa adanya.
    if (e instanceof HttpsError) throw e;
    // Firestore sendiri bisa melempar resource-exhausted (kuota/kontensi) —
    // fail closed: tolak panggilan, jangan beri akses gratis saat sistem penuh.
    if (e && e.code === 'resource-exhausted') {
      throw new HttpsError('resource-exhausted', 'Terlalu banyak panggilan AI. Coba lagi sebentar.');
    }
    throw e; // error lain → firebase-functions menerjemahkan ke 'internal'
  }
}

/**
 * aiComplete — relay AI generik. Callable = auth Firebase otomatis diverifikasi
 * (request.auth non-null hanya untuk user terautentikasi). Body: { prompt,
 * imageBase64? }. Response: { text } — null saat semua penyedia gagal.
 */
exports.aiComplete = onCall(
  {
    secrets: [OPENROUTER_SECRET, GEMINI_SECRET],
    timeoutSeconds: 55,
  },
  async (request) => {
    if (!request.auth) {
      throw new HttpsError('unauthenticated', 'Harus login untuk memakai AI server.');
    }
    // M8: batas laju DURABLE (per-menit/uid, per-hari/uid, global per-menit).
    await allowCallDurable(request.auth.uid, Date.now());
    const data = request.data || {};
    const prompt = String(data.prompt || '').trim();
    if (!prompt) {
      throw new HttpsError('invalid-argument', 'Prompt tidak boleh kosong.');
    }
    if (prompt.length > MAX_PROMPT_CHARS) {
      throw new HttpsError('invalid-argument', 'Prompt terlalu panjang.');
    }
    const imageBase64 =
      typeof data.imageBase64 === 'string' && data.imageBase64.length > 0
        ? data.imageBase64
        : null;
    if (imageBase64 && imageBase64.length > MAX_IMAGE_BASE64_CHARS) {
      throw new HttpsError('invalid-argument', 'Gambar terlalu besar.');
    }
    // M8: guard biaya tambahan — image request lebih mahal, batasi ukuran &
    // fan-out model (cukup 2 model pertama, bukan seluruh FREE_MODELS).
    if (imageBase64 && imageBase64.length > MAX_IMAGE_BASE64_GUARDED_CHARS) {
      throw new HttpsError('invalid-argument', 'Gambar terlalu besar untuk diproses AI.');
    }
    const modelsToTry = imageBase64 ? FREE_MODELS.slice(0, AI_IMAGE_MODEL_FANOUT) : FREE_MODELS;

    // 1) OpenRouter (server key) — model gratis dengan rotasi otomatis
    const orKey = readSecret(OPENROUTER_SECRET);
    if (orKey) {
      for (const model of modelsToTry) {
        try {
          const text = await callOpenRouter(orKey, model, prompt, imageBase64);
          if (text) {
            // P2#5 (audit 2026-08-14): JANGAN log isi output — output AI rekap
            // berisi data finansial pengguna (privasi). Cukup model + panjang.
            logger.info('Relay OK model=' + model + ' len=' + text.length);
            return { text };
          }
        } catch (e) {
          logger.warn('Relay OpenRouter gagal: ' + model + ' code=' + errDetail(e));
        }
      }
    }

    // 2) Gemini (server key)
    const gemKey = readSecret(GEMINI_SECRET);
    if (gemKey) {
      try {
        const text = await callGemini(gemKey, prompt, imageBase64);
        if (text) return { text };
      } catch (e) {
        logger.warn('Relay Gemini gagal code=' + errDetail(e));
      }
    }

    // Semua penyedia gagal / tidak ada key server → null (app lanjut heuristik)
    return { text: null };
  }
);

// ============================================================================
// NOTIFIKASI CHAT & KEANGGOTAAN (FASE 3 item 3.7 + r1.6.0)
// ============================================================================

// Batas anggota per plan — HARUS sinkron dengan Constants.Limits di app.
const PLAN_FREE = 'free';
const PLAN_PRO = 'pro';
const LIMIT_FREE = 2;
const LIMIT_PRO = 6;

/** Token FCM anggota, null bila kosong/rusak. */
function tokenOf(memberDoc) {
  const t = memberDoc.data().fcmToken;
  return typeof t === 'string' && t.length > 0 ? t : null;
}

/**
 * Kirim data message multicast ke token FCM & bersihkan token invalid
 * (app di-uninstall / token kedaluwarsa) supaya kiriman berikutnya lebih
 * bersih & biaya FCM tidak terbuang. Dipakai notifyChatMessage &
 * handleJoinRequest (DRY).
 * @param {Array<{uid:string, token:string}>} tokensByUid
 */
async function sendMulticastAndCleanup(familyId, tokensByUid, data) {
  if (tokensByUid.length === 0) return;
  const tokens = tokensByUid.map((r) => r.token);
  let response;
  try {
    response = await admin.messaging().sendEachForMulticast({ tokens: tokens, data: data });
  } catch (err) {
    logger.warn('Gagal kirim notifikasi multicast code=' + errDetail(err));
    return;
  }
  const invalidUids = [];
  response.responses.forEach((r, i) => {
    const rec = tokensByUid[i];
    if (!rec) return;
    const err = r.error || {};
    if (!r.success) {
      invalidUids.push(rec.uid);
      console.log('FCM gagal uid=' + rec.uid.slice(0, 10) +
        ' code=' + (err.code || '?') +
        ' msg=' + (err.message || '?'));
    }
  });
  console.log('Multicast total=' + tokensByUid.length +
    ' gagal=' + invalidUids.length);
  if (invalidUids.length > 0) {
    // L5d: Firestore batch maks 500 operasi — pecah menjadi chunk + commit
    // terpisah supaya cleanup tidak gagal total saat token invalid banyak.
    const BATCH_CHUNK = 500;
    for (let i = 0; i < invalidUids.length; i += BATCH_CHUNK) {
      const batch = admin.firestore().batch();
      invalidUids.slice(i, i + BATCH_CHUNK).forEach((uid) => {
        batch.update(
          admin.firestore()
            .collection('families').doc(familyId).collection('members').doc(uid),
          { fcmToken: admin.firestore.FieldValue.delete() }
        );
      });
      await batch.commit()
        .catch((err) => logger.warn('Hapus token FCM invalid gagal code=' + errDetail(err)));
    }
  }
}

// Nama fungsi sengaja BUKAN onMessageWrite — deploy percobaan pertama sempat
// membuat resource dengan trigger HTTPS yang tidak bisa di-update ke trigger
// background ("Changing from an HTTPS function..."). Nama baru = resource baru.
exports.notifyChatMessage = onDocumentWritten(
  'families/{familyId}/messages/{cloudId}',
  async (event) => {
    const after = event.data && event.data.after;
    if (!after || !after.exists) return; // pesan dihapus → tanpa notifikasi
    // Sempurnakan 2026-08-14: EDIT pesan (before ada) → JANGAN notifikasi ulang
    // ke semua anggota — sebelumnya tiap edit mengirim notifikasi duplikat.
    const before = event.data && event.data.before;
    if (before && before.exists) return;
    const data = after.data();
    if (!data) return;
    const familyId = event.params.familyId;

    // r1.7.0 (E2EE): konten pesan TIDAK dikirim ke cloud apa adanya lagi —
    // sejak aktivasi E2EE body = ciphertext `enc` yang tidak terbaca server.
    // L3: payload FCM sengaja MINIMAL — `{type, cloudId, familyId, senderUid}`.
    // Nama tampilan (`sender`) & isi (`body`) TIDAK dikirim; app yang resolve
    // nama lokal dari doc member. `senderUid` WAJIB dikirim agar klien bisa
    // skip notifikasi pesannya sendiri (ChatMessageFirebaseService.kt:82-96).
    const senderUid = String(data.senderUid || '');

    // Kumpulkan token FCM semua anggota yang pernah disinkronkan.
    const membersSnap = await admin.firestore()
      .collection('families').doc(familyId)
      .collection('members').get();
    const recipients = []; // { uid, token }
    membersSnap.forEach((doc) => {
      const t = tokenOf(doc);
      // r1.6.1: jangan kirim ke penulis sendiri (hemat FCM & tidak perlu
      // di-skip lagi di app). Bila senderUid kosong (data lama) kirim semua.
      if (!t || (senderUid && doc.id === senderUid)) return;
      recipients.push({ uid: doc.id, token: t });
    });
    await sendMulticastAndCleanup(familyId, recipients, {
      type: 'chat',
      cloudId: String(event.params.cloudId),
      familyId: String(familyId),
      senderUid: senderUid,
    });
  }
);

/**
 * r1.7.0 — CHAT EPHEMERAL ala WhatsApp: hapus pesan dari server begitu SEMUA
 * perangkat anggota menulis ACK di subkoleksi deliveries/{uid} (perangkat
 * menerima & menyimpan pesan di Room — server tidak menyimpan konten).
 *
 * Trigger: tiap tulis di `families/{familyId}/messages/{cloudId}/deliveries/{uid}`.
 *  1. Pesan plaintext lama (msgVersion=0) → TIDAK pernah dihapus (riwayat lama
 *     tetap tersimpan & tersinkron antar perangkat).
 *  2. Pesan terenkripsi (msgVersion=1): hapus bila (a) semua anggota punya ACK,
 *     ATAU (b) TTL: pesan berumur > TTL_MS sejak timestamp (jaring pengaman
 *     untuk perangkat yang tidak pernah online lagi).
 *  3. Hapus: doc pesan + subkoleksi ACK-nya + foto blob Storage (best-effort).
 */
exports.cleanupDeliveredMessage = onDocumentWritten(
  'families/{familyId}/messages/{cloudId}/deliveries/{uid}',
  async (event) => {
    const familyId = event.params.familyId;
    const cloudId = event.params.cloudId;
    const messageRef = admin.firestore()
      .collection('families').doc(familyId)
      .collection('messages').doc(cloudId);

    const messageSnap = await messageRef.get();
    if (!messageSnap.exists) return; // sudah dihapus
    const data = messageSnap.data() || {};
    // Hanya pesan terenkripsi (post-E2EE) yang ephemeral.
    if (Number(data.msgVersion || 0) !== 1) return;
    const TTL_MS = 90 * 24 * 60 * 60 * 1000; // 90 hari jaring pengaman
    const old = Number(data.timestamp || 0) > 0 &&
      Date.now() - Number(data.timestamp) > TTL_MS;
    if (!old) {
      // Butuh semua member punya ACK (termasuk pengirim — ia juga menerima
      // pesannya sendiri lewat snapshot listener & menulis ACK).
      const [membersSnap, deliveriesSnap] = await Promise.all([
        admin.firestore()
          .collection('families').doc(familyId).collection('members').get(),
        messageRef.collection('deliveries').get()
      ]);
      const memberCount = membersSnap.size;
      const ackedUids = new Set(deliveriesSnap.docs.map((d) => d.id));
      const allAcked = memberCount > 0 &&
        membersSnap.docs.every((d) => ackedUids.has(d.id));
      if (!allAcked) return;
    }

    // Hapus pesan + ACK + foto Storage (best-effort, jangan menggagalkan doc).
    const batch = admin.firestore().batch();
    batch.delete(messageRef);
    const deliveries = await messageRef.collection('deliveries').get();
    deliveries.docs.forEach((d) => batch.delete(d.ref));
    await batch.commit()
      .catch((err) => logger.warn('Hapus pesan+ACK gagal code=' + errDetail(err)));
    const storagePath =
      'families/' + familyId + '/messages/' + cloudId + '.jpg';
    await admin.storage().bucket().file(storagePath).delete()
      .catch(() => { /* foto belum ada / sudah dihapus */ });
  }
);

/**
 * r1.7.1 — TANDA TERIMA/BACA: hapus marker `receipts/{cloudId}` begitu SEMUA
 * ANGGOTA LAIN sudah MEMBACA pesan (titik UI menjadi pelangi). Pengirim tidak
 * pernah menandai pesannya sendiri dibaca → dia di-exclude dari perhitungan.
 * Konten pesan sudah dihapus lebih dulu (cleanupDeliveredMessage saat semua
 * menerima); marker ini hanya membawa array uid (tanpa konten) supaya dots
 * tetap hidup.
 *
 * Sekaligus TTL jaring pengaman (14 hari tanpa aktivitas) untuk anggota yang
 * UNINSTALL: pesannya tak pernah "semua baca", marker dibiarkan hidup sampai
 * kedaluwarsa, lalu dots hilang (pesan tetap ada di Room perangkat). Cleanup
 * TTL dijalankan INLINE di sini (tiap tulis receipt) — sengaja TIDAK memakai
 * onSchedule supaya deploy tidak perlu mengaktifkan API Cloud Scheduler
 * (perlu IAM tambahan di service account CI).
 *
 * Trigger: tiap tulis di `families/{familyId}/receipts/{cloudId}`.
 */
exports.cleanupReceipt = onDocumentWritten(
  'families/{familyId}/receipts/{cloudId}',
  async (event) => {
    const familyId = event.params.familyId;
    const cloudId = event.params.cloudId;
    const familyRef = admin.firestore()
      .collection('families').doc(familyId);
    const receiptRef = familyRef.collection('receipts').doc(cloudId);
    const receiptSnap = await receiptRef.get();
    if (!receiptSnap.exists) return;
    const data = receiptSnap.data() || {};
    const readBy = Array.isArray(data.readBy) ? data.readBy : [];
    const senderUid = typeof data.senderUid === 'string' ? data.senderUid : '';
    if (senderUid) {
      const membersSnap = await familyRef.collection('members').get();
      const memberUids = membersSnap.docs.map((d) => d.id);
      // "Semua baca" = setiap anggota LAIN (selain pengirim) sudah masuk readBy.
      const others = memberUids.filter((uid) => uid && uid !== senderUid);
      const allRead = others.length > 0 &&
        others.every((uid) => readBy.includes(uid));
      if (allRead) {
        await receiptRef.delete()
          .catch((err) => logger.warn('Hapus receipt gagal code=' + errDetail(err)));
        return;
      }
    }

    // TTL jaring pengaman: bersihkan marker lama (>= 14 hari tanpa aktivitas)
    // di keluarga ini. Dipicu tiap tulis receipt — cukup untuk menjaga koleksi
    // tetap ramping tanpa jam dinding terpisah.
    const STALE_RECEIPT_TTL_MS = 14 * 24 * 60 * 60 * 1000; // 14 hari
    const cutoff = new Date(Date.now() - STALE_RECEIPT_TTL_MS);
    const stale = await familyRef.collection('receipts')
      .where('updatedAt', '<', cutoff).limit(1000).get();
    if (stale.empty) return;
    const batch = admin.firestore().batch();
    stale.docs.forEach((d) => {
      if (d.id !== cloudId) batch.delete(d.ref);
    });
    await batch.commit()
      .catch((err) => logger.warn('Hapus receipt kadaluwarsa gagal code=' + errDetail(err)));
  }
);

/**
 * Notifikasi keanggotaan (r1.6.0) — trigger pada join request:
 * - CREATE: permintaan bergabung masuk → notifikasi PEMILIK (jika workspace
 *   masih muat). Body memakai nama custom workspace bila ada.
 * - DELETE: keputusan owner (approved/rejected) → notifikasi PEMOHON via token
 *   yang disimpan di doc request saat requestJoin. Request yang dicabut sendiri
 *   (tanpa status) tidak menghasilkan notifikasi.
 * Payload berisi `type` sehingga app bisa membedakan dari notifikasi chat.
 */
exports.handleJoinRequest = onDocumentWritten(
  'families/{familyId}/joinRequests/{uid}',
  async (event) => {
    const before = event.data && event.data.before;
    const after = event.data && event.data.after;
    const familyId = event.params.familyId;
    const uid = event.params.uid;
    const famRef = admin.firestore()
      .collection('families').doc(familyId);

    // EVENT DELETE — keputusan owner.
    if (after && !after.exists && before && before.exists) {
      const req = before.data() || {};
      const token = typeof req.fcmToken === 'string' && req.fcmToken.length > 0
        ? req.fcmToken
        : null;
      if (!token) return;
      // Approve → member doc ada (ditulis atomik bersama delete request).
      // Reject → ditandai `status='rejected'` sebelum delete.
      // Selain itu (dicabut sendiri) → tanpa notifikasi.
      let approved = false;
      try {
        const memberSnap = await famRef.collection('members').doc(uid).get();
        approved = memberSnap.exists;
      } catch (err) {
        logger.warn('handleJoinRequest: cek member gagal code=' + errDetail(err));
        return;
      }
      if (!approved && req.status !== 'rejected') return;
      let familyName = '';
      try {
        const fam = await famRef.get();
        if (fam.exists) familyName = String(fam.data().name || '');
      } catch (err) {
        logger.warn('handleJoinRequest: baca keluarga gagal code=' + errDetail(err));
      }
      await sendMulticastAndCleanup(familyId, [{ uid, token }], {
        type: 'join_decision',
        approved: approved ? '1' : '0',
        requesterUid: uid,
        familyName: familyName
      });
      return;
    }

    // Bukan CREATE (hanya update status oleh owner) → tanpa notifikasi.
    if (!after || !after.exists) return;
    if (before && before.exists) return;

    // CREATE — permintaan masuk. Cek kapasitas dulu: workspace penuh → tanpa
    // notifikasi (owner hanya akan melihat permintaan yang tak bisa disetujui).
    try {
      const fam = await famRef.get();
      if (!fam.exists) return;
      const famData = fam.data() || {};
      const limit = famData.plan === PLAN_PRO ? LIMIT_PRO : LIMIT_FREE;
      const membersSnap = await famRef.collection('members').get();
      if (membersSnap.size >= limit) return;

      const ownerTokens = [];
      membersSnap.forEach((doc) => {
        if (doc.data().role === 'owner') {
          const t = tokenOf(doc);
          if (t) ownerTokens.push({ uid: doc.id, token: t });
        }
      });
      if (ownerTokens.length === 0) return;
      // L3: payload minimal — requesterUid dipertahankan (dipakai klien sbg
      // ID notifikasi), familyName dipertahankan. Nama & email pemohon TIDAK
      // dikirim: klien yang resolve nama lokal dari doc member.
      await sendMulticastAndCleanup(familyId, ownerTokens, {
        type: 'join_request',
        requesterUid: uid,
        // Audit L3: familyId (PIN) dipakai klien untuk resolve nama pemohon dari
        // joinRequests doc — nama/email TIDAK dikirim lewat FCM.
        familyId: familyId,
        familyName: String(famData.name || '')
      });
    } catch (err) {
      logger.warn('handleJoinRequest gagal code=' + errDetail(err));
    }
  }
);

// ============================================================================
// CALLABLE BARU — PIN, KUOTA & ATOMISITAS KEANGGOTAAN (M8 / L5)
// ----------------------------------------------------------------------------
// Semua endpoint di bawah WAJIB auth (fail closed). Penulisan ke doc yang
// dijaga Firestore Rules dilakukan lewat Admin SDK — klien tidak bisa langsung
// menulis field sensitif (plan, member, request, klaim PIN).
// ============================================================================

const PIN_LENGTH = 8;
const MAX_ACTIVE_PIN_CLAIMS = 5;
const PIN_CLAIM_TTL_MS = 300_000;          // 5 menit
const PIN_ATTEMPT_COOLDOWN_MS = 300_000;   // 5 menit antar percobaan
const PIN_ATTEMPT_WINDOW_MS = 3_600_000;   // jendela 1 jam
const PIN_ATTEMPT_MAX = 8;

/** PIN workspace BARU = tepat 8 digit (sinkron Constants.PIN_LENGTH). */
function isValidPin(value) {
  return typeof value === 'string' &&
    value.length === PIN_LENGTH &&
    /^\d{8}$/.test(value);
}

/** PIN lama (legacy) boleh 6 digit saat mencoba GABUNG — workspace lama
 *  harus tetap bisa diakses. Rate-limit server (registerPinAttempt) tetap
 *  berlaku penuh, jadi penerimaan legacy tidak membuka brute force. */
function isValidPinForAttempt(value) {
  return typeof value === 'string' &&
    (value.length === PIN_LENGTH || value.length === 6) &&
    /^\d{6,8}$/.test(value);
}

/**
 * reservePin — pesan claim PIN sementara sebelum user membuat/menggabungkan
 * workspace. `create()` sengaja dipakai supaya dua klien yang memesan PIN yang
 * sama tidak bisa menimpa (fails if exists). Token acak dipakai rules gate.
 */
exports.reservePin = onCall(async (request) => {
  if (!request.auth) {
    throw new HttpsError('unauthenticated', 'Harus login untuk memesan PIN.');
  }
  const data = request.data || {};
  const familyId = String(data.familyId || '');
  if (!isValidPin(familyId)) {
    throw new HttpsError('invalid-argument', 'PIN harus 8 digit angka.');
  }
  const uid = request.auth.uid;
  const db = admin.firestore();
  const now = Date.now();
  const expiresAtDate = new Date(now + PIN_CLAIM_TTL_MS);
  const expiresAt = admin.firestore.Timestamp.fromDate(expiresAtDate);

  const token = crypto.randomUUID();
  const ref = db.collection('pinClaims').doc(familyId);
  try {
    // Kuota & create() dalam SATU transaksi → dua request bersamaan tidak bisa
    // sama-sama lolos dari batas 5 klaim aktif per uid.
    await db.runTransaction(async (tx) => {
      // Query hanya pakai `uid ==` (single-field index), saring kedaluwarsa di
      // memori — menghindari kebutuhan composite index.
      const owned = await tx.get(db.collection('pinClaims').where('uid', '==', uid));
      const activeCount = owned.docs.filter((doc) => {
        const v = (doc.data() || {}).expiresAt;
        return v && typeof v.toMillis === 'function' && v.toMillis() > now;
      }).length;
      if (activeCount >= MAX_ACTIVE_PIN_CLAIMS) {
        throw new HttpsError('resource-exhausted', 'Terlalu banyak klaim PIN aktif. Coba lagi sebentar.');
      }
      tx.create(ref, {
        uid: uid,
        token: token,
        expiresAt: expiresAt,
        createdAt: admin.firestore.FieldValue.serverTimestamp(),
      });
    });
  } catch (e) {
    // 6 = ALREADY_EXISTS (Firestore) — PIN sedang dipesan pihak lain.
    if (e && e.code === 6) {
      throw new HttpsError('already-exists', 'PIN sedang dipesan pengguna lain.');
    }
    throw e;
  }
  // `expiresAt` dikembalikan sebagai epoch millis (angka) — aman di payload JSON.
  return { token: token, expiresAt: expiresAtDate.getTime() };
});

/**
 * registerPinAttempt — mencatat percobaan PIN per uid. Doc ini yang dibaca
 * Firestore Rules sebagai gate. Cooldown 5 menit & maksimal 8 percobaan / jam.
 * Semua pengecekan + penulisan dilakukan di dalam satu transaksi (fail closed).
 */
exports.registerPinAttempt = onCall(async (request) => {
  if (!request.auth) {
    throw new HttpsError('unauthenticated', 'Harus login untuk mencoba PIN.');
  }
  const data = request.data || {};
  const familyId = String(data.familyId || '');
  if (!isValidPinForAttempt(familyId)) {
    throw new HttpsError('invalid-argument', 'PIN harus 6 atau 8 digit angka.');
  }
  const uid = request.auth.uid;
  const db = admin.firestore();
  const ref = db.collection('pinAttempts').doc(uid);
  const now = Date.now();

  await db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    const cur = snap.exists ? (snap.data() || {}) : {};
    const last = Number(cur.lastAttemptAtMillis || 0);
    if (last > 0 && now - last < PIN_ATTEMPT_COOLDOWN_MS) {
      throw new HttpsError('resource-exhausted', 'Coba lagi beberapa saat.');
    }
    let windowStart = Number(cur.windowStartedAtMillis || 0);
    let count = Number(cur.count || 0);
    if (windowStart <= 0 || now - windowStart >= PIN_ATTEMPT_WINDOW_MS) {
      windowStart = now; // jendela baru → reset hitungan
      count = 0;
    }
    if (count >= PIN_ATTEMPT_MAX) {
      throw new HttpsError('resource-exhausted', 'Terlalu banyak percobaan PIN. Coba lagi nanti.');
    }
    tx.set(ref, {
      uid: uid,
      familyId: familyId,
      count: count + 1,
      windowStartedAt: admin.firestore.Timestamp.fromDate(new Date(windowStart)),
      windowStartedAtMillis: windowStart,
      lastAttemptAt: admin.firestore.Timestamp.fromDate(new Date(now)),
      lastAttemptAtMillis: now,
      updatedAt: admin.firestore.FieldValue.serverTimestamp(),
    });
  });
  return { ok: true };
});

/**
 * setWorkspacePlan — satu-satunya jalan mengubah plan workspace. Klien TIDAK
 * boleh menulis field `plan` sendiri (rules ditutup).
 *
 * CATATAN PENTING: entitlement `entitlements/{uid}` (field `active: true` &
 * `plan`) HANYA diberikan oleh backend Play Billing / admin — mekanisme
 * verifikasi pembelian BELUM diimplementasikan di sini. Endpoint ini sengaja
 * FAIL CLOSED: tanpa entitlement yang cocok, permintaan ditolak dengan
 * pesan generik (tidak membocorkan detail akun/plan).
 */
exports.setWorkspacePlan = onCall(async (request) => {
  if (!request.auth) {
    throw new HttpsError('unauthenticated', 'Harus login untuk mengubah paket.');
  }
  const data = request.data || {};
  const familyId = String(data.familyId || '');
  const plan = String(data.plan || '');
  if (!familyId) {
    throw new HttpsError('invalid-argument', 'familyId wajib diisi.');
  }
  if (plan !== PLAN_FREE && plan !== PLAN_PRO) {
    throw new HttpsError('invalid-argument', 'Paket tidak valid.');
  }
  const uid = request.auth.uid;
  const db = admin.firestore();
  const familyRef = db.collection('families').doc(familyId);

  // Cek owner lebih dulu, sebelum entitlement (fail closed, hemat baca).
  const memberSnap = await familyRef.collection('members').doc(uid).get();
  if (!memberSnap.exists || (memberSnap.data() || {}).role !== 'owner') {
    throw new HttpsError('permission-denied', 'Hanya pemilik workspace yang bisa mengubah paket.');
  }

  // Turun ke FREE selalu diizinkan (tidak ada risiko monetisasi & mencegah
  // user terjebak di plan yang tidak bisa diubah). Hanya naik ke PRO yang
  // membutuhkan entitlement terverifikasi.
  if (plan === PLAN_PRO) {
    const entSnap = await db.collection('entitlements').doc(uid).get();
    const ent = entSnap.exists ? (entSnap.data() || {}) : {};
    if (ent.active !== true || ent.plan !== PLAN_PRO) {
      throw new HttpsError('permission-denied', 'Paket tidak tersedia untuk akun ini.');
    }
  }

  try {
    await familyRef.update({ plan: plan });
  } catch (e) {
    logger.warn('setWorkspacePlan gagal update code=' + errDetail(e));
    throw new HttpsError('failed-precondition', 'Workspace tidak ditemukan.');
  }
  return { ok: true };
});

/**
 * approveJoinAtomic — terima anggota dalam SATU transaksi: cek owner, cek
 * kapasitas plan, tulis doc member, hapus join request. Mencegah race yang
 * dulu bisa membuat workspace melebihi kuota (free=2 / pro=6).
 */
exports.approveJoinAtomic = onCall(async (request) => {
  if (!request.auth) {
    throw new HttpsError('unauthenticated', 'Harus login untuk menyetujui anggota.');
  }
  const data = request.data || {};
  const familyId = String(data.familyId || '');
  const requestUid = String(data.requestUid || '');
  if (!familyId || !requestUid) {
    throw new HttpsError('invalid-argument', 'Data tidak lengkap.');
  }
  const uid = request.auth.uid;
  const db = admin.firestore();
  const familyRef = db.collection('families').doc(familyId);
  const membersRef = familyRef.collection('members');
  const requestRef = familyRef.collection('joinRequests').doc(requestUid);

  await db.runTransaction(async (tx) => {
    const mySnap = await tx.get(membersRef.doc(uid));
    if (!mySnap.exists || (mySnap.data() || {}).role !== 'owner') {
      throw new HttpsError('permission-denied', 'Hanya pemilik workspace yang bisa menyetujui.');
    }
    // AggregateQuery (.count()) tidak didukung di Transaction.get() pada Admin
    // SDK — pakai tx.get(collection).size (anggota maks 6, biaya terukur).
    const [famSnap, membersSnap, reqSnap] = await Promise.all([
      tx.get(familyRef),
      tx.get(membersRef),
      tx.get(requestRef),
    ]);
    if (!famSnap.exists) {
      throw new HttpsError('failed-precondition', 'Workspace tidak ditemukan.');
    }
    if (!reqSnap.exists) {
      throw new HttpsError('failed-precondition', 'Permintaan tidak ditemukan.');
    }
    if (membersSnap.docs.some((d) => d.id === requestUid)) {
      throw new HttpsError('failed-precondition', 'Pengguna sudah menjadi anggota.');
    }
    const plan = (famSnap.data() || {}).plan === PLAN_PRO ? PLAN_PRO : PLAN_FREE;
    const limit = plan === PLAN_PRO ? LIMIT_PRO : LIMIT_FREE;
    if (membersSnap.size >= limit) {
      throw new HttpsError('resource-exhausted', 'Workspace sudah penuh.');
    }
    const req = reqSnap.data() || {};
    const member = {
      uid: requestUid,
      name: String(req.name || ''),
      email: String(req.email || ''),
      // Audit: samakan kontrak member doc dengan jalur approve lama — UI
      // membaca label/addedAt/photoUrl.
      label: String(req.name || ''),
      addedAt: admin.firestore.FieldValue.serverTimestamp(),
      role: 'member',
      joinedAt: admin.firestore.FieldValue.serverTimestamp(),
    };
    if (typeof req.photoUrl === 'string' && req.photoUrl.length > 0) {
      member.photoUrl = req.photoUrl;
    }
    if (typeof req.fcmToken === 'string' && req.fcmToken.length > 0) {
      member.fcmToken = req.fcmToken;
    }
    tx.set(membersRef.doc(requestUid), member);
    tx.delete(requestRef);
  });
  return { ok: true };
});

/**
 * leaveWorkspaceAtomic — keluar workspace secara atomik. Owner terakhir tidak
 * boleh keluar (harus ada minimal 1 owner lain) supaya workspace tak yatim.
 */
exports.leaveWorkspaceAtomic = onCall(async (request) => {
  if (!request.auth) {
    throw new HttpsError('unauthenticated', 'Harus login untuk keluar workspace.');
  }
  const data = request.data || {};
  const familyId = String(data.familyId || '');
  if (!familyId) {
    throw new HttpsError('invalid-argument', 'familyId wajib diisi.');
  }
  const uid = request.auth.uid;
  const db = admin.firestore();
  const membersRef = db.collection('families').doc(familyId).collection('members');

  await db.runTransaction(async (tx) => {
    const mySnap = await tx.get(membersRef.doc(uid));
    if (!mySnap.exists) {
      throw new HttpsError('failed-precondition', 'Anda bukan anggota workspace ini.');
    }
    if ((mySnap.data() || {}).role === 'owner') {
      const ownersSnap = await tx.get(membersRef.where('role', '==', 'owner'));
      const otherOwners = ownersSnap.docs.filter((d) => d.id !== uid).length;
      if (otherOwners < 1) {
        throw new HttpsError('failed-precondition', 'Tidak bisa keluar: Anda satu-satunya pemilik workspace.');
      }
    }
    tx.delete(membersRef.doc(uid));
  });
  return { ok: true };
});
