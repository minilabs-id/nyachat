# Nyachat — Audit UX Putaran 2 (pasca-perbaikan)

**Tanggal:** 2026-11-19
**Konteks:** audit segar terhadap working tree **setelah** seluruh perbaikan keamanan dan UI/UX putaran 1 diterapkan. Tiga lensa: alur pengguna end-to-end, aksesibilitas mendalam, konsistensi interaksi.
**Metode:** static review. Gradle/emulator tidak dijalankan (tidak ada JDK). Estimasi kontras dihitung manual dari token warna.
**Temuan dide-duplikasi** bila muncul di lebih dari satu lensa.

---

## Ringkasan eksekutif

| Severity | Jumlah | Fokus |
|---|---|---|
| HIGH | 10 | handoff notifikasi, upgrade buntu, feedback sukses palsu, gate TalkBack, reduced-motion, 3 kanal snackbar |
| MED | 28 | dialog state rotasi, timeout gate, result write, fokus/IME, kontras, aksesibilitas semantics |
| LOW | 18 | terminologi, token desain, emoji, casing, layout 320dp |

**Temuan paling berdampak:** tap notifikasi tidak pernah mengarah ke percakapan yang benar (R2-H1), dan tombol "Upgrade Sekarang" adalah jalan buntu karena tidak ada alur pembelian (R2-H2). Keduanya adalah dead-end perjalanan pengguna.

---

## A. Dead-end & kejujuran sistem (HIGH)

### R2-H1. Tap notifikasi tidak mengarah ke chat/workspace yang benar
`ChatMessageFirebaseService.kt:126-132`, `MainActivity.kt:100-104,121-126,179,964-968,1073-1092`

Skenario: anggota menerima notifikasi chat workspace A → app background/mati → tap notifikasi. `PendingIntent` hanya membuka `MainActivity` **tanpa extra** (`familyId`, `cloudId`, `requesterUid`). `MainActivity` **tidak meng-override `onNewIntent`** dan tidak membaca extras di `onCreate`. Jika activity masih hidup (`SINGLE_TOP`), tap = **no-op total**; jika dibangun ulang, user mendarat di tab default. Notifikasi `join_request` juga tidak membuka Kelola Anggota.

**Fix:** kirim extra di `PendingIntent` dengan `requestCode` unik per notifikasi; override `onNewIntent()` + baca extras di `onCreate`; set tab chat, highlight `cloudId`, dan untuk `join_request` buka `dialogs.showManageMembers = true`.

### R2-H2. "Upgrade Sekarang" jalan buntu
`ManageMembersScreen.kt:219-227,467-497`, `MembershipManager.kt:1085-1097`, `functions/index.js:770-806`, `strings.xml:449-455`

Owner 2/2 anggota → approve ditolak → dialog "Upgrade ke Pro untuk menambah hingga 6 anggota" → tombol **Upgrade Sekarang** → callable menolak karena tidak ada `entitlements/{uid}`. Yang tampil hanya Toast "Upgrade belum tersedia…", tanpa tautan/alur beli (tidak ada `BillingClient` di app). User kembali ke dialog yang sama → loop.

**Fix:** tambahkan alur pembelian sungguhan, atau ganti copy/tombol menjadi "Hubungi admin" / sembunyikan bila entitlement tidak tersedia, sertakan langkah konkret. Beri snackbar sukses bila `setPlan` berhasil.

### R2-H3. Feedback sukses PALSU pada hapus/tolak/ubah peran anggota
`ManageMembersScreen.kt:377-397,432-439`, `MembershipManager.kt:1048-1065,1103-1129`

`removeMember()` / `rejectJoin()` / `setMemberRole()` menelan error (`runCatching{}.onFailure { Log.w }`) dan selalu return `Unit`. UI mengecek `runCatching { ... }.isSuccess` → **selalu true** → snackbar "Anggota dihapus" walau offline/rules error. Anggota masih terdaftar.

**Fix:** kembalikan `Boolean`/`Result` dari manager dan pakai nilai itu untuk feedback. Jangan pakai `isSuccess` dari blok yang tidak pernah melempar.

### R2-H4. Gagal cek update dilaporkan "sudah versi terbaru"
`MainAppDialogs.kt:173-182`, `GitHubUpdateChecker.kt:70-115`, `strings.xml:322`

