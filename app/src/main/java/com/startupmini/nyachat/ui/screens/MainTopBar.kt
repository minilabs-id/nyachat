package com.startupmini.nyachat.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Tooltip
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.startupmini.nyachat.R
import com.startupmini.nyachat.ui.util.AvatarImage
import com.startupmini.nyachat.ui.util.avatarColorFor

/**
 * TopAppBar utama: avatar bertumpuk anggota yang SEDANG ONLINE + judul + aksi
 * (kelola anggota & pengaturan).
 *
 * r1.6.0 (presence): sumber avatar berubah — dari "pengirim pesan" menjadi
 * "anggota yang online" (daftar [onlineMemberNames] sudah difilter & diurutkan
 * di MainActivity via MembershipManager.onlineMembers, self didahulukan).
 * Subtitle menampilkan jumlah online/total. Ukuran avatar diturunkan (28dp)
 * supaya 6 anggota online (cap plan pro) tetap muat bertumpuk.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainTopBar(
    // Nama tampilan anggota yang online (self sudah pasti termasuk, didahulukan).
    onlineMemberNames: List<String>,
    // Total anggota di workspace (untuk subtitle "N/M online").
    totalMembers: Int,
    userName: String?,
    memberAvatarPaths: Map<String, String> = emptyMap(),
    // r1.6.0: nama custom workspace (nilai default saat doc keluarga belum ada).
    familyName: String = "",
    onManageMembers: () -> Unit,
    onSettings: () -> Unit
) {
    val avatars = onlineMemberNames.ifEmpty {
        userName?.let { listOf(it) } ?: emptyList()
    }

    TopAppBar(
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                // B-M19: TopAppBar sudah memberi slot title lebar penuh; bobot
                // pada Column (di RowScope) yang menyusut sebelum menimpa actions.
                modifier = Modifier.padding(start = 0.dp)
            ) {
                StackedAvatars(
                    senders = avatars,
                    avatarPaths = memberAvatarPaths,
                    modifier = Modifier.padding(end = 10.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        // r1.6.0: nama workspace aktif; fallback default bila doc
                        // keluarga belum sempat ter-snapshot (bootstrap).
                        text = familyName.ifBlank { stringResource(R.string.topbar_title) },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (onlineMemberNames.size > 1 && totalMembers > 0) {
                        Text(
                            text = stringResource(R.string.topbar_online_count, onlineMemberNames.size, totalMembers),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            // B-M19: subtitle ikut terpotong, tidak menimpa ikon aksi.
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else if (onlineMemberNames.size == 1) {
                        Text(
                            text = onlineMemberNames.first(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        },
        actions = {
            // L8: tooltip pada aksi ikon — label muncul saat hover/long-press.
            // C18: isi tooltip TIDAK boleh jadi node TalkBack terpisah — label
            // sudah dibawa IconButton lewat contentDescription. Bersihkan
            // semantics teks tooltip agar tidak ada pengumuman dobel.
            Tooltip(
                text = {
                    Text(
                        stringResource(R.string.manage_members_title),
                        modifier = Modifier.clearAndSetSemantics {}
                    )
                }
            ) {
                IconButton(
                    onClick = onManageMembers,
                    modifier = Modifier.testTag("topbar_manage_members")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Group,
                        contentDescription = stringResource(R.string.manage_members_title),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            Tooltip(
                text = {
                    Text(
                        stringResource(R.string.action_settings),
                        modifier = Modifier.clearAndSetSemantics {}
                    )
                }
            ) {
                IconButton(
                    onClick = onSettings,
                    modifier = Modifier.testTag("topbar_settings")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Settings,
                        contentDescription = stringResource(R.string.action_settings),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            actionIconContentColor = MaterialTheme.colorScheme.onSurface
        )
    )
}

/**
 * Avatar bertumpuk (stacked) bergaya Twitter/X group chat.
 * Setiap avatar adalah lingkaran berwarna unik dari hash nama pengirim,
 * dengan inisial huruf di tengah. Avatar berikutnya sedikit overlap ke kiri.
 * r1.6.0 (presence): menampilkan hingga 6 avatar (cap plan pro) supaya seluruh
 * anggota yang online terlihat sekaligus.
 */
@Composable
private fun StackedAvatars(
    senders: List<String>,
    modifier: Modifier = Modifier,
    avatarPaths: Map<String, String> = emptyMap(),
    avatarSize: Int = 28,
    overlapDp: Int = 10,
    maxAvatars: Int = 6
) {
    if (senders.isEmpty()) return
    val show = senders.take(maxAvatars)
    val totalWidth = (avatarSize + (show.size - 1) * (avatarSize - overlapDp)).dp

    Box(
        modifier = modifier
            .width(totalWidth)
            .height(avatarSize.dp)
    ) {
        show.forEachIndexed { index, name ->
            val photoPath = avatarPaths[name]
            if (photoPath != null) {
                // r1.2.3 (P1): tampilkan FOTO avatar bila tersedia.
                AvatarImage(
                    name = name,
                    size = avatarSize,
                    photoPath = photoPath,
                    modifier = Modifier
                        .offset(x = (index * (avatarSize - overlapDp)).dp)
                        .zIndex((show.size - index).toFloat())
                        // B-L: samakan cincin avatar foto dengan inisial.
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                    backgroundColor = avatarColorFor(name),
                    textStyle = MaterialTheme.typography.labelMedium
                )
            } else {
                val avatarColor = avatarColorFor(name)
                val initials = name.take(2).uppercase()
                // Teks adaptif (audit WCAG): inisial gelap saat bg avatar terang (orange/sky/
                // hijau — putih hanya ~2.3-3:1), putih saat bg gelap (indigo/ungu/crimson).
                val fgColor = if (avatarColor.luminance() > 0.22f) Color(0xFF202124) else Color.White
                Box(
                    modifier = Modifier
                        .size(avatarSize.dp)
                        .offset(x = (index * (avatarSize - overlapDp)).dp)
                        .zIndex((show.size - index).toFloat())
                        .clip(CircleShape)
                        .drawBehind { drawCircle(color = avatarColor) }
                        // B-L: ganti overlay putih (terlihat sebagai "white ring"
                        // dan hilang di dark) dengan border outlineVariant tema.
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = initials,
                        color = fgColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = (avatarSize / 2.8).sp,
                        maxLines = 1
                    )
                }
            }
        }
    }
}
