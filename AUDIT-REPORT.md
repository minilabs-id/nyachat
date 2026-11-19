# Nyachat — Laporan Audit Keamanan Final (r1.7.3)

**Tanggal:** 2026-11-19
**Commit:** `62d93af` (r1.7.3) + 3 file dengan perubahan belum di-commit
**Metode:** static review seluruh kode + verifikasi langsung setiap temuan pada catatan WIP (`AUDIT-NOTES.md`, `AUDIT_NOTES.md`). Semua temuan di bawah dikonfirmasi dari sumber (file:line sesuai working tree saat ini).
**Catatan runtime:** Gradle unit test **tidak dijalankan** — environment tidak memiliki `JAVA_HOME`/`java`. Temuan bersifat static-analysis. Perbaikan di working tree juga **belum memiliki unit test**.

---

## Ringkasan eksekutif

| Severity | Jumlah | Status |
|---|---|---|
| HIGH | 4 | semua masih terbuka |
| MED-HIGH | 1 | masih terbuka |
| MEDIUM | 12 | masih terbuka (1 perbaikan parsial) |
| LOW | 12 | masih terbuka |
| INFO | 1 | expected / tidak perlu diubah |

Tiga perbaikan sudah ada di working tree tetapi **belum di-commit dan belum diuji**; dua di antaranya hanya bersifat klien dan **tidak menutup** celah server yang mendasarinya (lihat §D).

Beberapa temuan memang didokumentasikan sebagai trade-off yang disengaja di komentar rules (PIN squatting `:48-52`, owner self-delete `:119-124`, `sender` tidak di-bind `:185-196`). Itu tetap merupakan kelemahan keamanan nyata, bukan sekadar kesalahan implementasi.

---

## A. Temuan HIGH

### H1. Key-wrap E2EE bisa ditimpa anggota mana pun → key-substitution MITM + DoS permanen
**Lokasi:** `firestore.rules:272-278`, `E2eeSyncManager.kt:245-275` (khusus `:260-263`, `:274-275`, `:293`)

Rules mengizinkan **semua anggota** `create`/`update` pada `families/{fid}/e2eeKeys/{uid}` untuk UID arbitrer — hanya ada validasi tipe/ukuran string, **tanpa** `uid == request.auth.uid`, tanpa owner check, tanpa binding versi/pubkey (path variabel `{uid}` tidak pernah di-bind ke `request.auth.uid`, ke pubkey/versi member doc, atau ke integrity tag):

```
allow create, update: if signedInGoogle() && isMember(familyId)
  && request.resource.data.e2eeKeyBytes is string
  && request.resource.data.e2eeKeyBytes.size() > 0
  && request.resource.data.e2eeKeyBytes.size() <= 5000;
allow delete: if false;                                   // :278
```

Self-heal hanya menulis ulang wrap bila **versinya berbeda** (`needsRewrap = wrapVersion != memberVersion`, `:274-275`). Penyerang dengan demikian dapat:
1. Menulis wrap dari **grup key miliknya sendiri** ke pubkey korban (pubkey tersedia terang-terangan di member doc) dengan versi yang **masih cocok** → korban berhasil unwrap lalu enkripsi dengan kunci penyerang → **MITM**, penyerang membaca pesan korban sementara anggota lain mengalami kegagalan dekripsi.
2. Menulis ciphertext acak dengan versi cocok → korban gagal unwrap.

**Amplifier kegagalan recovery:** `fetchAndUnwrapGroupKey()` memanggil `syncPubKeyToMemberDoc()`, tetapi fungsi itu **return lebih awal** bila pubkey di member doc sudah sama dengan lokal (`:293`), sehingga versi tidak naik → self-heal terus me-skip wrap yang rusak. Klien juga **dilarang menghapus** wrap (`:278`). Hasilnya DoS permanen sampai intervensi manual.

**Perbaikan di working tree tidak cukup:** `E2eeSyncManager.kt:254-256` kini membatasi wrap lintas-anggota hanya untuk owner di sisi **klien**, tetapi rules masih mengizinkan siapa pun → penyerang dengan klien termodifikasi tetap bisa menulis. Komentar kode ("matching the Firestore authorization rule") **keliru**.

