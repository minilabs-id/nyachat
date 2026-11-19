package com.startupmini.nyachat.ui

import android.content.Context
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.abs
import kotlin.math.max
import com.startupmini.nyachat.BuildConfig
import com.startupmini.nyachat.Constants
import com.startupmini.nyachat.R
import com.startupmini.nyachat.data.backup.DriveBackupController
import com.startupmini.nyachat.data.remote.GitHubUpdateChecker
import com.startupmini.nyachat.ui.theme.Motion
import com.startupmini.nyachat.ui.screens.BackupProgressDialog
import com.startupmini.nyachat.ui.screens.CrossFamilyRestoreDialog
import com.startupmini.nyachat.ui.screens.ManageMembersScreen
import com.startupmini.nyachat.ui.screens.MembershipGateScreen
import com.startupmini.nyachat.ui.screens.PassphraseDialog
import com.startupmini.nyachat.ui.screens.PinSwitchDialog
import com.startupmini.nyachat.ui.screens.RestoreConfirmDialog
import com.startupmini.nyachat.ui.screens.RestorePickerDialog
import com.startupmini.nyachat.ui.screens.UpdateAvailableDialog
import com.startupmini.nyachat.ui.screens.installApk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "MainOverlays"

/**
 * TASK-1.3 lanjutan — overlay GLOBAL yang tampil di SEMUA layar (termasuk
 * layar login/PIN/gate), diekstrak dari MainActivity (no behavior change).
 *
 * Menampung: MembershipGateScreen, ManageMembersScreen, PinSwitchDialog,
 * UpdateAvailableDialog + UpdateMessageDialog, dialog backup/restore Drive
 * (progress, picker, konfirmasi, lintas-workspace, passphrase) dan SnackbarHost.
 * State dialog dipakai dari [MainDialogController]; state backup dipakai dari
 * [DriveBackupController] langsung.
 */