`checkLatest()` mengembalikan `null` baik untuk error maupun "tidak ada release". Sheet langsung tutup tanpa spinner; snackbar hijau-netral "Aplikasi sudah menggunakan versi terbaru" walau offline/rate-limited.

**Fix:** `sealed class UpdateCheckResult { Success, UpToDate, Error }`; tampilkan loading; pesan error + tombol coba lagi.

### R2-H5. Tiga kanal umpan balik bersamaan
`MainOverlays.kt:354-378` (global, TopCenter, 480dp), `ChatScreen.kt:185-194,941-944` (lokal, BottomCenter, Short), `ManageMembersScreen.kt:111-130,310-315` (lokal di sheet), plus Toast di `ChatBubbles.kt:145` & `ManageMembersScreen.kt:489-493`.

"Disalin" muncul di bawah singkat, "Tercatat: …" muncul di atas dengan durasi Long — dua snackbar posisi/timing beda untuk aksi sekelas. Saat sheet Kelola Anggota terbuka, host global tetap aktif di atas. Toast upgrade bisa tertutup sheet/keyboard.

**Fix:** satu `SnackbarHostState` di MainActivity, ekspos via `CompositionLocal` atau `showSnack`; hapus dua host lokal & kedua Toast; matikan host global eksplisit saat sheet terbuka bila snackbar dalam sheet diinginkan.

### R2-H6. "Kunci tersimpan" sebelum penulisan selesai / tanpa cek hasil
`MainDialogs.kt:108-118`, `MainAppDialogs.kt:262-270,280-287`, `SecureStorage.kt:73-104`

`showSnackbar(strSaved)` dipanggil **sebelum** `onSave(trimmed)`; `putSecretAsync` di-fire-and-forget, hasil `Boolean` diabaikan. Dua string sukses berbeda (`strings.xml:59` vs `:284`). `isSaving` dilepas oleh `delay(1500)` buatan, bukan hasil nyata.

**Fix:** `onSave: suspend (String) -> Boolean`; spinner sampai `putSecretAsync` selesai; baru tutup + snackbar dari host global. Buang parameter `snackbarHostState` yang tidak pernah dikirim & salah satu string duplikat.

### R2-H7. Scrim gate memblokir pointer, bukan TalkBack/fokus
`MembershipGateScreen.kt:220-227`

Scrim hanya `pointerInput { detectTapGestures }`. Tombol login di balik gate (`PinConnectScreen`) **masih aktif di pohon semantics** dan bisa di-focus/diaktifkan TalkBack atau keyboard saat menunggu persetujuan (hingga 30 menit) — pengguna buta bisa mengganti akun di tengah proses.

**Fix:** `Modifier.semantics { invisibleToUser() }` pada konten utama saat `connectGate != null`, atau jadikan gate modal dengan `isDialog = true` + focus trap.

### R2-H8. Reduced-motion tidak ditegakkan secara global
`ChatScreen.kt:617` (`animateItem()` tanpa guard), `ChatInput.kt:182-192` (exit default), `Theme.kt:108-120` + `MainActivity.kt:112`

`applySystemSetting()` hanya men-set `Motion.reducedMotion`. Tidak ada `CompositionLocalProvider(LocalMotionDurationScale provides 0f)`, sehingga semua animasi M3 bawaan (sheet, AlertDialog, DropdownMenu, Tooltip) tetap berjalan. Test `MotionReducedMotionTest` hanya menguji helper, bukan integrasi Compose.

**Fix:** guard `animateItem()` seperti `RekapScreen.kt:291`; `exit = fadeOut(Motion.quick())`; bungkus `MaterialTheme` dengan `CompositionLocalProvider(LocalMotionDurationScale provides if (Motion.reducedMotion) 0f else 1f)`.

### R2-H9. `contentDescription` bubble menimpa status & aksi
`ChatBubbles.kt:271-283,428-440`

`combinedClickable` + `mergeDescendants`, induk menetapkan `contentDescription = bubbleDescription` dan `role = Role.None`. Label AI processing, status `PENDING`, "dibaca oleh N dari M", dan `Role.Button` "Buka transaksi" pada badge finansial **tertutup**. Bubble teks kehilangan jalan pintas transaksi.

**Fix:** sertakan status ke dalam `bubbleDescription`; jangan paksa `Role.None` saat ada child interaktif — gunakan `customActions` eksplisit untuk "Buka transaksi".

