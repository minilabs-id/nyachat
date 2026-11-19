package com.startupmini.nyachat.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.minimumInteractiveComponentSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClickLabel
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.startupmini.nyachat.Constants
import com.startupmini.nyachat.R
import com.startupmini.nyachat.data.local.FinancialTransaction
import com.startupmini.nyachat.ui.theme.LocalSemanticColors
import com.startupmini.nyachat.ui.theme.Motion
import com.startupmini.nyachat.ui.util.idrCurrencyFormat
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

// Daftar & filter riwayat transaksi layar Rekap — diekstrak dari RekapScreen.kt
// (TASK-1.2.2) tanpa perubahan behavior. State di-hoist ke RekapScreen.

/**
 * Navigasi bulan (‹ bulan ›): dari "Semua", ‹ masuk ke bulan berjalan; ›
 * dinonaktifkan di bulan berjalan supaya tidak melihat masa depan.
 */
@Composable
internal fun RekapMonthNav(
    selectedMonth: Pair<Int, Int>?,
    currentYearMonth: Pair<Int, Int>,
    monthLabel: String?,
    onStep: (Int) -> Unit,
    onClear: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { onStep(-1) },
                modifier = Modifier.testTag("rekap_prev_month")
            ) {
                Icon(Icons.Rounded.ChevronLeft, stringResource(R.string.rekap_prev_month_desc))
            }
            // Audit motion: crossfade halus saat label bulan berubah ("Semua" ⇄
            // nama bulan) — sebelumnya teks berganti instan saat angka di bawahnya
            // kini beranimasi; teks ikut melandai agar konsisten.
            // Audit motion (2026-08-12): diselaraskan ke Motion.base() (250ms) —
            // sama dengan angka saldo/pemasukan/pengeluaran di layar yang sama
            // (sebelumnya fast 200ms = durasi beda untuk fungsi setara).
            AnimatedContent(
                targetState = monthLabel ?: stringResource(R.string.rekap_month_all),
                transitionSpec = {
                    fadeIn(animationSpec = Motion.base()) togetherWith
                        fadeOut(animationSpec = Motion.base())
                },
                label = "monthLabel",
                modifier = Modifier
                    .weight(1f)
                    .testTag("rekap_month_label")
            ) { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    // M9/C16: nama bulan panjang tidak boleh memaksa tombol navigasi
                    // keluar layar — beri dua baris sebelum elipsis (fontScale besar).
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(
                onClick = { onStep(1) },
                enabled = selectedMonth != null && selectedMonth != currentYearMonth,
                modifier = Modifier.testTag("rekap_next_month")
            ) {
                Icon(Icons.Rounded.ChevronRight, stringResource(R.string.rekap_next_month_desc))
            }
        }
        // B-M3: chip "Semua" dipindah ke baris sendiri. Di baris yang sama chip
        // menggeser label (weight 1f) ke kiri saat muncul; dengan baris terpisah
        // label bulan tetap terpusat di antara tombol ‹/› di semua keadaan.
        if (selectedMonth != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    onClick = onClear,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    // B28: target sentuh minimal 48dp.
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("rekap_month_all_button")
                ) {
                    Text(stringResource(R.string.rekap_month_all))
                }
            }
        }
    }
}

/**
 * Header riwayat sticky (audit P1.3): chip saldo ringkas saat banner ter-scroll
 * keluar + judul + tombol catat manual + segmented filter + chip filter
 * kategori aktif. State (tab/filter/kategori) di-hoist ke RekapScreen.
 */
