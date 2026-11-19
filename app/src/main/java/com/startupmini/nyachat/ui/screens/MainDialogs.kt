package com.startupmini.nyachat.ui.screens

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ExitToApp
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Pin
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.startupmini.nyachat.R
import com.startupmini.nyachat.data.remote.GitHubRelease
import com.startupmini.nyachat.data.remote.GitHubUpdateChecker
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Dialog-dialog utama diekstrak dari MainActivity (TASK-1.3) — state di-hoist
// via parameter + callback, tanpa perubahan behavior.

@Composable
fun ApiKeyDialog(
    title: String,
    hint: String,
    initialKey: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    // F21: pertahankan draf kunci lintas rotasi.
    var key by rememberSaveable { mutableStateOf(initialKey) }
    var revealed by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    val strInvalid = stringResource(R.string.ux_api_key_invalid)

    // Validasi minimal: kunci kosong/terlalu pendek hampir pasti salah tempel.
    val isValid = key.trim().length >= 8
    val showInvalid = key.isNotBlank() && !isValid
    val visual: VisualTransformation =
        if (revealed) VisualTransformation.None else PasswordVisualTransformation()

    // F22: fokus tanpa delay sihir — LaunchedEffect langsung minta fokus.
    val keyFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { keyFocusRequester.requestFocus() }

    // Jaga-jaga bila pemanggil tidak menutup dialog: lepas guard setelah jeda.
    LaunchedEffect(isSaving) {
        if (isSaving) {
            kotlinx.coroutines.delay(1500)
            isSaving = false
        }
    }

    fun save() {
        if (isSaving) return
        val trimmed = key.trim()
        if (trimmed.length < 8) return
        isSaving = true
        // Umpan balik "kunci tersimpan" dipancarkan pemanggil lewat
        // AppSnackbar — tidak ada SnackbarHost lokal di file ini.
        onSave(trimmed)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(14.dp))
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text(stringResource(R.string.api_key_label)) },
                    singleLine = true,
                    isError = showInvalid,
                    visualTransformation = visual,
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
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(keyFocusRequester)
                )
                if (showInvalid) {
                    Text(
                        text = strInvalid,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { save() },
                enabled = isValid && !isSaving
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(stringResource(R.string.action_save))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSaving) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

/** Dialog tampil PIN workspace + tombol salin (L7: ClipData berlabel). */
@Composable
fun PinDisplayDialog(
    workspacePin: String?,
    onCopyPin: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(imageVector = Icons.Rounded.Pin, contentDescription = null) },
        title = { Text(stringResource(R.string.menu_pin)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.pin_settings_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(14.dp))
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = workspacePin ?: "-",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        letterSpacing = 8.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onCopyPin) {
                Text(stringResource(R.string.pin_settings_copy))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

/** Konfirmasi hapus SEMUA data lokal (Pengaturan → Hapus data). */
@Composable
fun ConfirmClearDataDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.confirm_clear_title)) },
        text = {
            Text(stringResource(R.string.confirm_clear_message))
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

/** Dialog logout: keluar tanpa hapus data / keluar & hapus semua data. */
@Composable
fun LogoutDialog(
    onKeepData: () -> Unit,
    onDeleteData: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.AutoMirrored.Rounded.ExitToApp, contentDescription = null) },
        title = { Text(stringResource(R.string.logout_dialog_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.logout_dialog_message),
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(
                    onClick = onKeepData,
                    // B28: TextButton default min-height 40dp → naik ke 48dp.
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                ) {
                    Text(
                        stringResource(R.string.logout_keep_data),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                TextButton(
                    onClick = onDeleteData,
                    // B28: target sentuh minimal 48dp.
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                ) {
                    Text(
                        stringResource(R.string.logout_delete_data),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
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

/** Konfirmasi ganti workspace (PIN berbeda) — tampil di SEMUA layar. */
@Composable
fun PinSwitchDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Pin, contentDescription = null) },
        title = { Text(stringResource(R.string.pin_switch_title)) },
        text = { Text(stringResource(R.string.pin_switch_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    stringResource(R.string.pin_switch_confirm),
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

/** Dialog "update tersedia" — aksi di-hoist (unduh & pasang langsung).
 *
 * Audit UI/UX r1.5.2: diseragamkan dengan pola dialog lain di app (ikon +
 * judul bertintakan primary, tombol aksi fill untuk CTA, teks sekunder
 * onSurfaceVariant). Saat mengunduh, dialog berubah jadi modal progres:
 * CircularProgressIndicator + LinearProgressIndicator dengan angka persen.
 */
@Composable
fun UpdateAvailableDialog(
    release: GitHubRelease,
    currentVersion: String,
    isDownloading: Boolean,
    progress: Float?,
    onAction: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!isDownloading) onDismiss() },
        icon = {
            Icon(
                imageVector = Icons.Rounded.SystemUpdate,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
        },
        title = {
            Text(
                stringResource(R.string.update_available_title),
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            // stringResource tidak bisa dipanggil di dalam lambda semantics.
            val checkingLabel = stringResource(R.string.ux_status_checking)
            if (isDownloading) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            // B14: status spinner dibacakan TalkBack.
                            modifier = Modifier
                                .size(28.dp)
                                .semantics { stateDescription = checkingLabel },
                            strokeWidth = 3.dp
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Column {
                            Text(
                                stringResource(R.string.update_downloading),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            if (progress != null) {
                                Text(
                                    stringResource(
                                        R.string.update_download_percent,
                                        (progress.coerceIn(0f, 1f) * 100).toInt()
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    LinearProgressIndicator(
                        progress = { progress?.coerceIn(0f, 1f) ?: 0f },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = stringResource(R.string.update_available_message, release.versionName),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = stringResource(R.string.update_current_version, currentVersion),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (release.body.isNotBlank()) {
                        Text(
                            text = stringResource(R.string.update_release_notes_title),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = release.body,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (!isDownloading) {
                Button(onClick = onAction) {
                    Text(stringResource(R.string.update_action))
                }
            }
        },
        dismissButton = {
            if (!isDownloading) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.update_later))
                }
            }
        }
    )
}

/** Dialog info hasil update — seragam dengan pola ikon+judul (C7/C8). */
@Composable
fun UpdateMessageDialog(
    message: String,
    onDismiss: () -> Unit
) {
    val strDownloadFailed = stringResource(R.string.update_download_failed)
    val strVerifyFailed = stringResource(R.string.ux_update_verify_failed)
    // C7/C8: detail teknis (sidik jari sertifikat / host URL / tanda tangan APK)
    // yang dilempar GitHubUpdateChecker TIDAK boleh tampil ke user — diganti
    // pesan ux_update_verify_failed yang aman & bisa ditindaklanjuti.
    val isTechnicalRejection = listOf(
        "sidik jari", "sertifikat", "tanda tangan", "host github",
        "url unduhan", "ditolak", "certificate", "signature"
    ).any { message.contains(it, ignoreCase = true) }
    val isError = isTechnicalRejection || message == strDownloadFailed
    val title = stringResource(
        if (isError) R.string.ux_update_error_title else R.string.ux_update_check_title
    )
    val body = if (isTechnicalRejection) strVerifyFailed else message
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Rounded.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
        },
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_ok))
            }
        }
    )
}

/** Nama file dengan timestamp: 20260803-143000 */
internal fun timestampForFile(): String =
    SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

/** Buka intent install untuk APK hasil unduhan (via FileProvider). */
internal fun installApk(context: Context, file: File) {
    // H4/M10 (audit 2026-11-19): guard pertahanan berlapis. Jalur install ini
    // HANYA boleh dipakai setelah GitHubUpdateChecker.verifyApkSignature() true.
    // Caller utama (MainOverlays) sudah memverifikasi; check di sini mencegah
    // caller baru memasang APK tak terverifikasi.
    check(GitHubUpdateChecker.verifyApkSignature(context, file)) {
        "APK gagal verifikasi tanda tangan — install dibatalkan"
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}
