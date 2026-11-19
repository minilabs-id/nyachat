package com.startupmini.nyachat.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import com.startupmini.nyachat.data.local.FinancialTransaction
import com.startupmini.nyachat.data.remote.GitHubRelease
import com.startupmini.nyachat.data.remote.MyWorkspace

/**
 * TASK-1.3 lanjutan — state holder SEMUA dialog/overlay layar utama.
 *
 * Sebelumnya ~16 `var ... by remember` tersebar di MainActivity sehingga
 * composable utama sulit dibaca. Dipindahkan ke satu class yang hidup selama
 * komposisi (diingat via `remember`), mengikuti pola `DriveBackupController`.
 * Tidak ada behavior change — hanya lokasi state berubah.
 */
class MainDialogController {
    var showAddDialog by mutableStateOf(false)
    // BUG-08: dinaikkan tiap dialog transaksi manual ditutup — ChatScreen
    // mereset input-nya supaya karakter sisa tidak menempel di field chat.
    var chatResetTrigger by mutableIntStateOf(0)
    // Gate BUG-08: reset field chat HANYA saat dialog dibuka dari tab Chat
    // (tap badge finansial). Dialog dari tab Rekap tidak boleh menghapus
    // draf chat user yang diketik sebelum pindah tab.
    var resetChatOnDialogClose by mutableStateOf(false)
    var showSettingsSheet by mutableStateOf(false)
    // Profil & Akun (r1.2.1): halaman profil dibuka dari kartu profil Settings.
    var showProfileAccount by mutableStateOf(false)
    var showManageMembers by mutableStateOf(false)
    var showGeminiKeyDialog by mutableStateOf(false)
    var showLogoutDialog by mutableStateOf(false)
    var pendingPinConnect by mutableStateOf<Triple<String, String, String>?>(null)
    var connectGate by mutableStateOf<Triple<String, String, String>?>(null)
    var showOpenRouterKeyDialog by mutableStateOf(false)
    var showConfirmClearDialog by mutableStateOf(false)
    // r1.4.0 (keluar dari workspace): dialog konfirmasi lepas dari workspace.
    var showLeaveWorkspaceDialog by mutableStateOf(false)
    // r1.4.0 (auto-connect): daftar pilihan workspace untuk akun lama >1.
    var workspaceChoices by mutableStateOf<List<MyWorkspace>>(emptyList())
    var showPinDialog by mutableStateOf(false)
    var editTarget by mutableStateOf<FinancialTransaction?>(null)

    // State dialog update (tampil di SEMUA layar).
    var updateInfo by mutableStateOf<GitHubRelease?>(null)
    var isDownloadingUpdate by mutableStateOf(false)
    // Progres unduhan APK 0..1 (hanya bermakna saat isDownloadingUpdate).
    var updateProgress by mutableStateOf(0f)
    var updateMessage by mutableStateOf<String?>(null)
    // R2-H4/C7: loading saat cek update & jenis pesan dialog update (error vs info).
    var isCheckingUpdate by mutableStateOf(false)
    var updateMessageIsError by mutableStateOf(false)

    companion object {
        /**
         * B1: dialog yang memuat input tidak boleh hilang saat rotasi. Field yang
         * tidak serializable ([editTarget], [workspaceChoices], [updateInfo], dsb.)
         * sengaja dilewat — state transien itu boleh di-reset.
         * Triple disimpan sebagai 3 String (empty = null) agar listSaver tetap
         * bertipe List<Any> (non-null).
         */
        val Saver: Saver<MainDialogController, List<Any>> = listSaver(
            save = { c ->
                listOf<Any>(
                    c.showAddDialog,
                    c.chatResetTrigger,
                    c.resetChatOnDialogClose,
                    c.showSettingsSheet,
                    c.showProfileAccount,
                    c.showManageMembers,
                    c.showGeminiKeyDialog,
                    c.showLogoutDialog,
                    c.showOpenRouterKeyDialog,
                    c.showConfirmClearDialog,
                    c.showLeaveWorkspaceDialog,
                    c.showPinDialog,
                    c.pendingPinConnect?.first ?: "",
                    c.pendingPinConnect?.second ?: "",
                    c.pendingPinConnect?.third ?: "",
                    c.connectGate?.first ?: "",
                    c.connectGate?.second ?: "",
                    c.connectGate?.third ?: ""
                )
            },
            restore = { saved ->
                MainDialogController().apply {
                    showAddDialog = saved[0] as Boolean
                    chatResetTrigger = saved[1] as Int
                    resetChatOnDialogClose = saved[2] as Boolean
                    showSettingsSheet = saved[3] as Boolean
                    showProfileAccount = saved[4] as Boolean
                    showManageMembers = saved[5] as Boolean
                    showGeminiKeyDialog = saved[6] as Boolean
                    showLogoutDialog = saved[7] as Boolean
                    showOpenRouterKeyDialog = saved[8] as Boolean
                    showConfirmClearDialog = saved[9] as Boolean
                    showLeaveWorkspaceDialog = saved[10] as Boolean
                    showPinDialog = saved[11] as Boolean
                    val pendingPin = saved[12] as String
                    pendingPinConnect = if (pendingPin.isEmpty()) null else Triple(
                        pendingPin, saved[13] as String, saved[14] as String
                    )
                    val gatePin = saved[15] as String
                    connectGate = if (gatePin.isEmpty()) null else Triple(
                        gatePin, saved[16] as String, saved[17] as String
                    )
                }
            }
        )
    }
}