### R2-H10. Aksi hapus data paling destruktif tanpa progres/konfirmasi
`MainAppDialogs.kt:309-317` (Hapus Semua Data), `:319-333` (Keluar & Hapus Data)

Dialog langsung hilang, tanpa indikator "Menghapus…", tanpa snackbar hasil. Padahal backup/restore yang lebih ringan punya modal progres & feedback eksplisit.

**Fix:** pola `onLeaveWorkspaceConfirmed` (`MainActivity.kt:1199-1215`): `scope.launch`, progres, snackbar hasil, tutup hanya setelah state akhir diketahui.

---

## B. Integritas state & hasil operasi (MEDIUM)

| # | Temuan | Lokasi | Fix |
|---|---|---|---|
| B1 | Seluruh state dialog hilang saat rotasi — `MainDialogController` memakai `remember` biasa, sehingga `rememberSaveable` di dalam dialog tidak pernah membantu | `MainActivity.kt:183` vs `:189,196` | `rememberSaveable` + Saver untuk flag dialog, atau `savedStateHandle` |
| B2 | Retry gate setelah TIMEOUT mengirim ulang join request & kehilangan listener; timeout listener 10 mnt vs janji UI 30 mnt | `MembershipGateScreen.kt:63,136-195,310-321`, `MembershipManager.kt:968-1005` | bedakan retry "gagal kirim" vs "masih menunggu"; pertahankan `requestedOnce=true` & pasang ulang listener; samakan timeout |
| B3 | Simpan transaksi/hapus gagal diam-diam, sheet tetap tertutup | `MainViewModel.kt:308-339`, `AddTransactionDialog.kt:496-518` | pancarkan `operationFailed` → snackbar + retry; jangan tutup sebelum insert terkonfirmasi |
| B4 | Reset draf chat dari badge finansial bisa gagal — `showAddDialog=false` di `onConfirm` membuat scope `dismiss()` dibatalkan sebelum `onDismiss()` | `AddTransactionDialog.kt:234-238,496-519`, `MainAppDialogs.kt:140-153` | jangan set false di `onConfirm`; biarkan `dismiss()→onDismiss()`, atau naikkan trigger langsung |
| B5 | Rename workspace / edit label tanpa feedback & guard | `ManageMembersScreen.kt:323-328,457-461`, `MembershipManager.kt:1068-1075,1121-1129` | pakai pola `actionInFlight` + feedback yang sudah ada di file sama |
| B6 | Satu aksi "hapus", dua perilaku — chat/rekap/clear-data tutup duluan; manage member tunggu hasil. Tidak ada double-submit guard pada hapus/edit pesan | `ChatScreen.kt:953-957,1005-1010`, `RekapScreen.kt:317-321`, `MainAppDialogs.kt:311-314` | standarkan: `inFlight`, disable, async → feedback → tutup |
| B7 | Kirim ulang tidak memberi umpan balik; menu langsung tutup, pesan tetap "Gagal terkirim" saat offline | `ChatScreen.kt:423-429,645-657` | state `RETRYING` + snackbar hasil |
| B8 | Passphrase backup bisa di-submit dua kali; busy hanya untuk Restore | `BackupDialogs.kt:367-375`, `MainOverlays.kt:313-324` | `inFlight` lokal + `enabled=false`; busy untuk kedua tipe prompt |
| B9 | Status aksi anggota pakai satu `joinBusy` untuk semua kartu, tanpa spinner di kartu yang diproses | `ManageMembersScreen.kt:113-115,240-270,561-578` | `busyUid: String?` + spinner 16dp menggantikan ikon Check |
| B10 | Magic delay 120ms untuk fokus masih dipakai di 3 tempat | `ChatScreen.kt:381,978-981`, `AddTransactionDialog.kt:229-232` | `LaunchedEffect(Unit){ requestFocus() }` tanpa delay |
| B11 | Tidak ada restorasi fokus setelah dialog/sheet ditutup | seluruh dialog & sheet | simpan `FocusRequester` pemilik, `requestFocus()` di `onDismiss` |
| B12 | Fokus hilang saat langkah onboarding PIN berubah | `PinConnectScreen.kt:437-683` | `LaunchedEffect(pinFlowState){ ...requestFocus() }` |
| B13 | Kolom input chat tidak punya nama/hint untuk TalkBack | `ChatInput.kt:557-593` | `semantics { contentDescription = chat_input_placeholder }`, sembunyikan Text placeholder |
| B14 | Indikator progres tidak mengumumkan status | `MembershipGateScreen.kt:237`, `MainDialogs.kt:373-376,395-398`, `BackupDialogs.kt:97-100`, `ImageViewerDialog.kt:231-234` | `stateDescription`/`contentDescription` pada spinner |
| B15 | Keterangan transaksi tap-to-expand tanpa `onClickLabel`/state | `RekapList.kt:578-590` | `onClickLabel` + `stateDescription` "Terbuka/Terpotong" |
| B16 | `imeAction` belum merata (deskripsi transaksi, field passphrase) | `AddTransactionDialog.kt:376-386`, `BackupDialogs.kt:319-355` | `Done` / `Next` + `KeyboardActions` |
| B17 | Notifikasi "AI offline" terkubur di isi laporan, `ux_ai_offline_notice` tidak dipakai Service | `GeminiService.kt:485-505,608-614`, `AiReportDialog.kt:187-193` | chip/banner di header dialog & kartu; satu sumber kebenaran |
| B18 | Toggle "Notifikasi chat" diam-diam juga mematikan notifikasi keanggotaan | `ChatMessageFirebaseService.kt:44-48`, `strings.xml:389-390` | pisahkan toggle atau perjelas copy |
| B19 | Kunci pemulihan disuruh "salin" tapi tidak ada tombol Salin | `MainActivity.kt:604-633`, `SettingsSheet.kt:384-415` | tombol Salin via `ClipboardManager` |
| B20 | Microcopy "dihapus permanen" tapi ada "Urungkan" | `strings.xml:301,108` vs `MainActivity.kt:297-306` | ubah copy atau hapus undo |
| B21 | Kontras AiReport button gagal di dark mode (2.74:1) | `AiReportDialog.kt:199-240` | `contentColor = Color.White`, `semantic.ai` untuk outlined |
| B22 | Kontras teks kecil alpha 0.7 gagal di light (3.2–4.03:1) & scrim jam foto 2.4:1 | `RekapList.kt:636-640`, `ChatScreen.kt:513-518`, `ChatBubbles.kt:825-832,1063-1074` | warna penuh; scrim ≥0.6f |
| B23 | Titik "dibaca" hijau `#4CAF50` kontras 2.15:1 di bubble terang | `ChatBubbles.kt:1172-1177` | `semantic.income` / `#188038` |
| B24 | Duplikat pengumuman TalkBack (sync status, donut summary, identity card) | `RekapCharts.kt:288-305,575-581`, `SettingsSheet.kt:462-475` | `clearAndSetSemantics` / `mergeDescendants` |
| B25 | Inisial avatar dibacakan sebagai huruf liar di banyak layar | `AvatarImage.kt:119-125`, `MainTopBar.kt:166-205`, `SettingsSheet.kt:481-488`, `ManageMembersScreen.kt:605-615`, `ProfileAccountSheet.kt:133-140` | `clearAndSetSemantics {}` di dalam `AvatarImage` |
| B26 | Role kategori donut salah (`Role.Button`, selected tidak diumumkan) | `RekapCharts.kt:330-339` | `Role.Tab` |
| B27 | Status OFFLINE vs ERROR tidak bisa dibedakan visual di Rekap | `RekapCharts.kt:280-287` | bentuk/warna berbeda + `contentDescription` |
| B28 | Target sentuh <48dp tersisa (✕ viewer 44dp, FilterChip ~32dp, TextButton 40dp di 6+ tempat) | `ImageViewerDialog.kt:306-315`, `AddTransactionDialog.kt:291-329,441-447`, `RekapList.kt:147-153,233-238`, `ManageMembersScreen.kt:561-578,816-821`, `PinConnectScreen.kt:803-809`, `MainDialogs.kt:279-299` | `heightIn(min=48.dp)` / `minimumInteractiveComponentSize()` |

