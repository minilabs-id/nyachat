package com.startupmini.nyachat.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.startupmini.nyachat.Constants
import com.startupmini.nyachat.data.local.ChatMessage
import com.startupmini.nyachat.data.remote.SyncStatus
import com.startupmini.nyachat.ui.theme.CategoryColorsDark
import com.startupmini.nyachat.ui.theme.CoupleFinanceTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Golden DARK MODE — pasangan dari [AppSnapshotTest] (gap prioritas HIGH #1 di
 * UI-AUDIT.md D): 0/15 golden lama memakai darkTheme=true sehingga temuan
 * B-M15/M16/M23/M24 & D-M2 tidak terdeteksi. Setup identik, bedanya:
 *  - qualifier `uiMode=night`
 *  - `CoupleFinanceTheme(darkTheme = true)` dipaksa (tidak bergantung pada
 *    `isSystemInDarkTheme()` di Robolectric)
 *  - nama file + folder unik (`screens/dark/...`) supaya tidak menimpa golden light.
 *
 * Rekam: `./gradlew :app:recordRoborazziDebug`, verifikasi:
 * `./gradlew :app:verifyRoborazziDebug`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-night")
class AppSnapshotDarkTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun capture(name: String, content: @Composable () -> Unit) {
        composeRule.setContent {
            CoupleFinanceTheme(darkTheme = true) {
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
        composeRule.onRoot().captureRoboImage("src/test/snapshots/screens/dark/$name.png")
    }

    @Test
    fun `dark chat bubble pengeluaran olehku dengan badge finansial`() {
        val msg = ChatMessage(
            sender = "Suami",
            messageText = "Beli nasi padang 50.000",
            timestamp = 1_783_800_000_000L,
            isFinancial = true,
            detectedAmount = 50_000.0,
            detectedCategory = "Makanan & Minuman",
            detectedType = Constants.TransactionTypes.EXPENSE
        )
        capture("dark_chat_bubble_expense") {
            ChatMessageBubble(message = msg, currentActiveSender = "Suami")
        }
    }

    @Test
    fun `dark chat bubble pemasukan pengirim lain dengan header`() {
        val msg = ChatMessage(
            sender = "Istri",
            messageText = "Gaji masuk 5.000.000",
            timestamp = 1_783_800_000_000L,
            isFinancial = true,
            detectedAmount = 5_000_000.0,
            detectedCategory = "Gaji & Pemasukan",
            detectedType = Constants.TransactionTypes.INCOME
        )
        capture("dark_chat_bubble_income_other") {
            ChatMessageBubble(message = msg, currentActiveSender = "Suami", showHeader = true)
        }
    }

    @Test
    fun `dark chat bubble membalas pesan`() {
        val msg = ChatMessage(
            sender = "Istri",
            messageText = "Oke, besok aku transfer ya",
            timestamp = 1_783_800_000_000L,
            replyToSender = "Suami",
            replyToText = "Bayar kontrakan 1.500.000",
            editedAt = 1_783_800_100_000L
        )
        capture("dark_chat_bubble_reply") {
            ChatMessageBubble(message = msg, currentActiveSender = "Suami", showHeader = true)
        }
    }

    @Test
    fun `dark banner saldo tersinkron`() {
        capture("dark_balance_banner_synced") {
            BalanceBannerCard(
                totalIncome = 5_000_000.0,
                totalExpense = 1_250_000.0,
                balance = 3_750_000.0,
                syncStatus = SyncStatus.SYNCED
            )
        }
    }

    @Test
    fun `dark donut chart kategori termasuk slice kecil`() {
        val totals = listOf(
            "Makanan & Minuman" to 300_000.0,
            "Transportasi" to 150_000.0,
            "Tagihan & Utilitas" to 80_000.0,
            "Hiburan & Belanja" to 39_000.0,
            "Lain-lain" to 1_000.0
        )
        capture("dark_donut_chart") {
            DonutChart(
                categoryTotals = totals,
                totalExpense = 570_000.0,
                colors = CategoryColorsDark
            )
        }
    }

    @Test
    fun `dark composer chat pill terisi`() {
        capture("dark_chat_composer_pill_active") {
            ChatInputBar(
                value = "Beli bakso 15.000",
                onValueChange = {},
                isDark = true,
                canSend = true,
                onAttachClick = {},
                onSend = {},
                onAskAi = {},
                inputFocusRequester = remember { FocusRequester() }
            )
        }
    }

    @Test
    fun `dark topbar dengan dua anggota online`() {
        capture("dark_topbar_two_online") {
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
    fun `dark saran cepat quick add`() {
        // Sama seperti golden light: chip masuk dengan animasi dari kanan —
        // settle dulu supaya menangkap posisi akhir.
        composeRule.setContent {
            CoupleFinanceTheme(darkTheme = true) {
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
        composeRule.onRoot().captureRoboImage("src/test/snapshots/screens/dark/dark_quick_suggestions.png")
    }
}