/** [BoxScope]: SnackbarHost memakai [Modifier.align] terhadap Box konten induk. */
@Composable
fun BoxScope.MainOverlays(
    viewModel: MainViewModel,
    context: Context,
    scope: CoroutineScope,
    dialogs: MainDialogController,
    driveController: DriveBackupController,
    snackbarHostState: SnackbarHostState,
    workspacePin: String?,
    workspaceRole: String?,
    onApplyPinConnect: (String, String, String) -> Unit,
    // BUG-fix (audit 2026-08-14): snackbar di fase Main TIDAK boleh menimpa
    // ikon top bar (Kelola Anggota/Settings). true → snackbar di-offset ke
    // bawah TopAppBar (~64dp); false (fase Pin, tanpa top bar) → tetap dekat
    // status bar. Diisi MainActivity dari startupPhase.
    snackbarBelowTopBar: Boolean = false
) {
    // C7/C8: resolve string di composable scope — JANGAN context.getString di
    // dalam coroutine (lint LocalContextGetResourceValueCall).
    val updateVerifyFailedLabel = stringResource(R.string.ux_update_verify_failed)
    val updateDownloadFailedLabel = stringResource(R.string.update_download_failed)
    val updateCheckTitle = stringResource(R.string.ux_update_check_title)
    val updateErrorTitle = stringResource(R.string.ux_update_error_title)
    val statusCheckingLabel = stringResource(R.string.ux_status_checking)

    // Gate keanggotaan: setelah PIN dimasukkan, sebelum data terbuka.
    // Owner menyiapkan workspace; anggota kirim permintaan & menunggu
    // persetujuan pemilik. Layar penuh menimpa semua konten lain.
    // Audit motion startup (2026-08-12): muncul LEMBUT (fade + zoom) —
    // sebelumnya langsung pop instan di tengah alur pembukaan.
    androidx.compose.animation.AnimatedVisibility(
        visible = dialogs.connectGate != null,
        enter = androidx.compose.animation.fadeIn(animationSpec = Motion.base()) +
            androidx.compose.animation.scaleIn(
                initialScale = 0.97f,
                animationSpec = Motion.base()
            ),
        exit = androidx.compose.animation.fadeOut(animationSpec = Motion.quick())
    ) {
        dialogs.connectGate?.let { (pin, role, name) ->
            MembershipGateScreen(
                pin = pin,
                role = role,
                onReady = {
                    dialogs.connectGate = null
                    onApplyPinConnect(pin, role, name)
                },
                onCancel = { dialogs.connectGate = null }
            )
        }
    }

    // R2-H7: saat gate keanggotaan aktif, SEMUA UI di luar gate harus hilang
    // dari pohon semantics (TalkBack/fokus keyboard) — scrim pointerInput di
    // MembershipGateScreen hanya komplemen pointer-only, tidak cukup untuk a11y.
    // Gate itu sendiri tetap di luar wrapper ini sehingga tetap bisa dibaca.
    Box(
        modifier = Modifier
            .matchParentSize()
            .semantics { if (dialogs.connectGate != null) invisibleToUser() }
    ) {

    // Layar kelola anggota & permintaan bergabung (owner/member).
    // Guard `workspacePin != null` → smart cast aman (audit ketahanan:
    // hilangkan `!!` yang berisiko NPE bila alur berubah di masa depan).
    // Audit motion (2026-08-12): kini ModalBottomSheet — muncul slide dari
    // bawah & tutup turun ke bawah (sheetState.hide() di dalam screen),
    // konsisten dengan SettingsSheet. Wrapper AnimatedVisibility fade/zoom
    // dihapus karena sheet sudah punya animasi bawaan sendiri.
    if (dialogs.showManageMembers && workspacePin != null) {
        ManageMembersScreen(
            pin = workspacePin,
            isOwner = (workspaceRole == Constants.Roles.OWNER),
            onDismiss = { dialogs.showManageMembers = false }
        )
    }

    // Konfirmasi ganti workspace (PIN berbeda): tampil di SEMUA layar.
    dialogs.pendingPinConnect?.let { (pin, role, name) ->
        PinSwitchDialog(
            onConfirm = {
                dialogs.pendingPinConnect = null
                viewModel.clearLocalData()
                dialogs.connectGate = Triple(pin, role, name)
            },
            onDismiss = { dialogs.pendingPinConnect = null }
        )
    }

    // Dialog update tampil di SEMUA layar (termasuk layar login/PIN),
    // jadi yang belum selesai onboarding tetap dapat notif rilis baru.
    dialogs.updateInfo?.let { release ->
        UpdateAvailableDialog(
            release = release,
            currentVersion = BuildConfig.VERSION_NAME,
            isDownloading = dialogs.isDownloadingUpdate,
            progress = dialogs.updateProgress,
            onAction = {
                scope.launch {
                    // Update in-app berlaku di SEMUA build (debug & release):
                    // unduh APK dari GitHub Release lalu pasang langsung. Sebelumnya
                    // release build hanya membuka halaman rilis di browser (keluhan
                    // tester: "klik update malah diarahkan ke repo"). Kini tombol
                    // langsung download & install — lihat juga REQUEST_INSTALL_PACKAGES
                    // di main manifest.
                    dialogs.isDownloadingUpdate = true
                    dialogs.updateProgress = 0f
                    dialogs.updateMessageIsError = true
                    var rejection: String? = null
                    try {
                        val url = release.apkUrl
                        if (url == null) throw IllegalStateException("APK tidak tersedia di release")

                        // H4/M10 (audit 2026-11-19): cek konfigurasi sidik jari dan
                        // host URL lebih dulu. Tanpa fingerprint, update DITOLAK
                        // (fail-closed) — APK tak terverifikasi tidak boleh masuk.
                        // C7: detail teknis (sertifikat/host/signature) diganti
                        // pesan ux_update_verify_failed yang bisa dipahami user.
                        if (BuildConfig.APK_CERT_SHA256.isBlank()) {
                            rejection = updateVerifyFailedLabel
                            throw IllegalStateException(rejection)
                        }
                        if (!GitHubUpdateChecker.isAllowedApkUrl(url)) {
                            rejection = updateVerifyFailedLabel
                            throw IllegalStateException(rejection)
                        }

                        val dest = File(context.cacheDir, "downloads/nyachat-${release.versionName}.apk")
                        // Progres dipanggil dari thread IO → marshal ke main scope
                        // sebelum menulis state Compose.
                        GitHubUpdateChecker.downloadApk(url, dest) { p ->
                            scope.launch { dialogs.updateProgress = p }
                        }

                        // H4/M10: WAJIB verifikasi tanda tangan SEBELUM install.
                        // Parsing sertifikat via PackageManager di thread IO agar
                        // tidak mem-blok main thread saat Compose recompose.
                        val verified = withContext(Dispatchers.IO) {
                            GitHubUpdateChecker.verifyApkSignature(context, dest)
                        }
                        if (!verified) {
                            rejection = updateVerifyFailedLabel
                            throw IllegalStateException(rejection)
                        }
                        installApk(context, dest)
                    } catch (e: Exception) {
                        Log.w(TAG, "Update gagal: ${e.message}")
                        // C7: pesan error user-facing, bukan detail teknis.
                        dialogs.updateMessage = rejection ?: updateDownloadFailedLabel
                    } finally {
                        dialogs.isDownloadingUpdate = false
                        dialogs.updateProgress = 0f
                        dialogs.updateInfo = null
                    }
                }
            },
            onDismiss = { dialogs.updateInfo = null }
        )
    }

    // R2-H4: loading saat mengecek update (judul ux_update_check_title).
    if (dialogs.isCheckingUpdate) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(updateCheckTitle) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(statusCheckingLabel)
                }
            },
            confirmButton = {}
        )
    }

    // C7/C8: satu dialog pesan dengan judul kontekstual — error memakai
    // ux_update_error_title, hasil cek/info memakai ux_update_check_title.
    dialogs.updateMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { dialogs.updateMessage = null },
            title = {
                Text(if (dialogs.updateMessageIsError) updateErrorTitle else updateCheckTitle)
            },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = { dialogs.updateMessage = null }) {
                    Text(stringResource(R.string.action_ok))
                }
            }
        )
    }

    // ---- Dialog Export CSV / Backup / Restore Drive ----
    // B3: modal bisa dibatalkan — kalau Drive menggantung, user tidak
    // terkunci; tombol Batal membatalkan operasi aktif di controller.
    val backupBusy by driveController.busy.collectAsStateWithLifecycle()
    val restoreBackups by driveController.backups.collectAsStateWithLifecycle()
    val restoreTarget by driveController.restoreTarget.collectAsStateWithLifecycle()
    val pendingCrossFamilyRestore by driveController.crossFamilyRestore.collectAsStateWithLifecycle()
    val backupPassphrasePrompt by driveController.passphrasePrompt.collectAsStateWithLifecycle()

    // F23: Back fisik menutup gate keanggotaan, TAPI hanya bila gate benar-benar
    // lapisan teratas. Dialog/sheet yang menumpuk di atasnya tetap menangani Back
    // agar tidak ikut tertutup (mis. PinSwitchDialog/Update/restore sheet).
    BackHandler(
        enabled = dialogs.connectGate != null &&
            dialogs.pendingPinConnect == null &&
            dialogs.updateInfo == null &&
            dialogs.updateMessage == null &&
            !dialogs.showManageMembers &&
            restoreBackups == null &&
            restoreTarget == null &&
            pendingCrossFamilyRestore == null &&
            backupPassphrasePrompt == null &&
            !backupBusy
    ) { dialogs.connectGate = null }

    // F19: restore adalah operasi kritis — Back/tap-di-luar/tombol Batal TIDAK
    // langsung membatalkan; tampilkan konfirmasi dulu.
    var restoreInProgress by remember { mutableStateOf(false) }
    var cancelRestoreConfirm by remember { mutableStateOf(false) }
    LaunchedEffect(backupBusy) {
        if (!backupBusy) {
            restoreInProgress = false
            cancelRestoreConfirm = false
        }
    }
    val handleBackupCancel: () -> Unit = {
        if (restoreInProgress) cancelRestoreConfirm = true
        else driveController.cancelActiveOperation()
    }

    if (backupBusy) {
        BackupProgressDialog(
            onCancel = handleBackupCancel,
            onDismissRequest = handleBackupCancel
        )
    }

    restoreBackups?.let { files ->
        RestorePickerDialog(
            files = files,
            onPick = { driveController.confirmRestore(it) },
            onDismiss = { driveController.dismissBackups() }
        )
    }

    restoreTarget?.let { f ->
        RestoreConfirmDialog(
            file = f,
            onConfirm = {
                restoreInProgress = true
                driveController.confirmRestore(f)
            },
            onDismiss = { driveController.dismissRestoreTarget() }
        )
    }

    // Backup milik workspace lain → konfirmasi eksplisit sebelum
    // menimpa data lokal & menyinkronkannya ke workspace ini (P1).
    pendingCrossFamilyRestore?.let {
        CrossFamilyRestoreDialog(
            onConfirm = {
                restoreInProgress = true
                driveController.proceedCrossFamilyRestore()
            },
            onDismiss = { driveController.cancelCrossFamilyRestore() }
        )
    }

    // Prompt passphrase (Sprint-2): muncul saat membuat backup
    // terenkripsi atau membuka backup terenkripsi saat restore.
    backupPassphrasePrompt?.let { prompt ->
        PassphraseDialog(
            prompt = prompt,
            onSubmit = {
                if (prompt is DriveBackupController.PassphrasePrompt.Restore) {
                    restoreInProgress = true
                }
                driveController.submitPassphrase(it)
            },
            onCancel = { driveController.cancelPassphrase() }
        )
    }

    // F19: konfirmasi pembatalan restore — data bisa tidak lengkap bila dihentikan.
    if (cancelRestoreConfirm) {
        AlertDialog(
            onDismissRequest = { cancelRestoreConfirm = false },
            title = { Text(stringResource(R.string.ux_cancel_restore_title)) },
            text = { Text(stringResource(R.string.ux_cancel_restore_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        cancelRestoreConfirm = false
                        restoreInProgress = false
                        driveController.cancelActiveOperation()
                    }
                ) { Text(stringResource(R.string.ux_cancel_restore_confirm)) }
            },
            // C9: tombol yang MELANJUTKAN restore adalah dismiss — sebelumnya
            // labelnya terbalik (OK = batalkan, Batal = lanjutkan).
            dismissButton = {
                TextButton(onClick = { cancelRestoreConfirm = false }) {
                    Text(stringResource(R.string.ux_continue_restore))
                }
            }
        )
    }

    // Snackbar overlay (audit P1.1): tampil di semua layar. Sejak 2026-08-10
    // dipindah ke ATAS layar — sebelumnya di BottomCenter + imePadding justru
    // muncul TEPAT di atas keyboard, menutupi kolom pengetikan & mengganggu
    // ketik cepat (keluhan user). Sekarang: atas, compact (pill), dan bisa
    // di-dismiss cepat dengan swipe kanan/kiri/atas (DismissibleSnackbar).
    SnackbarHost(
        hostState = snackbarHostState,
        modifier = Modifier
            .align(Alignment.TopCenter)
            .windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Top))
            // Review: inset status bar TETAP diperlukan (enableEdgeToEdge aktif,
            // induk tidak menyediakannya). Total offset dijaga ≈72dp/32dp:
            // statusBar ~24dp + manual 48dp/8dp. Dulu 24dp + 72dp ≈100dp.
            // BUG-fix (audit 2026-08-14): TopCenter + 8dp menimpa TopAppBar
            // (64dp) sehingga snackbar "Tercatat" menutupi ikon Kelola Anggota/
            // Settings. Saat top bar tampil, turunkan snackbar ke bawahnya
            // (64dp top bar + 8dp gap = 72dp); di layar PIN (tanpa top bar)
            // tetap 8dp. Posisi atas dipertahankan — alasan historisnya masih
            // berlaku (BottomCenter+imePadding menutupi kolom ketik).
            .padding(
                top = if (snackbarBelowTopBar) 48.dp else 8.dp,
                start = 20.dp,
                end = 20.dp
            ),
        snackbar = { data ->
            // Compact pill — batasi lebar (bukan full-width) supaya terasa
            // minimalis & konsisten dengan bahasa floating-card aplikasi.
            DismissibleSnackbar(
                snackbarData = data,
                modifier = Modifier.widthIn(max = 480.dp)
            )
        }
    )
    } // R2-H7: akhir wrapper semantics background (di luar gate).
}

