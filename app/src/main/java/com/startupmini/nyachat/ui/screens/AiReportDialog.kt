package com.startupmini.nyachat.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.startupmini.nyachat.R
import com.startupmini.nyachat.data.remote.GeminiService
import com.startupmini.nyachat.ui.theme.LocalSemanticColors
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiReportDialog(
    reportText: String,
    onDismiss: () -> Unit,
    // Audit response (2026-08-12): mode error — pesan ditampilkan sebagai error
    // (ikon + warna) dengan tombol "Coba Lagi" (Problem → Action), bukan sekadar
    // teks polos di dalam sheet.
    isError: Boolean = false,
    onRetry: (() -> Unit)? = null
) {
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true
    )
    val scope = rememberCoroutineScope()
    val semantic = LocalSemanticColors.current

    // B17: deteksi offline lewat SATU oracle di GeminiService (mencocokkan
    // OFFLINE_REPORT_NOTICE / marker fallback). Teks banner memakai string
    // resource persis ux_ai_offline_notice — bukan salinan literal di UI.
    val isOfflineReport = GeminiService.isOfflineFallbackReply(reportText)
    val offlineNotice = stringResource(R.string.ux_ai_offline_notice)

    // Audit motion (2026-08-12): onDismissRequest juga lewat dismiss() —
    // gesture swipe/scrim/back ikut turun ke bawah, konsisten dengan tombol.
    fun dismiss() {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            if (!sheetState.isVisible) onDismiss()
        }
    }

    ModalBottomSheet(
        onDismissRequest = ::dismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = null, // kita buat custom drag handle di dalam
        // Sheet berada di area konten (di atas NavigationBar) — padding navbar
        // bawaan sheet dinolkan agar tidak muncul celah.
        contentWindowInsets = { WindowInsets(0) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // B-M18: navbar inset manual (contentWindowInsets dinolkan),
                // B-M21: satu batas tinggi sheet 640dp dengan sheet lain.
                .navigationBarsPadding()
                .heightIn(max = 640.dp)
                .padding(horizontal = 20.dp)
        ) {
            // Custom drag handle + spacer
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

            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Audit warna: lingkaran icon AI pakai semantic.ai (mode-aware) —
                    // sebelumnya AiBlueLight (0xFFE3ECFF) terlalu terang di dark mode.
                    // Tombol CTA pakai aiAccent + aiOnContainer (B-M24).
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(semantic.ai.copy(alpha = 0.1f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AutoAwesome,
                            contentDescription = null,
                            tint = semantic.ai,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    // B-M1: judul/subjudul diberi weight(1f) supaya teks panjang
                    // tidak menimpa tombol tutup di lebar 320dp.
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.report_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = stringResource(R.string.report_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                IconButton(onClick = ::dismiss) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.report_close_desc),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            // B-M23: divider tanpa alpha — outlineVariant.copy(0.5f) terlalu
            // samar di tema gelap.
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(16.dp))

            // Scrollable content — ruang penuh
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                if (isOfflineReport) {
                    // B17: banner WARNING (warna error), bukan paragraf biasa —
                    // pembaca langsung tahu laporan ini dihitung dari data lokal.
                    Surface(
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp)
                            .testTag("ai_offline_notice")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = offlineNotice,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
                if (isError) {
                    // Mode error: ikon + pesan jelas, bukan laporan normal.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp, bottom = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(44.dp)
                        )
                    }
                }
                Text(
                    // H1: render markdown ringan (**bold**, bullet, heading emoji)
                    // alih-alih teks polos — tanpa dependency baru.
                    text = buildAiReportAnnotatedString(reportText),
                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 24.sp),
                    color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(24.dp))
            }

            if (isError && onRetry != null) {
                // Problem → Action: tombol Coba Lagi memicu generate ulang.
                Button(
                    onClick = onRetry,
                    // B-M24: AiBlue konstan cuma ~3.4:1 di dark mode — pakai
                    // aiAccent mode-aware + content aiOnContainer (AA di dua mode).
                    colors = ButtonDefaults.buttonColors(
                        containerColor = semantic.aiAccent,
                        contentColor = semantic.aiOnContainer
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        // B-M6: jarak 12dp supaya tombol Coba Lagi tidak
                        // menempel pada tombol Tutup di bawahnya.
                        .padding(bottom = 12.dp)
                        .testTag("retry_ai_report_button"),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(
                        stringResource(R.string.membership_retry),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // Tombol tutup di bawah — outlined saat mode error supaya aksi utama
            // (Coba Lagi) terbaca jelas, bukan dua tombol biru identik bertumpuk.
            if (isError) {
                OutlinedButton(
                    onClick = ::dismiss,
                    // B21: outlined mengikuti semantic.ai (mode-aware), bukan AiBlue
                    // konstan yang terlalu menyolok di tema gelap.
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = semantic.ai),
                    border = BorderStroke(1.dp, semantic.ai),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                        .testTag("close_ai_report_button"),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(stringResource(R.string.report_close), fontWeight = FontWeight.SemiBold)
                }
            } else {
                Button(
                    onClick = ::dismiss,
                    // B-M24: samakan dengan tombol Coba Lagi — aiAccent +
                    // aiOnContainer (kontras AA di light & dark).
                    colors = ButtonDefaults.buttonColors(
                        containerColor = semantic.aiAccent,
                        contentColor = semantic.aiOnContainer
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp)
                        .testTag("close_ai_report_button"),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(stringResource(R.string.report_close), fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// H1: renderer markdown ringan (internal → bisa di-unit-test tanpa Compose UI).
// Mendukung **bold**, bullet ("•", "-", "1."), heading/emoji, dan line break.
// ---------------------------------------------------------------------------

private val NUMBERED_BULLET = Regex("^\\d+\\.\\s+")

// C10: heading hanya untuk "# " eksplisit atau emoji heading yang dikenal.
// Baris yang mulai dengan '%', '(', '+', atau '#' tanpa spasi adalah ISI
// laporan (persentase, catatan, poin) — jangan ditebalkan seluruh barisnya.
private val HEADING_EMOJIS = listOf("📌", "💡", "📅", "⚠️", "🚨", "💰", "✅", "📊")

private fun isAiReportHeading(line: String): Boolean =
    line.startsWith("# ") || HEADING_EMOJIS.any(line::startsWith)

/** Pisahkan penanda bullet dari isi baris; null bila baris bukan bullet. */
private fun splitBulletMarker(line: String): Pair<String, String>? {
    if (line.startsWith("• ")) return "• " to line.substring(2)
    if (line.startsWith("- ")) return "- " to line.substring(2)
    val numbered = NUMBERED_BULLET.find(line)
    if (numbered != null) return numbered.value to line.substring(numbered.range.last + 1)
    return null
}

/** Tulis inline markdown: pasangan `**...**` jadi bold; [forceBold] untuk heading. */
private fun AnnotatedString.Builder.appendInlineMarkdown(source: String, forceBold: Boolean) {
    val bold = SpanStyle(fontWeight = FontWeight.Bold)
    var cursor = 0
    while (cursor < source.length) {
        if (source.startsWith("**", cursor)) {
            val end = source.indexOf("**", cursor + 2)
            if (end != -1) {
                withStyle(bold) { append(source.substring(cursor + 2, end)) }
                cursor = end + 2
                continue
            }
        }
        val next = source.indexOf("**", cursor + 1)
        val stop = if (next == -1) source.length else next
        val chunk = source.substring(cursor, stop)
        if (forceBold) withStyle(bold) { append(chunk) } else append(chunk)
        cursor = stop
    }
}

/**
 * H1 — ubah teks laporan AI menjadi [AnnotatedString] dengan:
 * - `**teks**` → `SpanStyle(fontWeight = FontWeight.Bold)`,
 * - baris bullet (`•`/`-`/`1.`) → penanda ditebalkan,
 * - baris heading/emoji → seluruh baris ditebalkan,
 * - satu `\n` per baris (line break rapi, tidak ada markdown mentah).
 */
internal fun buildAiReportAnnotatedString(text: String): AnnotatedString = buildAnnotatedString {
    text.lines().forEachIndexed { index, line ->
        if (index > 0) append('\n')
        val trimmed = line.trimStart()
        if (trimmed.isEmpty()) return@forEachIndexed
        val marker = splitBulletMarker(trimmed)
        if (marker != null) {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(marker.first) }
            appendInlineMarkdown(marker.second, forceBold = false)
        } else {
            appendInlineMarkdown(trimmed, forceBold = isAiReportHeading(trimmed))
        }
    }
}