**Fix:** (1) rules: `request.auth.uid == uid` untuk self-wrap + jalur owner/server untuk lintas-wrap; (2) monoton `e2eeKeyVersion`; (3) integrity tag/AAD yang mengikat wrap ke `familyId|recipientUid|hash(pubkey)|version`; (4) paksa bump versi (regenerasi keypair) pada setiap unwrap gagal; (5) izinkan owner/klien menghapus wrap stale.

---

### H2. Tidak ada rotasi grup key saat anggota dikeluarkan (tanpa forward secrecy)
**Lokasi:** `MembershipManager.kt:1066-1072` (`removeMember` hanya `delete()` member doc), `MembershipManager.kt:1151` (`leaveWorkspace` hanya `selfRef.delete()`), `E2eeSyncManager.kt:59-62` (komentar mengakui "butuh fitur rotate key, di luar scope r1.7.x"), `firestore.rules:278` (wrap mantan anggota tidak bisa dihapus).

Tidak ada jalur rotasi kunci di seluruh aplikasi. Mantan anggota tetap menyimpan AES grup key dan ciphertext yang pernah ia akses; bila di-approve ulang dengan perangkat sama, kunci lama masih valid.

**Mitigasi parsial di working tree:** `MainActivity.kt:708,751` memanggil `E2eeSyncManager.clearLocalKeys()` saat **keluar/di-kick** (ditambahkan di `E2eeSyncManager.kt:137-155`). Ini hanya membersihkan salinan lokal **di perangkat sendiri** — `removeMember` tidak memanggilnya, jadi kunci di perangkat mantan anggota tidak pernah di-invalidate.

**Fix:** rekey-on-revoke via Cloud Function (Admin SDK): grup key baru di-generate saat removal, di-wrap ulang hanya ke anggota tersisa, hapus `e2eeKeys/{uid}` mantan anggota, dan beri epoch pada pesan agar ciphertext dengan kunci lama ditolak.

---

### H3. `setPlan` membuka Pro tanpa verifikasi pembayaran
**Lokasi:** `MembershipManager.kt:1048-1060`, `firestore.rules:60-61`, `ManageMembersScreen.kt:372-391`, `functions/index.js:518`

`plan` adalah **satu-satunya** input batas anggota (free=2 → pro=6). Owner cukup mengetuk "Upgrade" di UI; klien langsung `update(plan="pro")`, dan rules mengizinkan owner menulis `plan` langsung. Cloud function `handleJoinRequest` hanya **membaca** `plan`. Komentar kode menyebut ini "PLACEHOLDER — beta", tetapi tetap jalur monetisasi yang bisa dibypass.

**Fix:** hapus `plan` dari field yang bisa ditulis klien di rules; jadikan perubahan plan server-authoritative lewat callable yang memverifikasi purchase token Play Billing (Google Play Developer API / RTDN).

---

### H4. Auto-update memasang APK tanpa verifikasi checksum/tanda tangan
**Lokasi:** `GitHubUpdateChecker.kt:36-37,66,74,111-145`, `MainOverlays.kt:165-173`, `MainDialogs.kt:410-418`, `AndroidManifest.xml:5`, `Constants.kt:63`

APK diunduh dari `browser_download_url` yang **diterima apa adanya** dari response API GitHub (tanpa allowlist host, OkHttp mengikuti redirect secara default, tanpa certificate pinning), lalu langsung diberikan ke installer. Tidak ada `MessageDigest`, tidak ada pengecekan signing certificate. Versi juga diturunkan dari `tag_name` yang dikontrol pihak luar. Jika akun/repo/release GitHub (atau jalur jaringan) dikompromi, malicious APK terpasang dengan satu ketukan.

**Fix:** sebelum install, verifikasi SHA-256 terhadap digest yang dikirim bersama aplikasi bertanda tangan, atau verifikasi fingerprint sertifikat penandatanganan via `getPackageArchiveInfo(..., GET_SIGNING_CERTIFICATES)`; pin host `github.com`/`api.github.com` dan tolak redirect lintas host; gunakan distribusi bertanda tangan/Play App Update.

---

## B. Temuan MEDIUM

