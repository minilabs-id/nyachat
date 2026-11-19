# Catatan Audit Nyachat (WIP)

Status: t2 (inspeksi) — berjalan. Semua temuan di bawah sudah dibaca langsung dari sumber.
Item bertanda [VERIFIKASI] belum dikonfirmasi dan tidak boleh dilaporkan sebagai temuan.

## Terverifikasi

### 1. setPlan placeholder tanpa verifikasi pembayaran
`MembershipManager.kt:1054-1060` — `setPlan(pin, plan)` langsung `update(plan=pro)`
dari klien, tanpa cek Play Billing/server. Owner bisa membuka cap anggota pro
(free=2 → pro=6) tanpa bayar. Komentar menyebut "PLACEHOLDER — beta".
Dampak: bypass monetisasi + batas anggota. Kelas: business-logic/security.

### 2. Tidak ada rotasi grup key saat anggota dikeluarkan
`MembershipManager.kt:1066-1072` (`removeMember` hanya `delete()` doc member) dan
`leaveWorkspace` (`:1151`). `E2eeSyncManager` tidak punya jalur rotasi kunci
(hanya `wrapForNewMember`, `selfHealWraps`, `activateNow` sekali).
Akibat: mantan anggota tetap memegang grup key lama; data terenkripsi yang masih
bisa ia akses (backup lokal, atau bila di-approve lagi) dapat didekripsi. Tidak ada
forward secrecy pasca-revoke. Kelas: kriptografi/keanggotaan.

### 3. TOCTOU yang sudah didokumentasikan kode (perlu dilaporkan sebagai risiko sisa)
- `MembershipManager.kt:955-982` — cek jumlah anggota SEBELUM transaksi; dua
  `approveJoin` konkuren di ambang batas bisa melebihi limit plan.
- `MembershipManager.kt:1120-1157` — hitung owner lain tidak atomik; dua owner
  terakhir keluar bersamaan → workspace yatim (tanpa owner).

### 4. deleteDocsAbsentFrom tanpa pagination
`FirestoreSyncManager.kt:1403-1413` — `ref.get()` menarik SEMUA dokumen sekaligus
(beda dengan `deleteCollectionInBatches` yang pakai `limit(500)`). Pada koleksi
besar, restore backup berisiko memori/`RESOURCE_EXHAUSTED`; kegagalan membuat
dokumen lama bertahan di cloud (data "hidup lagi" di perangkat lain).

## Temuan tambahan terverifikasi

### 5. Anggota dapat merusak key-wrap E2EE anggota lain
`firestore.rules:272-278` mengizinkan SEMUA anggota create/update `e2eeKeys/{uid}`
untuk UID arbitrer; rules hanya validasi string nonkosong <=5000, bukan penulis
UID sendiri/owner maupun versi pubkey yang sesuai. `E2eeSyncManager.kt:226-245`
self-heal hanya me-rewrap bila versi key-wrap berbeda dari versi pubkey. Jadi
anggota berbahaya bisa overwrite wrap valid target dengan ciphertext acak sambil
mempertahankan versi yang cocok; self-heal akan skip dan target gagal unwrap.
Ini serangan denial-of-service akses E2EE kepada anggota sasaran sampai wrap
berubah/recovery manual. Severity: Medium (integritas/availability).

## Pemeriksaan sudah ditutup tanpa isu tambahan
- firestore.rules: plan hanya bisa diubah owner, tetapi tetap bisa set `pro` langsung
  (konsisten dengan temuan placeholder billing); pesan & Storage aksesnya hanya anggota.
- GitHubUpdateChecker: APK diunduh dari URL API GitHub dan langsung diteruskan ke
  FileProvider installer (`MainOverlays.kt:165-173`, `MainDialogs.kt:410-417`);
  tidak tampak validasi checksum/signature aplikasi sebelum install. Jalur distribusi
  update bergantung pada keamanan akun/repo/release GitHub dan transport TLS.
- BackupCrypto: salt 16 byte dan IV GCM 12 byte acak per backup; format/KDF/cipher
  divalidasi, iterasi dibatasi maks 10M; tidak ada isu jelas dari bagian yang dibaca.
- SecureStorage: secret tersimpan AES-GCM di Android Keystore; PIN tidak terlihat
  disimpan plaintext di file yang diperiksa.

## Pemeriksaan runtime
- Belum dijalankan: Gradle unit tests. Wrapper ada (`gradlew`, `gradlew.bat`), tetapi
  shell tools saat sesi ini ditolak oleh environment. Perlu dijalankan di lingkungan
  repo untuk verifikasi otomatis.

