package com.startupmini.nyachat.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.takahirom.roborazzi.captureRoboImage
import com.startupmini.nyachat.Constants
import com.startupmini.nyachat.data.local.ChatMessage
import com.startupmini.nyachat.data.local.FinancialTransaction
import com.startupmini.nyachat.data.remote.ReceiptStats
import com.startupmini.nyachat.data.remote.SyncStatus
import com.startupmini.nyachat.ui.theme.CategoryColorsLight
import com.startupmini.nyachat.ui.theme.CoupleFinanceTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Snapshot UI dengan Roborazzi (P3-3) — mendeteksi regresi visual komponen
 * inti lewat render Robolectric (tanpa emulator). Baseline disimpan di
 * `app/src/test/snapshots/`; rekam ulang via
 * `./gradlew :app:recordRoborazziDebug`, verifikasi via
 * `./gradlew :app:verifyRoborazziDebug`.
 *
 * Catatan D-L4: font brand Plus Jakarta Sans dimuat via Downloadable Fonts
 * (GMS) — di Robolectric/CI tidak tersedia sehingga golden merepresentasikan
 * font sistem. Jangan bandingkan piksel lintas OS tanpa re-record di CI.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class AppSnapshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun capture(name: String, content: @Composable () -> Unit) {
        captureWithFontScale(name, fontScale = 1f, content = content)
    }

    /**
     * Helper untuk variasi fontScale (B-M1/M2/M7/M8/M9/M12/M19).
     * Menimpa [Density.fontScale] lewat CompositionLocalProvider — tidak
     * mengubah qualifier `@Config` supaya ukuran perangkat tetap w411dp.
     */
    private fun captureWithFontScale(
        name: String,
        fontScale: Float,
        content: @Composable () -> Unit
    ) {
        composeRule.setContent {
            CoupleFinanceTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(
                        density = density.density,
                        fontScale = fontScale
                    )
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.background)
                            .padding(8.dp)
                    ) {
                        content()
                    }
                }
            }
        }
        // Path relatif terhadap direktori modul (app/) — baseline berada di
        // app/src/test/snapshots/ supaya ikut ter-commit sebagai golden file.
        composeRule.onRoot().captureRoboImage("src/test/snapshots/screens/$name.png")
    }

    @Test
    fun `chat bubble pengeluaran olehku dengan badge finansial`() {
        val msg = ChatMessage(
            sender = "Suami",
            messageText = "Beli nasi padang 50.000",
            timestamp = 1_783_800_000_000L,
            isFinancial = true,
            detectedAmount = 50_000.0,
            detectedCategory = "Makanan & Minuman",
            detectedType = Constants.TransactionTypes.EXPENSE
        )
        capture("chat_bubble_expense") {
            ChatMessageBubble(message = msg, currentActiveSender = "Suami")
        }
    }

    @Test
    fun `chat bubble pemasukan pengirim lain dengan header`() {
        val msg = ChatMessage(
            sender = "Istri",
            messageText = "Gaji masuk 5.000.000",
            timestamp = 1_783_800_000_000L,
            isFinancial = true,
            detectedAmount = 5_000_000.0,
            detectedCategory = "Gaji & Pemasukan",
            detectedType = Constants.TransactionTypes.INCOME
        )
        capture("chat_bubble_income_other") {
            ChatMessageBubble(message = msg, currentActiveSender = "Suami", showHeader = true)
        }
    }

    @Test
    fun `chat bubble membalas pesan`() {
        val msg = ChatMessage(
            sender = "Istri",
            messageText = "Oke, besok aku transfer ya",
            timestamp = 1_783_800_000_000L,
            replyToSender = "Suami",
            replyToText = "Bayar kontrakan 1.500.000",
            editedAt = 1_783_800_100_000L
        )
        capture("chat_bubble_reply") {
            ChatMessageBubble(message = msg, currentActiveSender = "Suami", showHeader = true)
        }
    }

    @Test
    fun `item transaksi rekap pengeluaran dan pemasukan`() {
        val txs = listOf(
            FinancialTransaction(
                type = Constants.TransactionTypes.EXPENSE,
                category = Constants.Categories.FOOD,
                amount = 50_000.0,
                description = "Beli nasi padang",
                loggedBy = "Suami",
                timestamp = 1_783_800_000_000L
            ),
            FinancialTransaction(
                type = Constants.TransactionTypes.INCOME,
                category = Constants.Categories.SALARY,
                amount = 5_000_000.0,
                description = "Gaji bulanan",
                loggedBy = "Istri",
                timestamp = 1_783_800_000_000L
            )
        )
        capture("rekap_transaction_items") {
            Column {
                txs.forEach { tx ->
                    TransactionItemCard(transaction = tx, onDelete = {}, onEdit = {})
                }
            }
        }
    }

    @Test
    fun `banner saldo tersinkron`() {
        capture("balance_banner_synced") {
            BalanceBannerCard(
                totalIncome = 5_000_000.0,
                totalExpense = 1_250_000.0,
                balance = 3_750_000.0,
                syncStatus = SyncStatus.SYNCED
            )
        }
    }

    @Test
    fun `banner saldo defisit dan offline`() {
        capture("balance_banner_offline") {
            BalanceBannerCard(
                totalIncome = 0.0,
                totalExpense = 250_000.0,
                balance = -250_000.0,
                syncStatus = SyncStatus.OFFLINE
            )
        }
    }

    @Test
    fun `donut chart kategori termasuk slice kecil`() {
        // P2-18: slice kecil (<0.5°) tidak boleh merusak posisi slice lain.
        val totals = listOf(
            "Makanan & Minuman" to 300_000.0,
            "Transportasi" to 150_000.0,
            "Tagihan & Utilitas" to 80_000.0,
            "Hiburan & Belanja" to 39_000.0,
            "Lain-lain" to 1_000.0 // ~0.17° — nyaris tak terlihat
        )
        capture("donut_chart") {
            DonutChart(
                categoryTotals = totals,
                totalExpense = 570_000.0,
                colors = CategoryColorsLight
            )
        }
    }

    @Test
    fun `saran cepat quick add`() {
        // Chip kini BERLATAR (fill surfaceVariant) & masuk dengan animasi kereta
        // dari kanan (2026-08-12) — settle animasi dulu supaya snapshot
        // menangkap posisi akhir (chip sudah masuk semua).
        composeRule.setContent {
            CoupleFinanceTheme {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(8.dp)
                ) {
                    QuickSuggestionRow(
                        suggestions = listOf(
                            "Makan siang 25.000",
                            "Bensin 20.000",
                            "Beli token listrik 50.000"
                        ),
                        onSuggestionClicked = {}
                    )
                }
            }
        }
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.onRoot().captureRoboImage("src/test/snapshots/screens/quick_suggestions.png")
    }

    @Test
    fun `composer chat pill kosong`() {
        // Redesign composer (WhatsApp-style): floating pill + ikon (+) di dalam,
        // tombol Send circular terpisah — tanpa panel besar pembungkus.
        capture("chat_composer_pill") {
            ChatInputBar(
                value = "",
                onValueChange = {},
                isDark = false,
                canSend = false,
                onAttachClick = {},
                onSend = {},
                onAskAi = {},
                inputFocusRequester = remember { FocusRequester() }
            )
        }
    }

    @Test
    fun `composer chat pill terisi`() {
        // State aktif: teks terisi → tombol Send berwarna primary.
        capture("chat_composer_pill_active") {
            ChatInputBar(
                value = "Beli bakso 15.000",
                onValueChange = {},
                isDark = false,
                canSend = true,
                onAttachClick = {},
                onSend = {},
                onAskAi = {},
                inputFocusRequester = remember { FocusRequester() }
            )
        }
    }

    @Test
    fun `composer chat pill dengan reply quote`() {
        // Quote balasan menempel DI DALAM pill (gaya Telegram): garis aksen kiri,
        // nama pengirim tebal, snippet 1 baris, tombol ✕ — tombol Send tetap
        // sejajar dengan baris input.
        capture("chat_composer_reply_quote") {
            ChatInputBar(
                value = "Oke nanti sore",
                onValueChange = {},
                isDark = false,
                canSend = true,
                onAttachClick = {},
                onSend = {},
                onAskAi = {},
                inputFocusRequester = remember { FocusRequester() },
                replyTarget = ChatMessage(
                    sender = "ISTRI",
                    messageText = "Beli bakso 15.000 di pasar sore ini ya"
                ),
                onReplyDismiss = {}
            )
        }
    }

    @Test
    fun `topbar dengan enam anggota online`() {
        // r1.6.0 (presence): avatar topbar = anggota yang SEDANG ONLINE, bukan
        // pengirim pesan. Dengan 6 anggota online (cap plan pro) seluruhnya
        // bertumpuk; subtitle menampilkan "6 dari 6 anggota online".
        capture("topbar_six_online") {
            MainTopBar(
                onlineMemberNames = listOf("Ari", "Budi", "Cici", "Dedi", "Eka", "Fani"),
                totalMembers = 6,
                userName = "Ari",
                memberAvatarPaths = emptyMap(),
                familyName = "Keuangan Bersama",
                onManageMembers = {},
                onSettings = {}
            )
        }
    }

    @Test
    fun `topbar dengan dua anggota online`() {
        // r1.6.0 (presence): dua anggota online → dua avatar bertumpuk &
        // subtitle "2 dari 4 anggota online".
        capture("topbar_two_online") {
            MainTopBar(
                onlineMemberNames = listOf("Ari", "Budi"),
                totalMembers = 4,
                userName = "Ari",
                memberAvatarPaths = emptyMap(),
                familyName = "Keuangan Bersama",
                onManageMembers = {},
                onSettings = {}
            )
        }
    }

    @Test
    fun `topbar dengan satu anggota online`() {
        // r1.6.0 (presence): hanya diri sendiri online → avatar tunggal tanpa
        // counter (menampilkan nama member di subtitle).
        capture("topbar_one_online") {
            MainTopBar(
                onlineMemberNames = listOf("Ari"),
                totalMembers = 4,
                userName = "Ari",
                memberAvatarPaths = emptyMap(),
                familyName = "Keuangan Bersama",
                onManageMembers = {},
                onSettings = {}
            )
        }
    }

    // ── Helper test composable (production tetap tidak diubah visibilitasnya) ──

    /**
     * PinConnectScreen step-2 (PIN tergenerate) tidak bisa dirender langsung
     * tanpa state Google/auto-connect, jadi kami render ulang Text PIN-nya
     * dengan style persis (B-H1: displayMedium + ExtraBold + letterSpacing 8.sp).
     */
    @Composable
    private fun GeneratedPinText(pin: String) {
        Text(
            text = pin,
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.primary,
            letterSpacing = 8.sp
        )
    }

    /**
     * `MessageStatusIndicators` adalah `private` di ChatBubbles.kt — jalur resmi
     * satu-satunya adalah lewat [ChatMessageBubble] publik (delivery + receiptStats),
     * jadi wrapper di sini memanggil bubble tersebut, bukan mengubah visibility
     * produksi. Menangkap pending / read / rainbow (deretan titik + pelangi).
     */
    @Composable
    private fun MessageStatusIndicatorsWrapper(
        delivery: DeliveryStatus?,
        receiptStats: ReceiptStats?
    ) {
        ChatMessageBubble(
            message = ChatMessage(
                sender = "Suami",
                messageText = "Oke, sudah kubayar",
                timestamp = 1_783_800_000_000L
            ),
            currentActiveSender = "Suami",
            delivery = delivery,
            receiptStats = receiptStats
        )
    }

    /**
     * Replika container sheet `ManageMembersScreen` (Box fillMaxWidth +
     * widthIn(max=600dp)). Layar asli bergantung pada FirebaseAuth di
     * `MembershipManager.currentUid()` sehingga tidak aman dirender di unit test
     * tanpa inisialisasi Firebase. TODO: ganti ke `ManageMembersScreen(...)` bila
     * dependency sudah bisa di-inject / Firebase-aware test harness tersedia.
     * Golden ini mengunci geometri centering B-H2 pada lebar tablet 840dp.
     */
    @Composable
    private fun ManageMembersTabletReplica() {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(modifier = Modifier.fillMaxWidth().widthIn(max = 600.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Kelola Anggota",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                repeat(4) { index ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                text = "Anggota ${index + 1}",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = "anggota${index + 1}@example.com",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
            }
        }
    }

    // ── Golden BARU (15 test lama di atas sengaja tidak diubah) ──

    @Test
    fun `pin step 2 dengan PIN delapan digit`() {
        // B-H1: PIN 8 digit + letterSpacing 8.sp di w411dp (base coverage).
        capture("pin_step2_generated") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f))
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                GeneratedPinText(pin = "48291370")
            }
        }
    }

    @Test
    @Config(qualifiers = "w840dp-h1180dp")
    fun `sheet kelola anggota pada lebar tablet`() {
        // B-H2: container widthIn(600dp) harus ter-center, bukan menempel kiri.
        capture("manage_members_tablet") {
            ManageMembersTabletReplica()
        }
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp")
    fun `bubble chat pada 320dp dengan fontScale 1 5`() {
        val msg = ChatMessage(
            sender = "Suami",
            messageText = "Beli nasi padang 50.000",
            timestamp = 1_783_800_000_000L,
            isFinancial = true,
            detectedAmount = 50_000.0,
            detectedCategory = "Makanan & Minuman",
            detectedType = Constants.TransactionTypes.EXPENSE
        )
        captureWithFontScale("chat_bubble_w320_font15", fontScale = 1.5f) {
            ChatMessageBubble(message = msg, currentActiveSender = "Suami")
        }
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp")
    fun `topbar pada 320dp dengan fontScale 1 5`() {
        // B-M19: subtitle tanpa weight/ellipsis menimpa aksi di layar sempit.
        captureWithFontScale("topbar_two_online_w320_font15", fontScale = 1.5f) {
            MainTopBar(
                onlineMemberNames = listOf("Ari", "Budi"),
                totalMembers = 6,
                userName = "Ari",
                memberAvatarPaths = emptyMap(),
                familyName = "Keuangan Bersama",
                onManageMembers = {},
                onSettings = {}
            )
        }
    }

    @Test
    fun `indikator status pesan pending`() {
        capture("message_status_pending") {
            MessageStatusIndicatorsWrapper(
                delivery = DeliveryStatus.PENDING,
                receiptStats = null
            )
        }
    }

    @Test
    fun `indikator status pesan dibaca`() {
        capture("message_status_read") {
            MessageStatusIndicatorsWrapper(
                delivery = DeliveryStatus.SYNCED,
                receiptStats = ReceiptStats(delivered = 2, read = 1, totalOthers = 2)
            )
        }
    }

    @Test
    fun `indikator status pesan semua dibaca pelangi`() {
        capture("message_status_rainbow") {
            MessageStatusIndicatorsWrapper(
                delivery = DeliveryStatus.DELIVERED,
                receiptStats = ReceiptStats(delivered = 2, read = 2, totalOthers = 2, allRead = true)
            )
        }
    }

    @Test
    fun `kartu insight AI mode offline`() {
        // B17: banner transparansi saat tidak ada jalur AI aktif.
        capture("ai_insights_offline") {
            InsightsAiCard(
                insights = listOf("Pengeluaran bulan ini naik 12% dari bulan lalu."),
                isAuditLoading = false,
                onGenerateAudit = {},
                isMonthlyLoading = false,
                onGenerateMonthly = {},
                isOffline = true
            )
        }
    }

    // TODO golden (butuh harness Firestore / Firebase, bukan sekadar Composable):
    //  - E2EE banner states (checking / preparing / active / error) — statusnya
    //    di-read dari E2eeSyncManager.status di dalam ChatScreen, belum injectable.
    //  - AiReportDialog mode offline & error (ModalBottomSheet + GeminiService) —
    //    render sheet di Robolectric belum stabil; pakai ai_insights_offline di atas
    //    untuk state offline, dan tambahkan golden error setelah harness siap.
}