### M1. Aktivasi E2EE non-atomik → split-brain group key (MED-HIGH)
`E2eeSyncManager.kt:211-217` menyimpan grup key ke perangkat **sebelum** menulis marker, dengan `set()` tanpa transaksi/CAS; `ensureActivation()` (`:200-208`) cek `exists()` lalu buat — TOCTOU. Dua owner dapat membuat grup key berbeda. Yang kalah tetap menyimpan kunci lokalnya dan melakukan `selfHealWraps()` dengan kunci salah → workspace terbelah. Rule `firestore.rules:265` menolak update marker, tetapi kunci lokal sudah terlanjur disimpan.
**Fix:** marker sebagai "claim" atomik (transaction/create dengan owner uid); grup key dihasilkan **setelah** klaim menang; validasi ulang owner marker sebelum wrap.

### M2. Marker aktivasi E2EE bisa dibuat anggota biasa
`firestore.rules:261-266`: `create` hanya butuh `isMember` + `activated is timestamp` (bukan `request.time`, bukan owner). Wildcard `{markerDoc}` juga mengizinkan marker tambahan. Klien membatasi activation pada owner (`E2eeSyncManager.kt:206-207`), tetapi aturan server tidak. Anggota bisa membuat marker lebih dulu → owner mengira E2EE aktif tetapi tidak punya wrap → semua pengirim masuk mode `BLOCK` tanpa henti.
**Fix:** `create` hanya `isOwner(familyId)` + `activated == request.time`; path literal `e2ee/e2ee`.

### M3. Riwayat pesan & transaksi bisa ditimpa/dihapus anggota mana pun
`firestore.rules:231-241` (messages update/delete), `:323-331` (transactions update/delete): hanya `isMember`. Rule hanya memeriksa **bentuk dokumen baru** — tanpa perbandingan ke `resource.data`, tanpa `diff().affectedKeys()`, tanpa jendela edit. Field `senderUid` bersifat opsional; bila ada, harus sama dengan pemanggil — sehingga penyerang cukup menulis ulang pesan orang lain dengan `senderUid`-nya sendiri. Anggota dapat mengubah atau menghapus pesan/catatan keuangan siapa pun. (Komentar rules `:185-196` mengakui ini sebagai trade-off yang disengaja.)
**Fix:** bind update ke `'senderUid' in resource.data && resource.data.senderUid == request.auth.uid`, jendela edit terhadap `resource.data.timestamp`, `diff().affectedKeys().hasOnly(...)`; simpan `authorUid` pada transaksi; delete hanya penulis/owner + catatan audit.

### M4. Receipt bisa dipalsukan dengan UID arbitrer
`firestore.rules:287-298`: syarat `request.auth.uid in deliveredBy` **tidak** memvalidasi elemen array lain (bukan uid, bisa string sembarang / uid palsu), tidak ada `hasOnly` uid, tidak ada batas ukuran, dan tidak ada cek bahwa dokumen `cloudId` benar-benar ada. Anggota bisa menyuntik uid ke `readBy` untuk memalsukan status baca dan membuat array tumbuh tak terbatas. Lebih parah: rules hanya membandingkan `size()` (`resource == null || ...size() >= resource.data...size()`), sehingga penyerang bisa **mengganti elemen yang sudah ada** dengan uid/string lain selama ukuran tidak menyusut — menggagalkan maksud "grow-only / anti-hapus" yang tertulis di komentar `:285-286`. Juga tidak ada batas panjang array (DoS penyimpanan).
**Fix:** gunakan map yang di-key uid, bukan array; atau minimal `readBy.hasAll(resource.data.readBy)` (melarang penghapusan elemen), `size() <= N`, dan setiap uid baru harus sama dengan `request.auth.uid`. Status baca yang tepercaya sebaiknya ditulis Cloud Function.

### M5. Enumerasi workspace via PIN tanpa pembatasan server
`PinAttemptLimiter.kt:12-42` hanya helper in-memory di Compose state (`PinConnectScreen.kt:485-500`), bypassable dengan reinstall/perangkat lain/klien termodifikasi. `firestore.rules:158-176` hanya cek eksistensi family + jendela 24 jam + uid request, **tanpa** cap percobaan per uid lintas family. Tidak ada App Check. Setiap tebakan yang benar membuat request doc dan memicu FCM spam ke owner.
**Fix:** counter percobaan server-side transaksional (`pinAttempts/{uid}`) yang dicek di rules; aktifkan App Check; batasi joinRequest per uid per jam + backoff.

