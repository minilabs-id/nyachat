package com.startupmini.nyachat.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.startupmini.nyachat.Constants
import com.startupmini.nyachat.R
import com.startupmini.nyachat.data.local.FinancialTransaction
import com.startupmini.nyachat.ui.AppSnackbar
import com.startupmini.nyachat.ui.theme.LocalSemanticColors
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.launch
import kotlin.math.roundToLong

/** Simpan hanya digit mentah di state ("Rp 50.000" → "50000"). */
internal fun amountDigitsOnly(input: String): String = input.filter { it.isDigit() }

/** Tampilan grouping ribuan id-ID: "50000" → "50.000" (tanpa NumberFormat supaya
 *  stabil & murni — mudah di-unit-test). */
internal fun formatAmountDisplay(digits: String): String {
    if (digits.isEmpty()) return ""
    val sb = StringBuilder()
    val reversed = digits.reversed()
    reversed.forEachIndexed { index, c ->
        sb.append(c)
        if ((index + 1) % 3 == 0 && index + 1 < reversed.length) sb.append('.')
    }
    return sb.reverse().toString()
}

/**
 * Audit UI/UX M3: Material3 DatePicker memakai UTC epoch-millis (tengah malam
 * UTC), sedangkan transaksi menyimpan millis lokal. Konversi lokal → UTC agar
 * picker tidak menyorot tanggal yang salah di timezone UTC+ (mis. WIB).
 */
internal fun localDateToUtcMidnight(millis: Long): Long {
    val cal = Calendar.getInstance().apply {
        timeInMillis = millis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val offset = cal.timeZone.getOffset(cal.timeInMillis)
    return cal.timeInMillis - offset
}

/** Balik dari UTC-midnight DatePicker → local-midnight epoch millis. */
internal fun utcMidnightToLocalDate(millis: Long): Long {
    val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = millis }
    return Calendar.getInstance().apply {
        clear()
        set(Calendar.YEAR, utc.get(Calendar.YEAR))
        set(Calendar.MONTH, utc.get(Calendar.MONTH))
        set(Calendar.DAY_OF_MONTH, utc.get(Calendar.DAY_OF_MONTH))
    }.timeInMillis
}

/** Parse teks nominal (mentah maupun berformat) menjadi Double; null jika tidak valid. */
internal fun parseAmount(text: String): Double? {
    val digits = text.replace(".", "")
    return if (digits.isEmpty()) null else digits.toDoubleOrNull()
}

/**
 * M5: grouping ribuan dipindah ke [VisualTransformation] supaya nilai mentah di
 * `TextField` tidak berubah saat pengguna mengetik — kursor tidak lagi loncat.
 * [OffsetMapping] menjaga posisi kursor tetap benar di teks yang ditampilkan.
 */