---

## C. Konsistensi & design system (LOW)

| # | Temuan | Lokasi |
|---|---|---|
| C1 | Terminologi drift: workspace/keluarga/grup, PIN/passphrase/kata sandi, plan/paket/pengelola; fallback `"User"` (English) | `strings.xml:34,166,353-357,439,446,462`, `SettingsSheet.kt:375` |
| C2 | Casing tombol tidak seragam ("Coba lagi" vs "Coba Lagi", "Upgrade ke Pro" vs "Upgrade Sekarang") | `strings.xml:265,383,449,452` |
| C3 | Emoji tidak konsisten sebagai kebijakan (sebagian dipertahankan, sebagian sengaja dihapus) | `strings.xml:99-100,120-125,158,309-311`, `BackupDialogs.kt:204` |
| C4 | Radius ad-hoc 24dp/14dp di luar `Constants.Ui.CORNER_*` | `SettingsSheet.kt:177`, `ManageMembersScreen.kt:147`, `AiReportDialog.kt:84,205,225,237`, `MainDialogs.kt:207`, `BackupDialogs.kt:152` |
| C5 | `Modifier.width(13.dp)` pada ikon Star (meregangkan ikon) | `ManageMembersScreen.kt:640` |
| C6 | Warna indikator hardcoded di luar `SemanticColors` (rainbow, hijau centang, branch dark manual, CTA `AiBlue`) | `ChatBubbles.kt:1135-1138,1175`, `RekapCharts.kt:281-286`, `AiReportDialog.kt:201,219-232` |
| C7 | Judul dialog update memakai "Periksa Update" juga saat unduh/error | `MainOverlays.kt:210-219`, `MainDialogs.kt:446-475` |
| C8 | Detail teknis bocor di dialog update (sertifikat/host/signature), hardcoded di Kotlin | `MainOverlays.kt:184,188,206,214` |
| C9 | Tombol konfirmasi batal restore: confirm = "Batal", dismiss = "OK" (membingungkan) | `MainOverlays.kt:327-347` |
| C10 | Heuristic heading markdown bisa menebalkan baris diawali `%`, `(`, `+`, `#` | `AiReportDialog.kt:290-303` |
| C11 | Baris padat di 320dp: nama pemohon tanpa ellipsis, month nav agresif, sheet tanpa `widthIn` | `ManageMembersScreen.kt:547-552,144-152`, `RekapList.kt:101-155`, `SettingsSheet.kt:174-182`, `AiReportDialog.kt:80-89` |
| C12 | String hardcoded di UI ("Rp", " · ", " • ", ISO time fallback) | `AddTransactionDialog.kt:345`, `RekapList.kt:603-607`, `ManageMembersScreen.kt:657`, `MainAppDialogs.kt:436`, `BackupDialogs.kt:62-70` |
| C13 | Label netral "Anggota" diwarnai pink (identitas istri) | `RekapList.kt:611-615` |
| C14 | State `enabled=false` tidak disampaikan ke TalkBack | `SettingsSheet.kt:551-572` |
| C15 | `Switch(onCheckedChange=null)` berisiko node switch dobel | `SettingsSheet.kt:241,309` |
| C16 | Donut 160dp & avatar 96dp berisiko di fontScale 2.0 | `RekapCharts.kt:584-639`, `ProfileAccountSheet.kt:133-140` |
| C17 | Ellipsis agresif menyembunyikan info di 360dp + fontScale 2.0 | `RekapList.kt:117-137,626-634`, `RekapCharts.kt:377-385,630-638` |
| C18 | Tooltip TopBar perlu verifikasi tidak dobel dengan `contentDescription` | `MainTopBar.kt:110-127` |