/**
 * SnackbarVisuals dengan warna container khusus (audit 2026-08-12): notifikasi
 * "Tercatat" membedakan PEMASUKAN (hijau) vs PENGELUARAN (merah) agar user bisa
 * mengenali arah uang sekilas — sebelum ini semua notifikasi memakai inverseSurface
 * netral. [containerTint] null → tetap netral (notifikasi info lain). Warna dibawa
 * bersama visuals (bukan state terpisah) supaya benar walau snackbar mengantre.
 */
internal class TintedSnackbarVisuals(
    override val message: String,
    override val actionLabel: String?,
    override val duration: SnackbarDuration,
    val containerTint: Color? = null
) : SnackbarVisuals {
    override val withDismissAction: Boolean = false
}

/**
 * Snackbar compact yang bisa di-dismiss dengan swipe (kiri/kanan/atas).
 *
 * Catatan: material3 1.3.x TIDAK menyediakan swipe-to-dismiss bawaan (hanya
 * tombol dismissAction + aksesibilitas dismiss), jadi gesture diimplementasi
 * manual: drag bebas 2 sumbu (horizontal & vertikal) via pointerInput,
 * disertai fade-out proporsional. Saat dilepas: di atas ambang jarak 72dp
 * -> dismiss; di bawah ambang -> animasi kembali ke posisi semula (spring
 * lembut). Tap pada tombol aksi (mis. "Urungkan") tetap berfungsi karena
 * drag hanya aktif setelah melewati touch slop.
 *
 * (audit 2026-08-13) Komentar lama menyebut "atau kecepatan >= 2000px/s"
 * tapi implementasi TIDAK memeriksa kecepatan — hanya jarak. Komentar
 * diperbaiki agar jujur; kecepatan sengaja tidak ditambahkan supaya flick
 * cepat berjarak pendek tidak men-dismiss snackbar secara tak sengaja.
 */
