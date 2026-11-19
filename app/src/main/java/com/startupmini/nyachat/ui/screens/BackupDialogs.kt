package com.startupmini.nyachat.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.startupmini.nyachat.R
import com.startupmini.nyachat.data.backup.DriveBackupController
import com.startupmini.nyachat.data.backup.DriveBackupFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.delay

// Dialog backup/restore Drive diekstrak dari MainActivity (TASK-1.3) — aksi
// di-hoist ke DriveBackupController via callback, tanpa perubahan behavior.

/**
 * Format waktu `createdTime` dari Drive (ISO-8601, mis. "2026-08-12T07:42:23.259Z")
 * ke tampilan lokal: "12 Agu 2026 · 14:42". Gagal parse → kembalikan string
 * mentah (backup lama dengan format berbeda tidak boleh membuat dialog error).
 */
internal fun formatBackupTime(isoTime: String): String {
    return runCatching {
        val parsed = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .parse(isoTime) ?: return@runCatching null
        val display = SimpleDateFormat("d MMM yyyy · HH:mm", Locale("id", "ID"))
        display.format(parsed as Date)
    }.getOrNull() ?: isoTime
}

/** Modal progres backup — bisa dibatalkan (B3).
 *  [onDismissRequest] opsional: saat restore, pemanggil menggantinya dengan
 *  konfirmasi (F19) sehingga Back/tap-di-luar TIDAK membatalkan langsung. */
@Composable
fun BackupProgressDialog(
    onCancel: () -> Unit,
    onDismissRequest: (() -> Unit)? = null
) {
    AlertDialog(
        onDismissRequest = onDismissRequest ?: onCancel,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.CloudUpload,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(stringResource(R.string.backup_progress_title))
            }
        },
        text = {
            // stringResource tidak bisa dipanggil di dalam lambda semantics.
            val checkingLabel = stringResource(R.string.ux_status_checking)
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        // B14: status spinner dibacakan TalkBack.
                        modifier = Modifier
                            .size(30.dp)
                            .semantics { stateDescription = checkingLabel },
                        strokeWidth = 3.dp
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        stringResource(R.string.backup_progress_detail),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Text(
                    stringResource(R.string.backup_please_wait),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

/** Pilih file backup dari Google Drive untuk di-restore. */
@Composable
fun RestorePickerDialog(
    files: List<DriveBackupFile>,
    onPick: (DriveBackupFile) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.restore_pick_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.restore_pick_hint),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                // F18: daftar backup bisa panjang — bungkus dengan area scroll
                // berbatas tinggi supaya semua file tetap bisa dipilih.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                files.forEachIndexed { index, f ->
                    val isNewest = index == 0
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isNewest) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                            }
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                    ) {
                        TextButton(
                            onClick = { onPick(f) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = f.name,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (isNewest) {
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Surface(
                                            color = MaterialTheme.colorScheme.primary,
                                            shape = RoundedCornerShape(50)
                                        ) {
                                            Text(
                                                text = stringResource(R.string.restore_pick_newest_badge),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onPrimary,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                            )
                                        }
                                    }
                                    // Badge 🔒 — indikator file backup terenkripsi di picker
                                    // restore (temuan #4 live test). `encrypted == true`
                                    // (null = status belum diketahui, tampil netral).
                                    if (f.encrypted == true) {
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Surface(
                                            color = MaterialTheme.colorScheme.tertiaryContainer,
                                            shape = RoundedCornerShape(50)
                                        ) {
                                            Text(
                                                text = "🔒 ${stringResource(R.string.settings_backup_encrypted_yes)}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                            )
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(3.dp))
                                Text(
                                    text = formatBackupTime(f.createdTime),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

/** Konfirmasi restore file backup tertentu. */
@Composable
fun RestoreConfirmDialog(
    file: DriveBackupFile,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.restore_confirm_title)) },
        text = { Text(stringResource(R.string.restore_confirm_message, file.name)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.action_restore))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

/** Backup milik workspace lain → konfirmasi eksplisit sebelum menimpa (P1). */
@Composable
fun CrossFamilyRestoreDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.restore_cross_family_title)) },
        text = { Text(stringResource(R.string.restore_cross_family_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.action_restore))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

/** Prompt passphrase (backup terenkripsi / restore terenkripsi). */
@Composable
fun PassphraseDialog(
    prompt: DriveBackupController.PassphrasePrompt,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit
) {
    val isBackupPrompt = prompt is DriveBackupController.PassphrasePrompt.Backup
    // F21: kunci saver STABIL berdasarkan tipe prompt (bukan identitas objek),
    // supaya teks tidak hilang saat rotasi tapi tidak tercampur antar prompt.
    val saverKey = prompt::class.simpleName ?: "Passphrase"
    // Review: dua field TIDAK boleh memakai key saver yang sama — saat rotasi
    // keduanya akan membaca slot yang sama dan validasi konfirmasi jadi palsu.
    var passphrase by rememberSaveable("$saverKey.passphrase") { mutableStateOf("") }
    var confirmPassphrase by rememberSaveable("$saverKey.confirm") { mutableStateOf("") }
    var revealed by remember(saverKey) { mutableStateOf(false) }
    // B8: guard submit — cegah dobel tap OK memicu backup/restore dua kali.
    var inFlight by remember { mutableStateOf(false) }
    // Jaga-jaga bila pemanggil tidak menutup dialog: lepas guard setelah jeda.
    LaunchedEffect(inFlight) {
        if (inFlight) {
            delay(1500)
            inFlight = false
        }
    }
    val mismatch = confirmPassphrase != passphrase
    val visual: VisualTransformation =
        if (revealed) VisualTransformation.None else PasswordVisualTransformation()

    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Text(
                stringResource(
                    if (isBackupPrompt) R.string.backup_passphrase_title
                    else R.string.restore_passphrase_title
                )
            )
        },
        text = {
            // B-M14: dua field + pesan bisa melebihi tinggi dialog di layar
            // 320dp / fontScale besar — beri batas tinggi + area scroll.
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    stringResource(
                        if (isBackupPrompt) R.string.backup_passphrase_message
                        else R.string.restore_passphrase_message
                    )
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    singleLine = true,
                    visualTransformation = visual,
                    label = { Text(stringResource(R.string.backup_passphrase_hint)) },
                    trailingIcon = {
                        IconButton(onClick = { revealed = !revealed }) {
                            Icon(
                                imageVector = if (revealed) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                contentDescription = stringResource(
                                    if (revealed) R.string.ux_api_key_hide else R.string.ux_api_key_reveal
                                )
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = stringResource(R.string.ux_passphrase_min),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
                // F16: saat membuat backup, minta passphrase diketik ulang supaya
                // typo tidak membuat backup yang tidak bisa dipulihkan.
                if (isBackupPrompt) {
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = confirmPassphrase,
                        onValueChange = { confirmPassphrase = it },
                        singleLine = true,
                        visualTransformation = visual,
                        isError = mismatch && confirmPassphrase.isNotBlank(),
                        label = { Text(stringResource(R.string.ux_passphrase_confirm)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (mismatch && confirmPassphrase.isNotBlank()) {
                        Text(
                            text = stringResource(R.string.ux_passphrase_mismatch),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !inFlight && passphrase.length >= 8 &&
                    (!isBackupPrompt || (confirmPassphrase.length >= 8 && !mismatch)),
                onClick = {
                    if (inFlight) return@TextButton
                    inFlight = true
                    onSubmit(passphrase)
                },
                // B28: target sentuh minimal 48dp.
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                if (inFlight) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(stringResource(R.string.action_ok))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}