### M6. TOCTOU pada hitungan anggota dan owner terakhir
`MembershipManager.kt:948-1016` mengecek jumlah anggota **sebelum** transaksi (komentar `:954-957` mengakuinya); `MembershipManager.kt:1117-1157` menghitung owner lain secara non-atomik (komentar `:1124-1128`). Dua `approveJoin` bersamaan di batas → kelebihan kapasitas; dua owner terakhir keluar bersamaan → workspace yatim.
**Fix:** counter `memberCount`/`ownerCount` transaksional pada dokumen family (`FieldValue.increment` + pengecekan limit di dalam transaksi), atau pindahkan ke Cloud Function Admin SDK.

### M7. Invariants "1 akun = 1 workspace" hanya di klien
`MembershipManager.kt:750-770,240-243` melakukan pengecekan `collectionGroup`, tetapi `firestore.rules:48-56` mengizinkan unlimited family dengan `ownerId` sama. Klien termodifikasi bisa membuat workspace sebanyaknya. (PIN squatting juga memang diizinkan rules `:50-52`, diperparah M5.)
**Fix:** enforcement server-side via callable/counter doc yang dicek di rules.

### M8. Rate limit `aiComplete` per-instance, tanpa kuota harian/global
`functions/index.js:143-148` (Map in-memory, komentar `:141` mengakui "abuse lintas-instance masih mungkin"), `:175-177`, image 3 MB di `:146`, fan-out hingga 8 penyedia per request di `:197-221`. Cold start mereset Map; N instance → ~N×30 panggilan/menit/uid. Tidak ada daily/global cap; image 3 MB dikirim ke setiap model.
**Fix:** counter Firestore durable (`FieldValue.increment` + TTL) per uid **dan** global; kuota harian; cost guard untuk image (batasi 1–2 model saat ada gambar).

### M9. Jendela downgrade plaintext saat startup
`E2eeSyncManager.kt:74,80-82` status awal `false`, baru aktif di `start()` → `ensureActivation()` (`:112,200-205`). `SyncLifecycle.kt:91,100` menjalankan `startCloudSync()` **sebelum** `E2eeSyncManager.start()`. `FirestoreSyncManager.kt:137-141` memetakan `active == false` ke `SendMode.PLAINTEXT`, dan guard UI `ChatScreen.kt:737` hanya menangani `active && !ready` — bukan `active == false`. Pesan yang dikirim di jendela ini tersimpan sebagai LEGACY plaintext (`:1099-1115`).
**Fix:** status tiga-state (UNKNOWN/ACTIVE/INACTIVE); baca marker sebelum sync aktif; gate semua kirim sampai status E2EE terselesaikan.

### M10. Debug APK diterima sebagai kandidat update
`GitHubUpdateChecker.kt:68` (`releaseApk ?: debugApk`) dan `:74`. Debug keystore dikomit dengan password standar `android` (`app/build.gradle.kts:53-59`, `.gitignore:22-24` secara sengaja tidak mengabaikannya), sehingga siapa pun bisa menandatangani APK debug.
**Fix:** hanya terima aset release-signed; fail closed bila tidak ada (digabung dengan H4).

### M11. `writeAck` menimpa `readBy` menjadi array kosong → ACK berikutnya ditolak rules (bug fungsional)
`FirestoreSyncManager.kt:827-838`: `writeAck()` menulis `RECEIPT_READ_BY to emptyList<String>()` dengan `SetOptions.merge()`. Rule grow-only `firestore.rules:295-296` mensyaratkan `readBy.size()` tidak menyusut. Begitu ada anggota yang `markRead()` (`:419-432`, `arrayUnion(uid)`), ACK dari anggota lain akan **ditolak** → `deliveredBy` di receipt berhenti bertambah → status ✓✓/pelangi tidak akurat (dokumen `deliveries/{uid}` tetap tertulis sehingga cleanup masih jalan).
**Fix:** jangan tulis `readBy` di `writeAck`; gunakan `arrayUnion(uid)` saja, atau hapus field tersebut dari payload.

