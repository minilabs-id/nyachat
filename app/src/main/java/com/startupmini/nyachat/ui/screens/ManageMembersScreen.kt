package com.startupmini.nyachat.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.startupmini.nyachat.Constants
import com.startupmini.nyachat.R
import com.startupmini.nyachat.data.remote.FamilyMember
import com.startupmini.nyachat.data.remote.MembershipManager
import com.startupmini.nyachat.data.remote.JoinRequest
import com.startupmini.nyachat.ui.AppSnackbar
import kotlinx.coroutines.launch

/**
 * Layar "Kelola Anggota" — bottom sheet (konsisten dengan SettingsSheet).
 * - Pemilik: menyetujui/menolak permintaan bergabung, mengubah label & peran
 *   (pemilik/anggota), menghapus anggota.
 * - Anggota: hanya melihat daftar anggota (baca-saja).
 *
 * Audit motion (2026-08-12): sebelumnya full-screen Dialog dengan fade/zoom;
 * sekarang ModalBottomSheet — muncul slide dari bawah & tutup turun ke bawah
 * (sheetState.hide()), satu motion language dengan SettingsSheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageMembersScreen(
    pin: String,
    isOwner: Boolean,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val members by MembershipManager.members.collectAsState()
    val joinRequests by MembershipManager.joinRequests.collectAsState()
    // r1.2.3 (P1): foto avatar anggota (cache disk, map uid → path) —
    // ditampilkan di kartu anggota bila tersedia.
    val memberAvatars by MembershipManager.memberAvatarPaths.collectAsState()
    val myUid = remember { MembershipManager.currentUid() }

    // r1.6.0: nama & plan workspace (dari doc keluarga via listener) —
    // header menampilkan nama custom, baris paket menampilkan plan & kapasitas.
    val familyName by MembershipManager.familyName.collectAsState()
    val familyPlan by MembershipManager.familyPlan.collectAsState()

    var labelTarget by remember { mutableStateOf<FamilyMember?>(null) }
    var nameDialogOpen by remember { mutableStateOf(false) }
    var upgradeDialogOpen by remember { mutableStateOf(false) }
    // Audit workspace (2026-08-12): aksi destruktif (hapus/promote/demote)
    // WAJIB konfirmasi dulu — menghapus anggota = akses hilang permanen,
    // jadikan pemilik = transfer kendali. Sebelumnya dieksekusi langsung.
    var pendingAction by remember { mutableStateOf<MemberAction?>(null) }
    // R2-H5: SATU kanal umpan balik (AppSnackbar) — SnackbarHost lokal & Toast
    // dihapus. guard in-flight supaya dialog/konfirmasi hanya ditutup SETELAH
    // operasi selesai (B5/B6).
    var pendingReject by remember { mutableStateOf<JoinRequest?>(null) }
    var joinBusy by remember { mutableStateOf(false) }
    var actionInFlight by remember { mutableStateOf(false) }
    // B11: simpan FocusRequester pemicu & kembalikan fokus saat dialog ditutup.
    val renameFocusRequester = remember { FocusRequester() }
    var focusRestoreTrigger by remember { mutableStateOf<FocusRequester?>(null) }
    fun restoreTriggerFocus() {
        focusRestoreTrigger?.requestFocus()
        focusRestoreTrigger = null
    }
    val strApproveSuccess = stringResource(R.string.ux_approve_success)
    val strApproveFailed = stringResource(R.string.ux_approve_failed)
    val strRejectSuccess = stringResource(R.string.ux_reject_success)
    val strMemberRemoved = stringResource(R.string.ux_member_removed)
    val strRoleUpdated = stringResource(R.string.ux_member_role_updated)
    val strActionFailed = stringResource(R.string.ux_member_action_failed)
    val strWorkspaceRenamed = stringResource(R.string.ux_workspace_renamed)
    val strLabelUpdated = stringResource(R.string.ux_label_updated)
    val strUpgradeContact = stringResource(R.string.ux_upgrade_contact)

    // Satu motion language dengan SettingsSheet & sheet lain (audit motion
    // 2026-08-12): skipPartiallyExpanded = true → langsung buka penuh dari
    // bawah; tutup via sheetState.hide() → jendela turun ke bawah dulu,
    // baru onDismiss dipanggil.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    fun dismiss() {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            if (!sheetState.isVisible) onDismiss()
        }
    }

    ModalBottomSheet(
        onDismissRequest = ::dismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        // B-M20: handle bawaan dimatikan — custom 36x4dp handle dipakai di konten
        // supaya tidak ada dua handle bertumpuk.
        dragHandle = null,
        // Sheet hidup di area konten (berhenti di atas NavigationBar) — padding
        // navbar bawaan sheet dinolkan supaya tidak muncul celah.
        contentWindowInsets = { WindowInsets(0) },
    ) {
      // B-H2: pada tablet/landscape konten sheet dipusatkan (TopCenter), lalu
      // dibatasi 600dp — sebelumnya Box 600dp menempel di tepi kiri.
      Box(
          modifier = Modifier.fillMaxWidth(),
          contentAlignment = Alignment.TopCenter
      ) {
       Box(modifier = Modifier.fillMaxWidth().widthIn(max = 600.dp)) {
        // B-M18/M20/M21: satu Column konten dengan navigation-bar inset, drag
        // handle 36x4dp, dan tinggi maksimal 640dp (seragam dengan sheet lain).
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .heightIn(max = 640.dp)
        ) {
            // B-M20: drag handle — samakan dengan AddTransactionDialog & AiReportDialog.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .width(36.dp)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
                )
            }
            // Header diseragamkan penuh dengan SettingsSheet & ProfileAccountSheet
            // (audit 2026-08-12): padding 20/8 + ikon 20dp + titleMedium, TANPA
            // tombol X & garis bawah — dua sheet lain tidak memilikinya, sheet
            // ditutup via swipe/scrim/back. Sebelumnya header ini memakai X +
            // HorizontalDivider sehingga menyimpang dari pola dasar.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.Group,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        // r1.6.0: nama custom workspace; fallback default saat doc
                        // keluarga belum ter-snapshot (bootstrap).
                        text = familyName.ifBlank { stringResource(R.string.topbar_title) },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        // r1.6.0: kapasitas anggota (termasuk diri sendiri) vs limit plan.
                        text = stringResource(
                            R.string.manage_members_capacity,
                            members.size,
                            MembershipManager.memberLimit()
                        ),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // r1.6.0: owner bisa mengganti nama workspace (dialog di bawah).
                if (isOwner) {
                    IconButton(
                        onClick = {
                            focusRestoreTrigger = renameFocusRequester
                            nameDialogOpen = true
                        },
                        modifier = Modifier.focusRequester(renameFocusRequester)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Edit,
                            contentDescription = stringResource(R.string.manage_members_edit_name),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            LazyColumn(
                // B-M21: satu batas tinggi sheet dengan sheet lain (640dp) —
                // daftar panjang tetap scroll di dalam sheet.
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 640.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // r1.6.0: baris paket (owner saja) — plan aktif + kapasitas +
                // tombol upgrade placeholder (billing menyusul saat produksi).
                if (isOwner) {
                    item {
                        PlanCard(
                            plan = familyPlan,
                            memberCount = members.size,
                            limit = MembershipManager.memberLimit(),
                            onUpgrade = { upgradeDialogOpen = true }
                        )
                    }
                }
                // r1.6.0: section permintaan bergabung disembunyikan total saat
                // kosong (umpan balik tester) — baris kosong tidak tampil.
                if (isOwner && joinRequests.isNotEmpty()) {
                    item { SectionTitle(stringResource(R.string.manage_members_join_requests)) }
                    // Key harus unik di SELURUH LazyColumn (bukan per section):
                    // saat approve, member sudah masuk ke daftar `members`
                    // sementara join request-nya masih ada di `joinRequests`
                    // → UID sama muncul 2x → crash "Key was already used".
                    items(joinRequests, key = { "join_${it.uid}" }) { request ->
                        JoinRequestCard(
                            request = request,
                            busy = joinBusy,
                            onApprove = {
                                if (joinBusy) return@JoinRequestCard
                                joinBusy = true
                                scope.launch {
                                    val result = MembershipManager.approveJoin(pin, request)
                                    // F9: umpan balik eksplisit untuk tiap hasil.
                                    when (result) {
                                        MembershipManager.ApproveResult.SUCCESS ->
                                            AppSnackbar.show(strApproveSuccess)
                                        MembershipManager.ApproveResult.WORKSPACE_FULL ->
                                            // r1.6.0: workspace penuh (free=2) → arahkan
                                            // ke upgrade bila masih bisa; plan pro penuh
                                            // → tidak ada tawaran, beri tahu user.
                                            if (familyPlan != Constants.Plans.PRO) {
                                                upgradeDialogOpen = true
                                            } else {
                                                AppSnackbar.show(strApproveFailed)
                                            }
                                        MembershipManager.ApproveResult.FAILED ->
                                            AppSnackbar.show(strApproveFailed)
                                    }
                                    joinBusy = false
                                }
                            },
                            onReject = {
                                // F9: konfirmasi dulu sebelum menolak (permintaan
                                // harus dikirim ulang setelah ditolak).
                                if (!joinBusy) pendingReject = request
                            }
                        )
                    }
                    item { Spacer(modifier = Modifier.height(4.dp)) }
                }
                item { SectionTitle(stringResource(R.string.manage_members_list)) }

                // P3 (audit keanggotaan): empty-state untuk SEMUA peran — owner
                // juga dapat melihat "kosong" saat snapshot belum datang / anggota
                // belum ada (sebelumnya hanya non-owner).
                if (members.isEmpty()) {
                    item {
                        // B-M22: empty state ditingkatkan dari teks 13sp polos
                        // menjadi Card + ikon 48dp + judul + hint (pola RekapList),
                        // memakai string yang sudah ada — tanpa string baru.
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(36.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Group,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = stringResource(R.string.manage_members_list),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = stringResource(R.string.manage_members_empty),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                } else {
                    items(members, key = { "member_${it.uid}" }) { member ->
                        MemberCard(
                            member = member,
                            isSelf = member.uid == myUid,
                            isOwner = isOwner,
                            avatarPath = memberAvatars[member.uid],
                            // B11: teruskan FocusRequester tombol pemicu supaya
                            // fokus dikembalikan saat dialog ditutup.
                            onEditLabel = { trigger ->
                                focusRestoreTrigger = trigger
                                labelTarget = member
                            },
                            onToggleRole = { trigger ->
                                // Konfirmasi dulu — promote/demote mengubah
                                // kendali workspace.
                                focusRestoreTrigger = trigger
                                pendingAction = MemberAction.ToggleRole(member)
                            },
                            onRemove = { trigger ->
                                // Konfirmasi dulu — menghapus = akses hilang.
                                focusRestoreTrigger = trigger
                                pendingAction = MemberAction.Remove(member)
                            }
                        )
                    }
                }
            }
        }
       }
      }
    }

    labelTarget?.let { target ->
        LabelEditDialog(
            member = target,
            busy = actionInFlight,
            onDismiss = {
                labelTarget = null
                restoreTriggerFocus()
            },
            onSave = { newLabel ->
                if (actionInFlight) return@LabelEditDialog
                actionInFlight = true
                scope.launch {
                    // R2-H3: pakai HASIL nyata — jangan runCatching{}.isSuccess
                    // pada blok yang tidak pernah melempar.
                    val ok = MembershipManager.setMemberRole(pin, target.uid, target.role, newLabel)
                    AppSnackbar.show(if (ok) strLabelUpdated else strActionFailed)
                    actionInFlight = false
                    labelTarget = null // B5: tutup HANYA setelah hasil diketahui
                    restoreTriggerFocus()
                }
            }
        )
    }

    // Konfirmasi aksi destruktif keanggotaan (audit workspace): hapus anggota,
    // jadikan pemilik, atau jadikan anggota.
    pendingAction?.let { action ->
        val member = when (action) {
            is MemberAction.Remove -> action.member
            is MemberAction.ToggleRole -> action.member
        }
        val (titleRes, msgRes) = when (action) {
            is MemberAction.Remove -> {
                R.string.manage_members_confirm_remove_title to R.string.manage_members_confirm_remove_msg
            }
            is MemberAction.ToggleRole -> {
                if (member.isOwner) {
                    R.string.manage_members_confirm_demote_title to R.string.manage_members_confirm_demote_msg
                } else {
                    R.string.manage_members_confirm_promote_title to R.string.manage_members_confirm_promote_msg
                }
            }
        }
        // Fallback nama (reviewer): jangan sampai pesan konfirmasi punya argumen
        // kosong saat member tanpa label & nama.
        val memberDisplayName = member.label.ifBlank { member.name }.ifBlank {
            stringResource(R.string.sender_anggota)
        }
        val confirmRes = when (action) {
            is MemberAction.Remove -> R.string.manage_members_remove
            is MemberAction.ToggleRole -> {
                if (member.isOwner) R.string.manage_members_make_member
                else R.string.manage_members_make_owner
            }
        }
        AlertDialog(
            // F11: saat operasi berjalan, Back/tap-di-luar tidak boleh menutup
            // dialog sebelum hasilnya diketahui.
            onDismissRequest = {
                if (!actionInFlight) {
                    pendingAction = null
                    restoreTriggerFocus()
                }
            },
            title = { Text(stringResource(titleRes)) },
            text = { Text(stringResource(msgRes, memberDisplayName)) },
            confirmButton = {
                TextButton(
                    enabled = !actionInFlight,
                    onClick = {
                        if (actionInFlight) return@TextButton
                        actionInFlight = true
                        scope.launch {
                            // R2-H3: method kini mengembalikan Boolean hasil operasi.
                            val ok = when (action) {
                                is MemberAction.Remove ->
                                    MembershipManager.removeMember(pin, member.uid)
                                is MemberAction.ToggleRole ->
                                    MembershipManager.setMemberRole(
                                        pin, member.uid,
                                        if (member.isOwner) MembershipManager.ROLE_MEMBER
                                        else MembershipManager.ROLE_OWNER
                                    )
                            }
                            AppSnackbar.show(
                                when {
                                    !ok -> strActionFailed
                                    action is MemberAction.Remove -> strMemberRemoved
                                    else -> strRoleUpdated
                                }
                            )
                            actionInFlight = false
                            pendingAction = null
                            restoreTriggerFocus()
                        }
                    }
                ) {
                    Text(
                        text = stringResource(confirmRes),
                        color = if (action is MemberAction.Remove) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        pendingAction = null
                        restoreTriggerFocus()
                    },
                    enabled = !actionInFlight
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    // F9: konfirmasi tolak permintaan bergabung + umpan balik hasilnya.
    pendingReject?.let { req ->
        AlertDialog(
            onDismissRequest = {
                if (!joinBusy) {
                    pendingReject = null
                    restoreTriggerFocus()
                }
            },
            title = { Text(stringResource(R.string.ux_reject_confirm_title)) },
            text = { Text(stringResource(R.string.ux_reject_confirm_body)) },
            confirmButton = {
                TextButton(
                    enabled = !joinBusy,
                    onClick = {
                        if (joinBusy) return@TextButton
                        joinBusy = true
                        scope.launch {
                            val ok = MembershipManager.rejectJoin(pin, req.uid)
                            AppSnackbar.show(if (ok) strRejectSuccess else strActionFailed)
                            joinBusy = false
                            pendingReject = null
                            restoreTriggerFocus()
                        }
                    }
                ) { Text(stringResource(R.string.manage_members_reject)) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        pendingReject = null
                        restoreTriggerFocus()
                    },
                    enabled = !joinBusy
                ) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    // r1.6.0: rename nama workspace (owner) — nama kosong diblokir tombol Simpan.
    // B5: actionInFlight + AppSnackbar, dialog ditutup hanya setelah hasil nyata.
    if (nameDialogOpen) {
        WorkspaceNameDialog(
            currentName = familyName.ifBlank { stringResource(R.string.topbar_title) },
            busy = actionInFlight,
            onDismiss = {
                nameDialogOpen = false
                restoreTriggerFocus()
            },
            onSave = { newName ->
                if (actionInFlight) return@WorkspaceNameDialog
                actionInFlight = true
                scope.launch {
                    val ok = MembershipManager.setFamilyName(pin, newName)
                    AppSnackbar.show(if (ok) strWorkspaceRenamed else strActionFailed)
                    actionInFlight = false
                    nameDialogOpen = false
                    restoreTriggerFocus()
                }
            }
        )
    }

    // R2-H2: TIDAK ada alur pembelian di aplikasi. Upgrade hanya bisa terjadi
    // bila entitlement Pro sudah aktif di server — dialog ini menjelaskan itu
    // dan menawarkan pemeriksaan ulang (bukan transaksi palsu).
    if (upgradeDialogOpen) {
        val strUpgradeSuccess = stringResource(R.string.plan_pro_label) + " aktif"
        AlertDialog(
            onDismissRequest = {
                if (!actionInFlight) {
                    upgradeDialogOpen = false
                    restoreTriggerFocus()
                }
            },
            title = { Text(stringResource(R.string.plan_upgrade_title)) },
            text = {
                Column {
                    Text(
                        stringResource(
                            R.string.plan_upgrade_body,
                            Constants.Limits.FREE_MAX_MEMBERS,
                            Constants.Limits.PRO_MAX_MEMBERS
                        )
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = strUpgradeContact,
                        // B-L: fontSize literal 12.sp → token tipografi (D-L5).
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !actionInFlight,
                    onClick = {
                        if (actionInFlight) return@TextButton
                        actionInFlight = true
                        scope.launch {
                            // setPlan server-authoritative: true hanya bila
                            // entitlement Pro terverifikasi. Tanpa entitlement →
                            // pesan ux_upgrade_contact (bukan purchase flow).
                            val ok = MembershipManager.setPlan(pin, Constants.Plans.PRO)
                            AppSnackbar.show(if (ok) strUpgradeSuccess else strUpgradeContact)
                            actionInFlight = false
                            upgradeDialogOpen = false
                            restoreTriggerFocus()
                        }
                    }
                ) { Text(stringResource(R.string.plan_upgrade_now)) }
            },
            dismissButton = {
                TextButton(
                    enabled = !actionInFlight,
                    onClick = {
                        upgradeDialogOpen = false
                        restoreTriggerFocus()
                    }
                ) {
                    Text(stringResource(R.string.plan_upgrade_later))
                }
            }
        )
    }
}

/** Aksi keanggotaan yang butuh konfirmasi user (audit workspace). */
private sealed interface MemberAction {
    data class Remove(val member: FamilyMember) : MemberAction
    data class ToggleRole(val member: FamilyMember) : MemberAction
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        // B-L: fontSize literal 13.sp → token tipografi (min 12sp, D-L5).
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 2.dp)
    )
}