---

## D. Area yang sudah baik (tidak perlu diubah)

- **Auto-connect & PIN picker** fase Pin/Main sudah menangani offline, stale PIN, dan picker tanpa jalur mati (`MainActivity.kt:772-823,1032-1041`).
- **Status pesan jujur**: `PENDING` sampai receipt server nyata ada (`ChatScreen.kt:537-568`); empty state Rekap membedakan filter (`RekapScreen.kt:257-270`).
- **Undo hapus & snackbar tinted** income/expense konsisten (`MainActivity.kt:297-306,475-500`).
- **Konfirmasi restore** dua lapis sudah sesuai ekspektasi aksi irreversibel (`MainOverlays.kt:258-347`).
- **Konfirmasi anggota destruktif** sudah ada & di-guard in-flight — hanya pelaporan hasilnya yang salah (R2-H3).
- **Update in-app** verifikasi sertifikat fail-closed + host allowlist konsisten; tidak ada jalur install alternatif.
- **Animasi ber-label**, BackHandler tunggal dengan guard lapisan, empty state vs FAB, dan double-submit form transaksi sudah benar.
- **Target sentuh hasil putaran 1** (badge finansial, ✕ balas, hapus lampiran, tombol AI, chip saran, FAB, zoom ±) sudah ≥48dp.