internal object AmountGroupingTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val digits = text.text
        val grouped = formatAmountDisplay(digits)
        val originalToTransformed = IntArray(digits.length + 1)
        val transformedToOriginal = IntArray(grouped.length + 1)
        var o = 0
        var t = 0
        while (o < digits.length) {
            originalToTransformed[o] = t
            transformedToOriginal[t] = o
            o++
            t++
            val remaining = digits.length - o
            if (remaining > 0 && remaining % 3 == 0) {
                // Titik disisipkan di posisi t; kursor di sini mengacu ke digit
                // berikutnya (original offset o).
                transformedToOriginal[t] = o
                t++
            }
        }
        originalToTransformed[digits.length] = grouped.length
        transformedToOriginal[grouped.length] = digits.length
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int =
                originalToTransformed.getOrElse(offset) { grouped.length }

            override fun transformedToOriginal(offset: Int): Int =
                transformedToOriginal.getOrElse(offset) { digits.length }
        }
        return TransformedText(AnnotatedString(grouped), mapping)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTransactionDialog(
    transaction: FinancialTransaction? = null,
    initialLoggedBy: String? = null,
    onDismiss: () -> Unit,
    onConfirm: (FinancialTransaction) -> Unit,
    /**
     * B3 (opsional, default null = perilaku lama tetap kompatibel): pemanggil
     * yang bisa menunggu hasil insert/update meneruskan implementasi yang
     * memanggil [onResult] dengan `true` hanya setelah simpan berhasil. Sheet
     * baru ditutup pada `true`; pada `false` sheet TETAP terbuka, guard dilepas,
     * dan AppSnackbar menawarkan ux_save_failed + aksi Coba lagi.
     */
    onConfirmAsync: ((FinancialTransaction, (Boolean) -> Unit) -> Unit)? = null
) {
    val isEdit = transaction != null
    // M7: seluruh state form bertahan saat rotasi/config change.
    var type by rememberSaveable { mutableStateOf(transaction?.type ?: Constants.TransactionTypes.EXPENSE) }
    // State nominal = digit mentah; grouping ribuan dihitung hanya untuk tampilan
    // (audit P1.4). Prefill edit dibulatkan ke rupiah penuh.
    var amountText by rememberSaveable {
        mutableStateOf(transaction?.amount?.roundToLong()?.toString() ?: "")
    }
    var description by rememberSaveable { mutableStateOf(transaction?.description ?: "") }
    // H2: tanggal transaksi (edit = timestamp yang ada, add = hari ini).
    var selectedDateMillis by rememberSaveable {
        mutableLongStateOf(transaction?.timestamp ?: System.currentTimeMillis())
    }
    var showDatePicker by remember { mutableStateOf(false) }
    val dateFormatter = remember { SimpleDateFormat("dd MMM yyyy", Locale.forLanguageTag("id-ID")) }
    val loggedBy = remember {
        // Default netral (L4): jangan asumsi peran "Bendahara" — pakai identitas
        // pengguna aktif (initialLoggedBy) atau label netral bila tidak diketahui.
        transaction?.loggedBy ?: initialLoggedBy ?: Constants.Defaults.LABEL
    }

    // r1.2.2: daftar kategori DINAMIS per tipe — pengeluaran hanya menampilkan
    // kategori pengeluaran, pemasukan hanya kategori pemasukan (sebelumnya semua
    // tercampur: dropdown pengeluaran ikut menampilkan "Gaji & Pemasukan").
    val categories = if (type == Constants.TransactionTypes.INCOME) {
        Constants.Categories.INCOME_ALL
    } else {
        Constants.Categories.EXPENSE_ALL
    }

    var selectedCategory by rememberSaveable(transaction?.id) {
        mutableStateOf(
            transaction?.category ?: if (type == Constants.TransactionTypes.INCOME) Constants.Categories.SALARY else Constants.Categories.GROCERIES
        )
    }
    var expandedCategoryMenu by remember { mutableStateOf(false) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val semantic = LocalSemanticColors.current
    val keyboardController = LocalSoftwareKeyboardController.current

    // Audit bug (2026-08-12): guard anti-double-submit. onConfirm (insert DB,
    // fire-and-forget async) lalu onDismiss dipanggil sinkron tanpa guard — dua
    // tap cepat pada Simpan bisa mencatat transaksi DUPLIKAT. Flag di-set sebelum
    // aksi pertama dan menonaktifkan tombol sampai dialog tertutup.
    var isSaving by remember { mutableStateOf(false) }
    // B3: teks feedback simpan gagal + aksi retry (kanal global AppSnackbar).
    val saveFailedLabel = stringResource(R.string.ux_save_failed)
    val retryLabel = stringResource(R.string.ux_retry)

    // F3 (audit focus order): fokus langsung ke kolom Jumlah saat sheet terbuka —
    // sebelumnya Tab pertama mendarat di chip tipe (perlu 2× Tab untuk ke field).
    // B10: tanpa delay(120) sihir — LaunchedEffect sudah berjalan setelah
    // komposisi field ter-attach, jadi requestFocus() cukup dipanggil langsung.
    val amountFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        amountFocusRequester.requestFocus()
    }

    // BUG-08 regression guard: jalur tutup HARUS melalui fungsi ini. onConfirm
    // hanya boleh memicu simpan — PEMANGGIL tidak boleh menyet
    // showAddDialog=false di dalam onConfirm: itu membatalkan Composable tempat
    // coroutine hide() ini berjalan dan melewati onDismiss → chatResetTrigger
    // tidak pernah naik. Jalur yang benar: dismiss() → hide() → onDismiss(),
    // dan caller menutup sheet + menaikkan trigger di onDismiss (lihat juga
    // MainAppDialogs — tanggung jawab agent pemilik file tersebut).
    fun dismiss() {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            if (!sheetState.isVisible) onDismiss()
        }
    }

    // Audit motion (2026-08-12): onDismissRequest juga lewat dismiss() —
    // sebelumnya gesture swipe/scrim/back langsung onDismiss → sheet hilang
    // instan tanpa animasi turun, tidak konsisten dengan tombol internal.
    ModalBottomSheet(
        onDismissRequest = ::dismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = Constants.Ui.CORNER_XL.dp, topEnd = Constants.Ui.CORNER_XL.dp),
        dragHandle = null,
        // Sheet hidup di area konten (berhenti di atas NavigationBar) — padding
        // navbar bawaan sheet malah membuat celah, jadi dinolkan. Insets IME
        // ditangani windowInsetsPadding di konten bawah.
        contentWindowInsets = { WindowInsets(0) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.ime)
                // B-M18: navbar inset manual (contentWindowInsets dinolkan),
                // B-M21: satu batas tinggi sheet 640dp dengan sheet lain.
                .navigationBarsPadding()
                .heightIn(max = 640.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            // Drag handle
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

            Text(
                text = stringResource(if (isEdit) R.string.add_dialog_edit_title else R.string.add_dialog_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Type Toggle (Pengeluaran vs Pemasukan) — chip berwarna
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = type == Constants.TransactionTypes.EXPENSE,
                    onClick = {
                        type = Constants.TransactionTypes.EXPENSE
                        // Reset ke kategori pengeluaran bila yang terpilih
                        // sekarang kategori pemasukan (r1.2.2).
                        if (selectedCategory !in Constants.Categories.EXPENSE_ALL) {
                            selectedCategory = Constants.Categories.GROCERIES
                        }
                    },
                    label = { Text(stringResource(R.string.add_type_expense), fontWeight = FontWeight.Medium) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = semantic.expenseBg,
                        selectedLabelColor = semantic.expense,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp).widthIn(min = 48.dp)
                        .testTag("add_dialog_expense_chip")
                )

                FilterChip(
                    selected = type == Constants.TransactionTypes.INCOME,
                    onClick = {
                        type = Constants.TransactionTypes.INCOME
                        // Reset ke kategori pemasukan bila yang terpilih
                        // sekarang kategori pengeluaran (r1.2.2).
                        if (selectedCategory !in Constants.Categories.INCOME_ALL) {
                            selectedCategory = Constants.Categories.SALARY
                        }
                    },
                    label = { Text(stringResource(R.string.add_type_income), fontWeight = FontWeight.Medium) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = semantic.incomeBg,
                        selectedLabelColor = semantic.income,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp).widthIn(min = 48.dp)
                        .testTag("add_dialog_income_chip")
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Amount Input — grouping ribuan live + prefix "Rp" (audit P1.4).
            // State menyimpan digit mentah; validasi & simpan men-strip separator.
            val amountVal = parseAmount(amountText) ?: 0.0
            val isAmountInvalid = amountText.isNotBlank() && amountVal <= 0
            OutlinedTextField(
                // M5: nilai mentah tidak diubah; grouping lewat VisualTransformation
                // → kursor tidak loncat saat mengetik di tengah angka.
                value = amountText,
                onValueChange = { amountText = amountDigitsOnly(it) },
                visualTransformation = AmountGroupingTransformation,
                label = { Text(stringResource(R.string.add_amount_label_plain)) },
                // C12: prefix pakai string resource, bukan literal "Rp".
                leadingIcon = {
                    Text(
                        stringResource(R.string.ux_currency_prefix),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                placeholder = { Text(stringResource(R.string.add_amount_placeholder)) },
                // L2: imeAction Done — keyboard bisa ditutup lewat tombol ✓.
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = { keyboardController?.hide() }),
                isError = isAmountInvalid,
                // M4: petunjuk tampil permanen supaya tombol Simpan yang disabled
                // punya alasan yang jelas (error menggantikan petunjuk saat invalid).
                supportingText = {
                    Text(
                        text = stringResource(
                            if (isAmountInvalid) R.string.add_amount_error else R.string.ux_amount_hint
                        ),
                        color = if (isAmountInvalid) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(amountFocusRequester)
                    .testTag("add_dialog_amount_field"),
                singleLine = true,
                shape = RoundedCornerShape(Constants.Ui.CORNER_M.dp)
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Description Input
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text(stringResource(R.string.add_desc_label)) },
                placeholder = { Text(stringResource(R.string.add_desc_placeholder)) },
                // B16: samakan dengan kolom jumlah — ✓ keyboard langsung menutup
                // keyboard, pengguna tidak perlu menekan back untuk lanjut.
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { keyboardController?.hide() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("add_dialog_desc_field"),
                singleLine = true,
                shape = RoundedCornerShape(Constants.Ui.CORNER_M.dp)
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Category Dropdown
            ExposedDropdownMenuBox(
                expanded = expandedCategoryMenu,
                onExpandedChange = { expandedCategoryMenu = !expandedCategoryMenu },
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = selectedCategory,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.add_category_label)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedCategoryMenu) },
                    shape = RoundedCornerShape(Constants.Ui.CORNER_M.dp),
                    modifier = Modifier
                        .menuAnchor(androidx.compose.material3.MenuAnchorType.PrimaryNotEditable, true)
                        .fillMaxWidth()
                )

                ExposedDropdownMenu(
                    expanded = expandedCategoryMenu,
                    onDismissRequest = { expandedCategoryMenu = false }
                ) {
                    categories.forEach { cat ->
                        DropdownMenuItem(
                            text = { Text(cat) },
                            // Ikon kategori di dropdown (bonus audit P1.4) — sama
                            // dengan ikon di riwayat rekap.
                            leadingIcon = {
                                Icon(
                                    imageVector = getCategoryIcon(cat),
                                    contentDescription = null
                                )
                            },
                            onClick = {
                                selectedCategory = cat
                                expandedCategoryMenu = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // H2: tanggal transaksi — field read-only + DatePicker Material3.
            OutlinedTextField(
                value = dateFormatter.format(Date(selectedDateMillis)),
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.ux_transaction_date)) },
                trailingIcon = {
                    TextButton(
                        onClick = { showDatePicker = true },
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        // B28: target sentuh minimal 48dp.
                        modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp)
                    ) {
                        Text(stringResource(R.string.ux_pick_date))
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("add_dialog_date_field"),
                singleLine = true,
                shape = RoundedCornerShape(Constants.Ui.CORNER_M.dp)
            )

            if (showDatePicker) {
                val datePickerState = rememberDatePickerState(
                    initialSelectedDateMillis = localDateToUtcMidnight(selectedDateMillis)
                )
                DatePickerDialog(
                    onDismissRequest = { showDatePicker = false },
                    confirmButton = {
                        TextButton(onClick = {
                            datePickerState.selectedDateMillis?.let {
                                selectedDateMillis = utcMidnightToLocalDate(it)
                            }
                            showDatePicker = false
                        }) {
                            Text(stringResource(R.string.action_ok))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDatePicker = false }) {
                            Text(stringResource(R.string.add_cancel))
                        }
                    }
                ) {
                    DatePicker(state = datePickerState)
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = ::dismiss,
                    // B6: selama simpan berjalan tombol mati — tidak ada jalur
                    // keluar yang bisa membatalkan insert yang sedang berjalan.
                    enabled = !isSaving,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp).widthIn(min = 48.dp),
                    shape = RoundedCornerShape(Constants.Ui.CORNER_M.dp)
                ) {
                    Text(stringResource(R.string.add_cancel))
                }

                Button(
                    onClick = {
                        // Audit bug (2026-08-12): tap kedua diabaikan selama dialog
                        // masih menutup — mencegah transaksi duplikat (P1).
                        if (isSaving) return@Button
                        isSaving = true
                        val finalAmount = parseAmount(amountText) ?: 0.0
                        val pendingTransaction = FinancialTransaction(
                            id = transaction?.id ?: 0,
                            type = type,
                            category = selectedCategory,
                            amount = finalAmount,
                            description = description,
                            loggedBy = loggedBy,
                            // H2: pakai tanggal yang dipilih (add & edit).
                            timestamp = selectedDateMillis,
                            chatMessageId = transaction?.chatMessageId,
                            cloudId = transaction?.cloudId
                        )
                        if (onConfirmAsync != null) {
                            val saveAsync = onConfirmAsync
                            saveAsync(pendingTransaction) { success ->
                                if (success) {
                                    // BUG-08: tutup HANYA lewat dismiss() → onDismiss().
                                    dismiss()
                                } else {
                                    // B3: sheet TETAP terbuka bila insert gagal.
                                    isSaving = false
                                    AppSnackbar.show(
                                        message = saveFailedLabel,
                                        actionLabel = retryLabel,
                                        onAction = {
                                            isSaving = true
                                            saveAsync(pendingTransaction) { retryOk ->
                                                isSaving = !retryOk
                                                if (retryOk) dismiss()
                                            }
                                        }
                                    )
                                }
                            }
                        } else {
                            // Jalur caller lama: onConfirm + dismiss(). Kegagalan
                            // insert diumumkan ViewModel lewat operationFailed →
                            // AppSnackbar ux_save_failed + Coba lagi.
                            onConfirm(pendingTransaction)
                            // BUG-08: jangan pernah menutup dari onConfirm — lihat
                            // komentar di dismiss().
                            dismiss()
                        }
                    },
                    enabled = !isSaving && description.isNotBlank() && amountText.isNotBlank() && (parseAmount(amountText) ?: 0.0) > 0,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp).widthIn(min = 48.dp)
                        .testTag("add_dialog_save_button"),
                    shape = RoundedCornerShape(Constants.Ui.CORNER_M.dp)
                ) {
                    Text(stringResource(R.string.add_save), fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