@Composable
internal fun RekapFilterHeader(
    bannerVisible: Boolean,
    balance: Double,
    balanceColor: Color,
    currencyFormat: NumberFormat,
    selectedFilterTab: Int,
    onTabSelected: (Int) -> Unit,
    selectedCategory: String?,
    onClearCategory: () -> Unit,
    onAddTransactionClicked: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(bottom = 4.dp)
    ) {
        // Chip saldo ringkas — muncul saat banner ter-scroll keluar
        // supaya saldo tetap terpantau di riwayat panjang (item 2).
        // PENTING: TIDAK pakai AnimatedVisibility — BUG-05 compose-bom 2026.06
        // tidak me-layout konten AnimatedVisibility di konteks tertentu
        // (diverifikasi live: chip filter kategori tidak pernah ter-render).
        // B-L: animateContentSize di stickyHeader bikin jitter saat scroll cepat
        // (tinggi header berubah tiap frame) → slot pakai tinggi TETAP.
        Column(
            modifier = Modifier.height(if (!bannerVisible) 48.dp else 0.dp)
        ) {
            if (!bannerVisible) {
                Surface(
                    shape = RoundedCornerShape(Constants.Ui.CORNER_M.dp),
                    color = MaterialTheme.colorScheme.surface,
                    // Tanpa shadow — konsisten dengan prinsip chat (audit 2026-08-12).
                    shadowElevation = 0.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("rekap_balance_chip")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.balance_title),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = currencyFormat.format(balance),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = balanceColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.rekap_history_header),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                // B-M2: judul tidak boleh menimpa tombol "Tambah" di layar sempit.
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            OutlinedButton(
                onClick = onAddTransactionClicked,
                shape = RoundedCornerShape(Constants.Ui.CORNER_M.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                // B28: tinggi interaksi minimal 48dp walau padding konten kecil.
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("manual_add_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    stringResource(R.string.rekap_add),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Custom Segmented Control
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
            shape = RoundedCornerShape(Constants.Ui.CORNER_M.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                val filterOptions = listOf(
                    0 to stringResource(R.string.filter_all),
                    1 to stringResource(R.string.filter_expense),
                    2 to stringResource(R.string.filter_income)
                )

                filterOptions.forEach { (index, label) ->
                    val isSelected = selectedFilterTab == index
                    val segBg by animateColorAsState(
                        // B-M17: `surface` di atas `surfaceVariant` nyaris tak
                        // terlihat → pakai secondaryContainer agar pilihan jelas
                        // di light & dark.
                        targetValue = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                        animationSpec = Motion.fast(),
                        label = "segBg"
                    )
                    val segText by animateColorAsState(
                        targetValue = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        animationSpec = Motion.fast(),
                        label = "segText"
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(Constants.Ui.CORNER_S.dp))
                            .background(segBg)
                            // B-M17: border halus memperkuat batas segmen aktif.
                            .then(
                                if (isSelected) {
                                    Modifier.border(
                                        1.dp,
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                                        RoundedCornerShape(Constants.Ui.CORNER_S.dp)
                                    )
                                } else Modifier
                            )
                            // P3-2: selectable + Role.Tab — TalkBack mengumumkan
                            // "Terpilih"/"Tidak terpilih" (sebelumnya hanya clickable
                            // tanpa status pilihan).
                            .selectable(
                                selected = isSelected,
                                role = Role.Tab,
                                onClick = { onTabSelected(index) }
                            )
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = segText
                        )
                    }
                }
            }
        }

        // Chip filter kategori aktif (dari tap baris breakdown — item 3).
        // Tap chip untuk menghapus filter dan kembali ke semua kategori.
        // Muncul/hilang DIANIMASIKAN tingginya (sebelumnya snap = layout jump).
        // Pakai animateContentSize + if (bukan AnimatedVisibility — BUG-05,
        // lihat komentar chip saldo di atas; chip ini DIVERIFIKASI ter-render).
        Column(modifier = Modifier.animateContentSize(animationSpec = Motion.fast())) {
            val activeCategory = selectedCategory
            if (activeCategory != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    onClick = onClearCategory,
                    shape = RoundedCornerShape(Constants.Ui.CORNER_S.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.testTag("rekap_category_filter_chip")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.rekap_category_filter_active, activeCategory),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            // B-L: nama kategori panjang dipangkas, tidak
                            // meregangkan chip filter di sticky header.
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.rekap_category_filter_clear),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * State kosong riwayat — H3: membedakan "benar-benar kosong" dengan
 * "tidak ada hasil untuk filter aktif" (lengkap dengan aksi hapus filter).
 */
@Composable
internal fun RekapEmptyState(
    isFiltered: Boolean = false,
    onClearFilter: () -> Unit = {}
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(Constants.Ui.CORNER_L.dp),
        // Tanpa shadow — konsisten dengan prinsip chat (audit 2026-08-12).
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(36.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ReceiptLong,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(
                    if (isFiltered) R.string.ux_no_filter_results else R.string.rekap_empty_title
                ),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (isFiltered) {
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(
                    onClick = onClearFilter,
                    shape = RoundedCornerShape(Constants.Ui.CORNER_M.dp),
                    modifier = Modifier.testTag("rekap_clear_filter_button")
                ) {
                    Text(stringResource(R.string.ux_clear_filter))
                }
            } else {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.rekap_empty_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Header grup tanggal pada riwayat transaksi (audit P1.3).
 * Gaya pill (audit konsistensi 2026-08-12): DISERAGAMKAN dengan DateSeparator
 * di menu Chat — surfaceVariant rounded 12dp, label kecil — supaya pembatas
 * tanggal terasa satu keluarga di kedua layar.
 */
@Composable
internal fun TransactionDayHeader(label: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun TransactionItemCard(
    transaction: FinancialTransaction,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    onEdit: () -> Unit = {},
    // L5: formatter di-hoist sekali di RekapScreen dan dipakai ulang (default
    // tetap disediakan supaya pemanggil langsung seperti snapshot test aman).
    currencyFormat: NumberFormat = remember { idrCurrencyFormat() }
) {
    val isIncome = transaction.type == Constants.TransactionTypes.INCOME
    val semantic = LocalSemanticColors.current

    val dateFormat = remember { SimpleDateFormat("dd MMM, HH:mm", Locale.forLanguageTag("id-ID")) }

    val amountColor = if (isIncome) semantic.income else semantic.expense
    val amountPrefix = if (isIncome) "+ " else "- "

    val loggedByTag = when (transaction.loggedBy) {
        "Bendahara" -> stringResource(R.string.tag_bendahara)
        "Anggota" -> stringResource(R.string.tag_anggota)
        "Ketua" -> stringResource(R.string.tag_ketua)
        "ISTRI" -> stringResource(R.string.tag_istri)
        "SUAMI" -> stringResource(R.string.tag_suami)
        else -> stringResource(R.string.tag_other, transaction.loggedBy)
    }

    // SwipeToDismissBox: swipe kiri → Delete, swipe kanan → Edit
    val swipeState = rememberSwipeToDismissBoxState(
        confirmValueChange = { dismissValue ->
            when (dismissValue) {
                SwipeToDismissBoxValue.EndToStart -> {
                    onDelete()
                    false // false = jangan auto-dismiss, konfirmasi via dialog
                }
                SwipeToDismissBoxValue.StartToEnd -> {
                    onEdit()
                    false
                }
                else -> false
            }
        },
        positionalThreshold = { totalDistance -> totalDistance * 0.35f }
    )

    // Reset state setelah aksi dipicu (biar item tidak menghilang)
    val scope = rememberCoroutineScope()
    LaunchedEffect(swipeState.currentValue) {
        if (swipeState.currentValue != SwipeToDismissBoxValue.Settled) {
            scope.launch { swipeState.reset() }
        }
    }

    SwipeToDismissBox(
        state = swipeState,
        modifier = modifier,
        enableDismissFromStartToEnd = true,  // kanan → Edit
        enableDismissFromEndToStart = true,  // kiri → Delete
        backgroundContent = {
            // Latar belakang yang terungkap saat swipe
            val direction = swipeState.dismissDirection
            val isToDelete = direction == SwipeToDismissBoxValue.EndToStart
            val isToEdit = direction == SwipeToDismissBoxValue.StartToEnd
            val bgColorTarget = when {
                // B-M16: token semantik mode-aware (bukan literal merah terang
                // yang silau di tema gelap).
                isToDelete -> semantic.expenseBg
                isToEdit -> MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                else -> Color.Transparent
            }
            // Audit motion: warna latar swipe di-animasi halus saat arah berubah
            // (Edit ⇄ Hapus) — sebelumnya warna ganti instan di tengah gestur.
            val bgColor by animateColorAsState(
                targetValue = bgColorTarget,
                animationSpec = Motion.fast(),
                label = "swipeBg"
            )
            val icon = if (isToDelete) Icons.Rounded.Delete else Icons.Rounded.Edit
            val iconTint = if (isToDelete) semantic.expense else MaterialTheme.colorScheme.primary
            val align = if (isToDelete) Alignment.CenterEnd else Alignment.CenterStart

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(Constants.Ui.CORNER_L.dp))
                    .background(bgColor),
                contentAlignment = align
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier
                        .padding(horizontal = 20.dp)
                        .size(24.dp)
                )
            }
        }
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            // Tanpa shadow — konsisten dengan prinsip chat (audit 2026-08-12).
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            shape = RoundedCornerShape(Constants.Ui.CORNER_L.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("transaction_item_${transaction.id}")
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(11.dp))
                            .background(if (isIncome) semantic.incomeBg else semantic.expenseBg),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = getCategoryIcon(transaction.category),
                            contentDescription = null,
                            tint = if (isIncome) semantic.income else semantic.expense,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        // L10: keterangan terpotong bisa dibuka dengan tap.
                        var descriptionExpanded by remember { mutableStateOf(false) }
                        Text(
                            text = transaction.description,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = if (descriptionExpanded) Int.MAX_VALUE else 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .animateContentSize(animationSpec = Motion.fast())
                                .clickable { descriptionExpanded = !descriptionExpanded }
                                // B15: TalkBack mengumumkan aksi & keadaan expand,
                                // bukan hanya membaca teks keterangannya.
                                .semantics {
                                    onClickLabel = stringResource(R.string.ux_expand_description)
                                    stateDescription = stringResource(
                                        if (descriptionExpanded) R.string.ux_desc_expanded
                                        else R.string.ux_desc_collapsed
                                    )
                                }
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // C12: pemisah memakai ux_list_separator ("%1$s · %2$s").
                            // C13: "Anggota"/"Bendahara"/"Ketua" (juga ISTRI/SUAMI)
                            // adalah peran netral — bukan warna relasi wife/husband.
                            Text(
                                text = stringResource(
                                    R.string.ux_list_separator,
                                    transaction.category,
                                    loggedByTag
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "$amountPrefix${currencyFormat.format(transaction.amount)}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = amountColor,
                            // C17: nominal tidak boleh terpotong agresif di layar
                            // sempit / fontScale besar — dua baris + lebar proporsional.
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 190.dp)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = dateFormat.format(Date(transaction.timestamp)),
                            style = MaterialTheme.typography.labelSmall,
                            // B22: timestamp penuh (bukan alpha 0.7) — kontras teks
                            // kecil sudah memadai di kedua mode tema.
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    // ⋮ Menu aksi (audit touch/keyboard): swipe tetap ada untuk power
                    // user, tapi Edit/Hapus juga punya tombol — keyboard (Enter) &
                    // TalkBack bisa menjangkau tanpa gestur swipe.
                    var menuOpen by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(
                                imageVector = Icons.Rounded.MoreHoriz,
                                contentDescription = stringResource(R.string.rekap_more_actions),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_edit)) },
                                leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onEdit()
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.action_delete),
                                        color = MaterialTheme.colorScheme.error
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Rounded.Delete,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                },
                                onClick = {
                                    menuOpen = false
                                    onDelete()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