@Composable
private fun DismissibleSnackbar(
    snackbarData: SnackbarData,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val dismissThreshold = with(density) { 72.dp.toPx() }
    var dragOffset by remember(snackbarData) { mutableStateOf(Offset.Zero) }

    Box(
        modifier = modifier
            .graphicsLayer {
                translationX = dragOffset.x
                translationY = dragOffset.y
                val maxAbs = max(abs(dragOffset.x), abs(dragOffset.y))
                alpha = (1f - maxAbs / (dismissThreshold * 2)).coerceIn(0.3f, 1f)
            }
            .pointerInput(snackbarData) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val pointerId = down.id
                    var dragStarted = false
                    var accX = 0f
                    var accY = 0f
                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == pointerId }
                        if (change == null) break
                        if (!dragStarted) {
                            if (!change.isConsumed) {
                                accX += change.positionChange().x
                                accY += change.positionChange().y
                            }
                            val slop = viewConfiguration.touchSlop
                            if (abs(accX) > slop || abs(accY) > slop) {
                                // Mulai drag dari posisi akumulasi pra-slop agar
                                // tidak ada lompatan saat jari mulai bergerak.
                                dragStarted = true
                                dragOffset = Offset(accX, accY)
                                change.consume()
                            }
                        } else {
                            dragOffset += change.positionChange()
                            change.consume()
                        }
                    } while (event.changes.any { it.pressed })

                    if (dragStarted) {
                        val distance = dragOffset.getDistance()
                        if (distance >= dismissThreshold) {
                            snackbarData.dismiss()
                        } else {
                            val start = dragOffset
                            scope.launch {
                                Animatable(start, Offset.VectorConverter)
                                    .animateTo(
                                        Offset.Zero,
                                        Motion.base(),
                                    ) { dragOffset = value }
                            }
                        }
                    }
                }
            }
    ) {
        // Audit 2026-08-12: warna container bisa di-tint (TintedSnackbarVisuals) —
        // notifikasi transaksi memakai hijau/merah sesuai arah uang; sisanya netral
        // (inverseSurface). Teks & aksi putih di atas warna tint (kontras AA),
        // actionColor diset eksplisit supaya "Urungkan" terbaca di kedua kasus.
        val tint = (snackbarData.visuals as? TintedSnackbarVisuals)?.containerTint
        Snackbar(
            snackbarData = snackbarData,
            shape = RoundedCornerShape(28.dp),
            containerColor = tint ?: MaterialTheme.colorScheme.inverseSurface,
            contentColor = if (tint != null) Color.White else MaterialTheme.colorScheme.inverseOnSurface,
            actionColor = if (tint != null) Color.White else MaterialTheme.colorScheme.inversePrimary,
        )
    }
}
