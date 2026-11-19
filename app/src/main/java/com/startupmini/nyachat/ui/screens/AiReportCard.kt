package com.startupmini.nyachat.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.startupmini.nyachat.Constants
import com.startupmini.nyachat.R
import com.startupmini.nyachat.ui.theme.LocalSemanticColors

// Kartu insight AI + aksi laporan (bulanan/audit) — diekstrak dari RekapScreen.kt
// (TASK-1.2.3) tanpa perubahan behavior.

/**
 * Kartu gabungan (audit UI/UX P1.3): insight lokal mingguan + dua aksi AI
 * (laporan bulanan & audit finansial) dalam satu kartu di bawah riwayat.
 * Tombol full-width disusun vertikal — aman di layar 360dp & font scale besar.
 * Saat loading label teks TETAP tampil + spinner kecil di depan (tidak diganti).
 */
@Composable
fun InsightsAiCard(
    insights: List<String>,
    isAuditLoading: Boolean,
    onGenerateAudit: () -> Unit,
    isMonthlyLoading: Boolean,
    onGenerateMonthly: () -> Unit,
    // B17: banner transparansi saat tidak ada jalur AI aktif (opsional supaya
    // pemanggil lama/snapshot test tetap kompatibel).
    isOffline: Boolean = false,
    modifier: Modifier = Modifier
) {
    val semantic = LocalSemanticColors.current
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(Constants.Ui.CORNER_L.dp),
        // Tanpa shadow — konsisten dengan prinsip chat (audit 2026-08-12).
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        // Audit warna: pakai semantic.ai (mode-aware) — di dark mode
                        // biru lembut AiBlueDark selaras dengan bubble AI di chat
                        // (sebelumnya AiBlue 0xFF0066FF terlalu mencolok di layar gelap).
                        .background(semantic.ai.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        tint = semantic.ai,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = stringResource(R.string.rekap_insights_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.rekap_insights_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // B17: chip/banner WARNING — bukan paragraf biasa. Memakai teks
            // persis R.string.ux_ai_offline_notice (sama dengan dialog laporan).
            if (isOffline) {
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
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
                            text = stringResource(R.string.ux_ai_offline_notice),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            if (insights.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                insights.forEach { insight ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = "•",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            // Audit warna: semantic.ai (mode-aware) — konsisten dengan icon.
                            color = semantic.ai,
                            modifier = Modifier.width(14.dp)
                        )
                        Text(
                            text = insight,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onGenerateMonthly,
                    enabled = !isMonthlyLoading,
                    shape = RoundedCornerShape(Constants.Ui.CORNER_M.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ai_monthly_button")
                ) {
                    if (isMonthlyLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        stringResource(R.string.rekap_monthly_action),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        // B-L: maxLines tanpa overflow memotong teks mendadak.
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Button(
                    onClick = onGenerateAudit,
                    // B-M24: AiBlue FIXED cuma ~3.4:1 di dark mode — pakai
                    // aiAccent mode-aware + content aiOnContainer (AA di dua mode).
                    colors = ButtonDefaults.buttonColors(
                        containerColor = semantic.aiAccent,
                        contentColor = semantic.aiOnContainer
                    ),
                    enabled = !isAuditLoading,
                    shape = RoundedCornerShape(Constants.Ui.CORNER_M.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ai_audit_button")
                ) {
                    if (isAuditLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            // Token semantik LocalContentColor — spinner selalu
                            // mengikuti warna konten tombol (putih di kedua mode).
                            color = LocalContentColor.current,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        stringResource(R.string.rekap_ai_action),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        // B-L: maxLines tanpa overflow memotong teks mendadak.
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