@Composable
private fun JoinRequestCard(
    request: JoinRequest,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    // F9: kedua tombol dinonaktifkan saat aksi masih berjalan.
    busy: Boolean = false
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AvatarCircle(name = request.name.ifBlank { "?" }, request.uid)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = request.name.ifBlank { request.email },
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    // C11: nama pemohon panjang tidak boleh menimpa tombol aksi.
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (request.name.isNotBlank() && request.email.isNotBlank()) {
                    Text(
                        text = request.email,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        // B-M12: email panjang dipangkas, tidak melewati kartu.
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            TextButton(onClick = onApprove, enabled = !busy) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    // F13: width(18.dp) meregangkan ikon — pakai size().
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.manage_members_approve), fontSize = 12.sp)
            }
            TextButton(onClick = onReject, enabled = !busy) {
                Text(
                    stringResource(R.string.manage_members_reject),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun MemberCard(
    member: FamilyMember,
    isSelf: Boolean,
    isOwner: Boolean,
    // B11: callback membawa FocusRequester pemicu untuk restorasi fokus.
    onEditLabel: (FocusRequester) -> Unit,
    onToggleRole: (FocusRequester) -> Unit,
    onRemove: (FocusRequester) -> Unit,
    // r1.2.3 (P1): path foto avatar anggota (null → inisial berwarna).
    avatarPath: String? = null
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (avatarPath != null) {
                com.startupmini.nyachat.ui.util.AvatarImage(
                    name = member.label.ifBlank { member.name.ifBlank { "?" } },
                    size = 40,
                    photoPath = avatarPath,
                    backgroundColor = MaterialTheme.colorScheme.primaryContainer,
                    textStyle = MaterialTheme.typography.bodyMedium
                )
            } else {
                AvatarCircle(name = member.label.ifBlank { member.name.ifBlank { "?" } }, member.uid)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = member.label.ifBlank { member.name.ifBlank { stringResource(R.string.sender_anggota) } },
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        // B-M12: nama/label panjang dipangkas supaya tidak
                        // mendorong badge "Anda" keluar dari kartu.
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (isSelf) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.manage_members_you),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (member.isOwner) {
                        Icon(
                            imageVector = Icons.Rounded.Star,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            // C5: width() meregangkan ikon — pakai size().
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = stringResource(R.string.manage_members_role_owner),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.manage_members_role_member),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (member.name.isNotBlank()) {
                        Text(
                            text = " • ${member.name}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            // B-M12: sisa ruang dipangkas, nama tidak menimpa
                            // tombol overflow di ujung kartu.
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                }
            }

            // r1.6.0 (umpan balik tester): aksi member dirapikan ke SATU menu
            // overflow (MoreVert) — sebelumnya tombol teks panjang + ikon edit
            // membuat kartu berantakan. Menu: Ubah Label, peran (pemilik/anggota),
            // Hapus (guard: owner tidak bisa dihapus langsung).
            if (isOwner && !isSelf) {
                var menuOpen by remember { mutableStateOf(false) }
                // B11: fokus tombol pemicu disimpan untuk dikembalikan setelah
                // dialog label/konfirmasi ditutup.
                val actionsFocusRequester = remember { FocusRequester() }
                Box(modifier = Modifier) {
                    IconButton(
                        onClick = { menuOpen = true },
                        modifier = Modifier.focusRequester(actionsFocusRequester)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.MoreVert,
                            // F12: sebut nama anggota supaya TalkBack tidak hanya
                            // membaca "Aksi" generik.
                            contentDescription = stringResource(
                                R.string.ux_member_actions_for,
                                member.label.ifBlank { member.name }
                            ),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(R.string.manage_members_edit_label),
                                    fontSize = 13.sp
                                )
                            },
                            onClick = {
                                menuOpen = false
                                onEditLabel(actionsFocusRequester)
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (member.isOwner) {
                                        stringResource(R.string.manage_members_make_member)
                                    } else {
                                        stringResource(R.string.manage_members_make_owner)
                                    },
                                    fontSize = 13.sp
                                )
                            },
                            onClick = {
                                menuOpen = false
                                onToggleRole(actionsFocusRequester)
                            }
                        )
                        // Audit workspace: owner TIDAK bisa langsung dihapus — harus
                        // di-demote dulu menjadi anggota. Menghapus owner = workspace
                        // berisiko kehilangan semua kendali & tidak ada yang menyetujui
                        // permintaan bergabung.
                        if (!member.isOwner) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.manage_members_remove),
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                },
                                onClick = {
                                    menuOpen = false
                                    onRemove(actionsFocusRequester)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AvatarCircle(name: String, seed: String) {
    val initial = name.trim().firstOrNull()?.uppercase() ?: "?"
    val color = androidx.compose.ui.graphics.Color.hsl(
        hue = (seed.hashCode() % 360).toFloat().let { if (it < 0) it + 360 else it },
        saturation = 0.5f,
        lightness = 0.5f
    )
    // Teks adaptif (audit WCAG): gelap saat bg terang, putih saat bg gelap —
    // inisial tetap terbaca di avatar hue kuning/hijau/cyan (putih hanya 1.96:1).
    // Dekoratif: nama anggota sudah tercetak di sebelah avatar, jadi inisial
    // tidak perlu dibacakan TalkBack (P3-2).
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(color)
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initial,
            color = if (color.luminance() > 0.22f) androidx.compose.ui.graphics.Color(0xFF202124) else androidx.compose.ui.graphics.Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp
        )
    }
}