---

## E. Urutan prioritas

1. **R2-H1 → R2-H4** (dead-end & kejujuran sistem): notifikasi, upgrade, feedback palsu, cek update.
2. **R2-H5 → R2-H6 → R2-H10** (feedback terpadu): satu SnackbarHost, hasil write nyata, progres hapus data.
3. **R2-H7 → R2-H9** (aksesibilitas kritis): gate TalkBack, reduced-motion global, deskripsi bubble.
4. **B1–B12** (state & hasil): rotasi dialog, retry gate, fokus/IME, guard operasi.
5. **B13–B28** (a11y & kontras), lalu **C1–C18** (konsistensi & token).

## F. Status implementasi

Seluruh 56 temuan telah diperbaiki. Ringkasan:

| Area | Perbaikan |
|---|---|
| **Handoff notifikasi** | `PendingIntent` membawa `type/familyId/cloudId/requesterUid` dengan requestCode unik; `MainActivity.onNewIntent` + cold-start extras; tab chat + highlight pesan; `join_request` membuka Kelola Anggota; keanggotaan diverifikasi sebelum pakai PIN dari extras |
| **Upgrade & update** | Copy jujur (`ux_upgrade_contact`) + snackbar sukses/gagal; `UpdateCheckResult` terstruktur (Update/UpToDate/Error), loading, error + Coba lagi, judul & pesan error tidak teknis |
| **Feedback** | SATU `AppSnackbar` global (host tunggal di `MainOverlays`), semua Toast & host lokal dihapus; `AppSnackbar.kt` baru; `operationFailed` + `retryLastFailedOperation`; hasil operasi anggota/transaksi nyata (Boolean), bukan `isSuccess` palsu |
| **Dialog & state** | `MainDialogController.Saver` (18 slot) tahan rotasi; `onConfirmAsync` menunggu hasil insert nyata dan menjaga BUG-08 draft reset; deadline gate 30 mnt konsisten; retry gate tidak mengirim ulang request saat masih PENDING |
| **Aksesibilitas** | Background gate `invisibleToUser()` saat gate aktif; `LocalMotionDurationScale=0` saat reduced-motion di `CoupleFinanceTheme`; `animateItem()` guard; `AvatarImage.clearAndSetSemantics`; deskripsi bubble memuat status (pending/read/AI/failed) + custom action "Buka transaksi"; 7 token `SemanticColors` baru dipakai konsumen; target sentuh ≥48dp; fokus awal & restorasi; IME merata |
| **Rekap & form** | `snapshotFlow` & import `text.input` diperbaiki; chip/banner offline AI; role `Tab` pada filter; donut & sync status pakai token; deskripsi tap-to-expand dengan `onClickLabel`; tanggal DatePicker UTC↔lokal sudah benar |

**Perbaikan hasil review integrasi final (7 item):**
1. **BLOCKER** — import `snapshotFlow` salah package di `RekapScreen.kt` → `androidx.compose.runtime.snapshotFlow`
2. **BLOCKER** — `OffsetMapping`/`TransformedText` salah package di `AddTransactionDialog.kt` → `androidx.compose.ui.text.input`
3. `onConfirmAsync` selalu melaporkan sukses → VM kini menerima callback `onResult` dengan hasil nyata
4. "Resend success" palsu (`syncMessage` suspend Unit yang menelan error) → jalur default hanya menyatakan "menunggu jaringan"
5. R2-H8 tidak benar-benar diterapkan (klaim agen bahwa `LocalMotionDurationScale` tidak ada ditolak) → kini dipasang di `Theme.kt`
6. Token `SemanticColors` baru mati (tidak ada konsumen) → `ChatBubbles`/`RekapCharts` kini memakainya
7. `SyncLifecycle` masih memakai `checkLatest()` deprecated → beralih ke `checkLatestResult()`

`git diff --check` bersih; `node --check functions/index.js` lolos. Review mengonfirmasi **tidak ada regresi keamanan** (E2EE gating, receipt, `setPlan` server-authoritative, throttle PIN, kick/leave cleanup, verifikasi APK, AAD tetap utuh) dan kompatibilitas test terjaga.

**Belum bisa diverifikasi:** Gradle test — environment tidak memiliki JDK/`JAVA_HOME`. Perlu dijalankan di mesin dengan JDK sebelum rilis.