### M12. Siapa pun bisa mengklaim PIN (PIN squatting)
`firestore.rules:53-56`: document id = PIN, dan `create` hanya butuh `signedInGoogle()` + `ownerId == request.auth.uid`. Akun Google mana pun dapat mengklaim PIN yang belum terpakai dengan dirinya sebagai owner. Jika penyerang mendahului pemilik sah (atau mengklaim setelah PIN dihapus), anggota yang bergabung ke PIN tersebut masuk ke workspace penyerang yang memiliki akses penuh ke pesan, kunci, dan data anggota. Komentar rules `:48-52` mengakui trade-off ini. Diperparah M5 (brute-force PIN tanpa throttle).
**Fix:** buat family via Cloud Function tepercayaan dengan PIN yang di-generate server dan pembuatan doc owner secara atomik; hapus `create` langsung dari rules atau gate dengan token klaim dari server.

---

## C. Temuan LOW / INFO

| ID | Temuan | Lokasi | Fix singkat |
|---|---|---|---|
| L1 | Wrap & konten tanpa AAD/context binding (tetap bisa di-swap antar dokumen/workspace; dampak replay dibatasi keypair per-workspace) | `WorkspaceCrypto.kt:80-89,105-123,155-159`; `E2eeSyncManager.kt:381-383` | `updateAAD(familyId\|uid\|hash(pubkey))` dan `cloudId\|familyId\|senderUid\|msgVersion` |
| L2 | SecureStorage: alias master tunggal app-wide, `setUserAuthenticationRequired(false)`, `String(plaintext)` default charset, tanpa zeroisasi | `SecureStorage.kt:28,45,74,103` | UTF-8 eksplisit, `fill(0)` di `finally`, alias per-purpose |
| L3 | Metadata nonce FCM (nama pengirim, senderUid, cloudId) bocor ke Google meski isi E2EE; join request mengirim email PII | `functions/index.js:305,312,326-330,256,531-537` | payload opaque, resolve identitas lokal, hapus `requesterEmail` |
| L4 | Kunci API Gemini di query string URL (server & BYOK klien) — bisa masuk access log proxy | `functions/index.js:111-113`; `GeminiService.kt:828` | header `x-goog-api-key` |
| L5 | Hardening functions: log error object mentah, fallback `readSecret` ke env var, Map key leak, batch cleanup tanpa chunk 500 | `functions/index.js:207,219,68-77,148-158,275-285` | log string sanitized, fail-closed, evict bucket, chunk batch |
| L6 | Ruang PIN sebenarnya `9×10^7` (digit pertama 1–9, bukan `10^8`); PIN legacy 6 digit masih diterima | `Constants.kt:272-275`; `PinConnectScreen.kt:494-499,543-548` | koreksi dokumentasi, tolak 6 digit setelah cutoff + rate limit (M5) |
| L7 | Cooldown join-request 5 menit hanya pada `update`; delete+create mengabaikannya | `firestore.rules:168-174`; `MembershipManager.kt:863-876` | simpan "last request" di doc yang tidak bisa dihapus requester |
| L8 | Kunci privat/grup berupa material software-exportable (PKCS#8→Base64) dan tidak di-zeroisasi saat stop | `E2eeSyncManager.kt:281-284,311-315,75`; `WorkspaceCrypto.kt:57-61` | AndroidKeyStore non-exportable + zeroisasi ByteArray |
| L9 | Field `uid` pada member doc tidak di-bind ke path `{uid}` saat create (berbeda dengan joinRequests), sehingga owner bisa membuat doc dengan uid mismatch → inkonsistensi/cross-read | `firestore.rules:71-74`; `:141` | tambahkan `request.resource.data.uid == uid` pada member create |
| L10 | Storage: `fileName` tidak di-bind ke message `cloudId`/uploader; `delete` foto boleh oleh anggota mana pun; `imageUrl` di Firestore hanya divalidasi `is string` | `storage.rules:13-33`; `firestore.rules:229-230,239-240` | bind path ke doc pesan & uploader; samakan author check untuk delete |
| L11 | `deliveries/{uid}` delete tidak memeriksa `isMember` (create/update memeriksanya) — mantan anggota masih bisa hapus ACK-nya | `firestore.rules:255` | tambahkan `&& isMember(familyId)` |
| L12 | Debug keystore dikomit dengan password standar `android`; sengaja tidak diabaikan `.gitignore` | `app/build.gradle.kts:53-59`; `.gitignore:22-24` | terima hanya untuk debug; pastikan debug build tidak pernah dipakai sebagai update (M10) atau didistribusikan |
| I1 | Firebase API key di `google-services.json:31` | expected by design | Pastikan dibatasi ke package `com.startupmini.nyachat` + SHA-1 di Firebase Console |

---

## D. Status perbaikan di working tree (belum di-commit)

| Perubahan | Lokasi | Penilaian |
|---|---|---|
| Paginasi `deleteDocsAbsentFrom` (`limit(500)` + loop + early break + batas 1000 halaman) | `FirestoreSyncManager.kt:1399-1433` | **Fix valid.** Temuan lama "no pagination" (AUDIT_NOTES #4) kini **FALSE**. |
| `clearLocalKeys()` dipanggil saat kick/leave | `MainActivity.kt:708,751`; `E2eeSyncManager.kt:137-155` | Mitigasi **parsial** H2 (hanya perangkat sendiri). |
| `selfHealWraps` hanya wrap lintas-anggota untuk owner | `E2eeSyncManager.kt:254-256` | **Tidak cukup** untuk H1 — rules belum diubah; penyerang pakai klien termodifikasi. Juga tidak ada unit test. |

Tidak ada unit test baru untuk `clearLocalKeys`, pembatasan wrap, maupun paginasi. Gradle tidak bisa dijalankan di environment ini (`JAVA_HOME` tidak diset) → **test belum terverifikasi**.

---

## E. Area yang lolos audit (positif)

- **Manifest/konfigurasi:** `allowBackup=false`, `fullBackupContent=false`, `data_extraction_rules.xml` mengecualikan cloud backup & device transfer untuk semua domain; cleartext traffic tidak diizinkan; tidak ada `debuggable`/`taskAffinity`; FCM service & FileProvider `exported=false` dengan path set minimal (`AndroidManifest.xml:5,17-19,27-50`, `file_paths.xml:3-5`, `data_extraction_rules.xml:9-22`).
- **Storage rules:** akses foto keluarga hanya anggota, catch-all deny, batas 5 MB, contentType `image/*`, `{fileName}` single-segment (no `**` traversal) (`storage.rules:13-38`).
- **Build:** R8 minify + resource shrink aktif di release; tidak ada API key AI yang terkompilasi; signing password dari env var (`app/build.gradle.kts:33-36,41-45,63-68`).
- **Backup:** AES-256-GCM + PBKDF2 600k iterasi + AAD + salt/IV acak + validasi KDF/cipher + batas iterasi dekripsi 10M — solid (`BackupCrypto.kt:31-128`).
- **Kripto:** tidak ada IV/nonce reuse (SecureRandom per operasi, IV digabung di `encryptBytes`), GCM tag 128-bit, dekripsi fail-closed ke `null`, HKDF-SHA256 struktural benar, tidak ada plaintext/kunci di log.
- **Cloud Functions:** `aiComplete` mewajibkan `request.auth`; validasi prompt kosong/panjang & ukuran image; secrets milik server tidak pernah dikembalikan ke klien; tidak ada prompt/output AI di log; tidak ada ReDoS/injection/path traversal; error ke klien bersifat generik.
- **Tidak ada secret sensitif bocor:** `.env.example` hanya placeholder; `.gitignore` menutup `.env`, keystore, kredensial.

---

## F. Prioritas remediasi

**P0 — sebelum produksi:** H1 (binding uid + versi + AAD pada e2eeKeys), H2 (rotasi kunci saat revoke), H3 (plan server-authoritative), H4 (verifikasi tanda tangan APK).

**P1:** M1–M2 (aktivasi E2EE atomik & owner-only), M3 (author binding pesan/transaksi), M4 (validasi elemen receipt), M5 (rate limit PIN server + App Check), M6 (counter transaksional), M8 (rate limit AI durable), M9 (gating plaintext startup), M11 (perbaikan `writeAck`), M12 (klaim PIN via server).

**P2:** M7, M10, L1–L12, I1 (verifikasi pembatasan API key), tambah unit test untuk semua perbaikan, lalu jalankan `./gradlew test` di environment dengan JDK.

---

## G. Status implementasi perbaikan (setelah audit)

Seluruh temuan HIGH/MED-HIGH/MEDIUM/LOW telah diberi perbaikan di working tree. Ringkasan:

| ID | Perbaikan yang diterapkan | Status |
|---|---|---|
| H1 | `firestore.rules`: `e2eeKeys` di-bind ke `uid == auth.uid` **atau** owner + `e2eeKeyVersion` non-negatif & monoton + izin hapus wrap stale. Klien: unwrap gagal → rotasi keypair paksa + bump versi + hapus wrap lama (`E2eeSyncManager.forceKeyPairRotation`). | Selesai (rules + klien) |
| H2 | `E2eeSyncManager.rotateGroupKey()` owner-only, `keyEpoch` marker dinaikkan, wrap ditulis ulang ke semua member; aturan marker mengizinkan update `keyEpoch` oleh owner saja; listener marker mendeteksi perubahan epoch & mengambil ulang kunci di semua perangkat; `removeMember` memicu rotasi. | Selesai |
| H3 | `plan` dihapus dari field yang bisa ditulis klien di rules; `setPlan` kini memanggil callable `setWorkspacePlan` (butuh entitlement, fail-closed); UI menampilkan Toast bila ditolak. | Selesai (butuh backend entitlement untuk produksi) |
| H4 | `GitHubUpdateChecker`: verifikasi SHA-256 sertifikat penandatanganan APK sebelum install (fail-closed), allowlist host HTTPS, redirect tidak mengikuti host luar, hanya aset `release`. | Selesai (butuh `-PapkCertSha256=<fingerprint>` saat build release) |
| M1/M2 | Marker `e2ee/e2ee` literal, create owner-only + `activated == request.time`; klaim atomik `create()` sebelum grup key dibuat; cascade TOCTOU dicegah CAS. | Selesai |
| M3 | Update pesan di-bind ke `senderUid` asli/owner + jendela 15 menit + `affectedKeys` (konversi epoch millis→detik sudah benar); transaksi update/delete owner-only. | Selesai (perilaku edit transaksi non-owner berubah) |
| M4 | Receipt: elemen string, maks 100, `hasAll` + pertumbuhan ≤1 per write; `writeAck` tidak lagi menimpa `readBy` (M11). | Selesai |
| M5/L7 | Callable `registerPinAttempt` (cooldown 5 mnt, maks 8/jam) + `pinAttempts/{uid}` yang tidak bisa ditulis klien; rules `joinRequests` membaca counter. | Selesai (cooldown hanya di callable — pengecekan ganda di rules dihapus agar tidak menolak semua join) |
| M6 | Callable `approveJoinAtomic` & `leaveWorkspaceAtomic` melakukan pengecekan kapasitas/owner di dalam transaksi Admin SDK. | Selesai |
| M7/M12 | `reservePin` menerbitkan `pinClaims` (token, expiry); rules create family mewajibkan `claimToken` yang cocok. | Selesai |
| M8 | `aiComplete` memakai counter Firestore durable (per-menit/per-hari/global) + guard image 1,5 MB + fan-out maks 2 model saat ada gambar. | Selesai (perlu TTL policy untuk doc `rateLimits`) |
| M9 | Status E2EE tiga-state (UNKNOWN/ACTIVE/INACTIVE); `currentSendMode()` → BLOCK saat UNKNOWN; E2EE dimulai & status diselesaikan sebelum cloud sync. | Selesai |
| M10/L12 | Fallback debug APK dihapus; permission install diberi komentar pembatas; debug keystore tetap debug-only. | Selesai |
| M11 | `writeAck` tidak lagi menulis `readBy=[]`. | Selesai |
| L1 | AAD context/content tersedia & **dipakai** pada enkripsi/dekripsi pesan & transaksi; fallback legacy otomatis. | Selesai (pesan ber-flag `.a1` tidak bisa dibaca versi app lama sebelum update — perlu rollout bersamaan) |
| L2/L8 | SecureStorage UTF-8 eksplisit + zeroisasi; kunci & material kripto di-zero saat stop; alias Keystore sengaja tidak diubah (kompatibilitas). | Selesai |
| L3 | Payload FCM hanya `{type, cloudId, familyId, senderUid}`; nama pengirim & pemohon di-resolve lokal dari Firestore; email dihapus. | Selesai |
| L4 | Kunci Gemini (server & BYOK) pindah ke header `x-goog-api-key`. | Selesai |
| L5 | Logger sanitized, `readSecret` fail-closed di produksi, Map in-memory dihapus, cleanup token di-chunk 500. | Selesai |
| L6 | PIN 8 digit penuh `SecureRandom` (tanpa batasan digit pertama); PIN < 8 ditolak; dokumentasi ruang kunci dikoreksi. | Selesai |
| L9/L10/L11 | `uid` member di-bind ke path; storage path `{cloudId}.jpg` + doc pesan harus ada + metadata `uploaderUid`; `deliveries` delete wajib member. | Selesai |

**Langkah wajib sebelum deploy:**
1. Build release harus diberi `-PapkCertSha256=<SHA-256 sertifikat release>`; tanpa itu update in-app ditolak (disengaja).
2. Berikan entitlement melalui `entitlements/{uid}` (Admin SDK / backend Play Billing) agar `setWorkspacePlan` bisa dipakai.
3. Aktifkan TTL Firestore untuk koleksi `rateLimits` agar tidak tumbuh tanpa batas.
4. Deploy aturan: `firebase deploy --only firestore:rules,storage,functions`.
5. Jalankan `./gradlew test` di environment dengan JDK — **belum bisa dilakukan di sesi ini** (`JAVA_HOME` tidak tersedia). Unit test untuk perbaikan baru juga belum ditambahkan.

---

## H. Perbaikan hasil review integrasi (putaran kedua)

Review menyeluruh terhadap seluruh diff menemukan dan saya perbaiki:

| Masalah | Perbaikan |
|---|---|
| **Blocker kompilasi** — `MainActivity.kt` merujuk 4 properti `dialogs.*` yang tidak ada di `MainDialogController` | Diganti ke `connectGate`, `showManageMembers`, `workspaceChoices` yang benar |
| **Transaksi terenkripsi hilang semua** — AAD `loggedBy` tidak pernah ditulis ke Firestore | `LOGGED_BY` + `AUTHOR_UID` ditambahkan ke metadata transaksi; konstanta `AUTHOR_UID` ditambahkan |
| **ACK receipt ditolak rules** — `readBy` wajib `list` padahal `writeAck` tidak menulisnya | Rules mengizinkan `readBy` absen, tetap memvalidasi grow-only saat ada |
| **Upload foto selalu ditolak** — rules CREATE mensyaratkan doc pesan yang belum ada (klien upload lebih dulu) | Syarat `firestore.exists` dicopot dari CREATE (tetap di READ); CREATE dijaga membership + pola nama + `uploaderUid` metadata |
| **Rotasi kunci race** — epoch dinaikkan sebelum wrap ditulis → perangkat lain menyimpan kunci lama | Urutan dibalik: semua wrap ditulis dulu, baru `keyEpoch` dinaikkan |
| **Nama pemohon join hilang dari notifikasi** | Callable mengirim `familyId`; klien resolve nama dari `joinRequests/{uid}` (nama/email tetap tidak lewat FCM) |
| **`approveJoinAtomic` kehilangan field member** | Menyalinkan `photoUrl`, `label`, `addedAt` dari request |
| **6-digit workspace lama tidak bisa digabung** | `isValidPinForAttempt` (6/8 digit) untuk `registerPinAttempt`; UI join menerima ≥6; create tetap 8 |
| **Pro upgrade mustahil total** | Downgrade ke `free` kini selalu diizinkan; hanya naik ke `pro` yang butuh entitlement |
| **Jendela edit 15 menit memutus edit sah** | Dilebarkan ke 24 jam (tetap membatasi tamper histori lama) |
| **`readSecret` masih fallback env saat secret bernilai string kosong** | Fail-closed juga untuk nilai kosong di produksi |
| **FCM chat kehilangan `body`** | Klien memakai string default `chat_notif_default_body` (sudah ada) |

`git diff --check` bersih; `node --check functions/index.js` lolos. Gradle tetap belum bisa dijalankan (tanpa JDK).

Laporan UI/UX terpisah: **`UIUX-AUDIT-REPORT.md`** (61 temuan: 8 HIGH, 30 MED, 23 LOW; 3 regresi dari perbaikan keamanan sudah saya tangani).