@Composable
private fun PlanCard(
    plan: String,
    memberCount: Int,
    limit: Int,
    onUpgrade: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.plan_row_label),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (plan == Constants.Plans.PRO) {
                            stringResource(R.string.plan_pro_label)
                        } else {
                            stringResource(R.string.plan_free_label)
                        },
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        // Kapasitas: member aktif vs limit plan (mis. "2/6 anggota").
                        text = stringResource(R.string.manage_members_capacity, memberCount, limit),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            // r1.6.0: upgrade placeholder — langsung set plan 'pro' (billing
            // Play Billing diterapkan saat produksi).
            if (plan != Constants.Plans.PRO) {
                TextButton(onClick = onUpgrade) {
                    Text(
                        stringResource(R.string.plan_upgrade_button),
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun WorkspaceNameDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    // B5/B6: tombol dinonaktifkan saat operasi berjalan (anti double-submit).
    busy: Boolean = false
) {
    // F21: draf nama dipertahankan lintas rotasi.
    var text by rememberSaveable { mutableStateOf(currentName) }

    // F3/F22 (audit focus order): fokus tanpa magic delay.
    val nameFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { nameFocusRequester.requestFocus() }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.workspace_name_dialog_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.workspace_name_dialog_hint)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(nameFocusRequester)
            )
        },
        confirmButton = {
            Button(
                onClick = { if (!busy) onSave(text.trim()) },
                enabled = text.isNotBlank() && !busy
            ) {
                Text(stringResource(R.string.workspace_name_dialog_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
private fun LabelEditDialog(
    member: FamilyMember,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    // B5/B6: tombol dinonaktifkan saat operasi berjalan (anti double-submit).
    busy: Boolean = false
) {
    // F21: draf label dipertahankan lintas rotasi.
    var text by rememberSaveable { mutableStateOf(member.label) }

    // F3/F22 (audit focus order): fokus tanpa magic delay.
    val labelFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { labelFocusRequester.requestFocus() }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.manage_members_label_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.manage_members_label_hint)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(labelFocusRequester)
            )
        },
        confirmButton = {
            Button(
                onClick = { if (!busy) onSave(text.trim()) },
                enabled = text.isNotBlank() && !busy
            ) {
                Text(stringResource(R.string.manage_members_label_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}
