package com.startupmini.nyachat.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GroupAdd
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.startupmini.nyachat.R
import com.startupmini.nyachat.data.remote.JoinRequestResult
import com.startupmini.nyachat.data.remote.MembershipManager
import com.startupmini.nyachat.data.remote.MembershipStatus
import com.startupmini.nyachat.data.remote.OwnerSetupResult
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.delay

private enum class GateState { CHECKING, WAITING, ERROR }

/** Saver agar enum [GateState] bisa disimpan saat rotasi layar (P2-11). */
private val GateStateSaver = Saver<GateState, String>(
    save = { it.name },
    restore = { GateState.valueOf(it) }
)

/**
 * Batas total waktu menunggu persetujuan owner (anti-gantung selamanya, P2-10).
 * R2-B2: pakai konstanta yang SAMA dengan timeout listener di MembershipManager
 * supaya waktu yang ditampilkan jujur (dulu gate 30 menit vs listener 10 menit).
 */
private const val GATE_MAX_WAIT_MS = MembershipManager.JOIN_DECISION_TIMEOUT_MS

/**
 * Gate masuk workspace setelah PIN dimasukkan:
 * - Pemilik (owner): siapkan workspace (ownerId + member doc sendiri), lalu lanjut.
 * - Anggota: cek keanggotaan; kalau belum terdaftar, kirim permintaan bergabung
 *   dan TUNGGU persetujuan pemilik (polling 3 detik) sampai disetujui.
 * Keputusan langkah berikutnya didelegasikan ke [MembershipGateLogic] (murni,
 * bisa di-unit-test); file ini hanya menjalankan efek samping (Firestore, delay).
 * Ini yang menegakkan alur "PIN + persetujuan owner" sebelum data terbuka.
 */
@Composable
fun MembershipGateScreen(
    pin: String,
    role: String,
    onReady: () -> Unit,
    onCancel: () -> Unit
) {
    // rememberSaveable supaya rotasi layar tidak me-reset alur gate: rotasi yang
    // me-restart composable akan me-re-run LaunchedEffect — kalau state (mis.
    // requestedOnce) hilang, permintaan bergabung bisa TERKIRIM ULANG dobel.
    var state by rememberSaveable(stateSaver = GateStateSaver) { mutableStateOf(GateState.CHECKING) }
    var error by rememberSaveable { mutableStateOf<GateError?>(null) }
    var requestedOnce by rememberSaveable { mutableStateOf(false) }
    var attempt by rememberSaveable { mutableIntStateOf(0) }
    // F5: batas tunggu dihitung SEKALI saat gate pertama tampil dan dipertahankan
    // lintas rotasi. Sebelumnya deadline dibuat ulang di dalam LaunchedEffect tiap
    // retry → tombol "Coba Lagi" memberi waktu tunggu segar tanpa batas.
    val gateDeadline by rememberSaveable {
        mutableLongStateOf(System.currentTimeMillis() + GATE_MAX_WAIT_MS)
    }
    val currentOnReady by rememberUpdatedState(onReady)
    // Audit M5/F1: throttle server (RESOURCE_EXHAUSTED dari registerPinAttempt)
    // jangan dilaporkan sebagai "gagal terhubung" — user akan menekan Coba Lagi
    // dan justru memperpanjang lockout.
    fun failureGateError(): GateError = if (
        MembershipManager.lastFailure.value?.code == "RESOURCE_EXHAUSTED"
    ) GateError.THROTTLED else GateError.FAILED

    LaunchedEffect(pin, role, attempt) {
        // Batas total tunggu (F5): pakai deadline yang sama untuk semua retry.
        val deadline = gateDeadline
        if (role == MembershipManager.ROLE_OWNER) {
            when (MembershipManager.ensureOwnerWorkspace(pin)) {
                OwnerSetupResult.SUCCESS -> currentOnReady()
                OwnerSetupResult.ALREADY_OWNED -> { error = GateError.PIN_OWNED; state = GateState.ERROR }
                // Audit workspace (2026-08-14): akun sudah jadi owner di workspace
                // LAIN — blokir bikin baru sampai lama dihapus/diwariskan.
                OwnerSetupResult.OWNED_ELSEWHERE -> { error = GateError.OWNED_ELSEWHERE; state = GateState.ERROR }
                OwnerSetupResult.FAILED -> { error = GateError.FAILED; state = GateState.ERROR }
            }
            return@LaunchedEffect
        }

        /**
         * R2-B2: timeout listener TIDAK serta-merta berarti permintaan hilang.
         * Cek dokumen aslinya: bila masih PENDING, pasang ulang listener dan
         * pertahankan requestedOnce = true — jangan jatuh ke SendJoinRequest yang
         * mengirim permintaan dobel. Hanya bila dokumen sudah tidak ada kita
         * kembalikan status terbaru (MEMBER / NOT_REQUESTED / FAILED) untuk
         * ditangani [applyDecision].
         */
        suspend fun waitForDecisionWithRetry(): MembershipStatus {
            while (true) {
                val decision = MembershipManager.waitForJoinRequestDecision(pin)
                if (decision != MembershipStatus.TIMED_OUT) return decision
                val recheck = MembershipManager.checkMembership(pin)
                if (recheck != MembershipStatus.PENDING) return recheck
                // Masih menunggu persetujuan owner — request sudah pernah dikirim.
                requestedOnce = true
                state = GateState.WAITING
            }
        }

        /** Tangani keputusan; return true bila alur gate harus berhenti. */
        fun applyDecision(decision: MembershipStatus): Boolean {
            when (decision) {
                MembershipStatus.MEMBER -> {
                    currentOnReady()
                    return true
                }
                MembershipStatus.NOT_REQUESTED -> {
                    error = GateError.REJECTED
                    state = GateState.ERROR
                    return true
                }
                MembershipStatus.FAILED -> {
                    error = GateError.FAILED
                    state = GateState.ERROR
                    return true
                }
                MembershipStatus.TIMED_OUT -> {
                    error = GateError.TIMEOUT
                    state = GateState.ERROR
                    return true
                }
                else -> return false // PENDING tidak dikembalikan waitForJoinRequestDecision
            }
        }

        // Anggota: cek → minta → tunggu persetujuan owner VIA LISTENER REALTIME
        // (bukan polling 3 detik). Minta hanya SATU KALI per sesi gate —
        // kalau ditolak (NOT_REQUESTED lagi), berhenti, jangan mengirim ulang berulang.
        while (true) {
            if (System.currentTimeMillis() > deadline) {
                error = GateError.TIMEOUT
                state = GateState.ERROR
                return@LaunchedEffect
            }
            val status = MembershipManager.checkMembership(pin)
            when (val step = MembershipGateLogic.nextStep(status, requestedOnce)) {
                GateStep.Ready -> {
                    currentOnReady()
                    return@LaunchedEffect
                }
                is GateStep.Fail -> {
                    error = step.reason
                    state = GateState.ERROR
                    return@LaunchedEffect
                }
                GateStep.SendJoinRequest -> {
                    state = GateState.CHECKING
                    when (MembershipManager.requestJoin(pin)) {
                        // Rules menolak create kalau keluarga tidak ada → PIN tidak ditemukan.
                        JoinRequestResult.NOT_FOUND -> {
                            error = GateError.NOT_FOUND
                            state = GateState.ERROR
                            return@LaunchedEffect
                        }
                        JoinRequestResult.FAILED -> {
                            error = failureGateError()
                            state = GateState.ERROR
                            return@LaunchedEffect
                        }
                        JoinRequestResult.SUCCESS -> {
                            // Lanjut menunggu persetujuan owner.
                        }
                    }
                    requestedOnce = true
                    state = GateState.WAITING
                    if (applyDecision(waitForDecisionWithRetry())) return@LaunchedEffect
                }
                GateStep.WaitForApproval -> {
                    state = GateState.WAITING
                    // Permintaan sudah ada → pasang ulang listener, JANGAN kirim ulang.
                    if (applyDecision(waitForDecisionWithRetry())) return@LaunchedEffect
                    // Fallback: bila listener tidak menghasilkan keputusan, cek lagi.
                    delay(3_000)
                }
            }
        }
    }

    val errorRes = when (error) {
        GateError.PIN_OWNED -> R.string.membership_error_pin_owned
        GateError.OWNED_ELSEWHERE -> R.string.membership_error_owned_elsewhere
        GateError.NOT_FOUND -> R.string.membership_error_not_found
        GateError.REJECTED -> R.string.membership_error_rejected
        GateError.TIMEOUT -> R.string.membership_error_timeout
        GateError.THROTTLED -> R.string.membership_error_throttled
        else -> R.string.membership_error_failed
    }

    // R2-H7: gate adalah dialog modal — beri tahu TalkBack bahwa ini dialog agar
    // fokus tidak "keluar" ke konten di belakang. Menyembunyikan background UI
    // (Modifier.semantics { invisibleToUser() }) dilakukan MainOverlays yang
    // memasang gate di atas seluruh konten aplikasi (file lain, bukan milik kita).
    Box(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
    ) {
        // F2 (audit focus order): gate dulu tidak punya background → layar login di
        // belakangnya masih TERLIHAT & bisa di-tap saat menunggu persetujuan owner
        // (bisa berlangsung menit). Scrim solid di BAWAH konten gate: menutup visual
        // login + menangkap touch (pointerInput) supaya aksi login tidak bisa dipicu
        // dari balik gate — tombol gate tetap aktif karena scrim adalah sibling yang
        // digambar lebih dulu (dispatch pointer child/leaf lebih dahulu).
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .pointerInput(Unit) {
                    detectTapGestures(onTap = {})
                }
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .align(Alignment.Center)
                // B-M13: gate bisa melebihi tinggi layar di landscape /
                // fontScale besar — izinkan scroll, Center tetap dipertahankan.
                .verticalScroll(rememberScrollState())
                .padding(28.dp)
        ) {
            // stringResource tidak bisa dipanggil di dalam lambda semantics.
            val checkingLabel = stringResource(R.string.ux_status_checking)
            when (state) {
                GateState.CHECKING -> {
                    // B14: indikator butuh label untuk pembaca layar.
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(44.dp)
                            .semantics {
                                contentDescription = checkingLabel
                                stateDescription = checkingLabel
                            }
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Text(
                        text = stringResource(R.string.membership_checking),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                GateState.WAITING -> {
                    GateIcon(Icons.Rounded.Schedule, MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.membership_waiting_title),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.membership_waiting_detail),
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.membership_waiting_pin, pin),
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    // R2-B2: nyatakan keterbatasan nyata — gate tidak bisa
                    // menyetujui saat aplikasi tertutup.
                    Text(
                        text = stringResource(R.string.ux_gate_online_note),
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                GateState.ERROR -> {
                    GateIcon(Icons.Rounded.Warning, MaterialTheme.colorScheme.error)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(errorRes),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.error
                    )
                    // Tampilkan penyebab asli kegagalan (mis. kode Firestore) supaya
                    // bukan sekadar "gagal terhubung" yang menyesatkan — memudahkan
                    // diagnosa di perangkat.
                    if (error == GateError.FAILED) {
                        val lastFailure by MembershipManager.lastFailure.collectAsState()
                        val detail = lastFailure
                        if (detail != null) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = detail.summary,
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // P2-1 (audit keanggotaan): REJECTED (ditolak owner) & PIN_OWNED (PIN
            // dimiliki orang lain) adalah kondisi TERMINAL — "Coba Lagi" hanya akan
            // mengirim ulang permintaan/percobaan yang sama (spam). Untuk kondisi
            // itu hanya ada Batal (kembali ke layar PIN; percobaan ulang lewat
            // PinAttemptLimiter di input PIN).
            if (state == GateState.ERROR &&
                error != GateError.REJECTED &&
                error != GateError.PIN_OWNED &&
                error != GateError.OWNED_ELSEWHERE &&
                error != GateError.THROTTLED
            ) {
                Button(
                    onClick = {
                        // R2-B2: TIMEOUT terminal hanya terjadi bila dokumen request
                        // sudah hilang (waitForDecisionWithRetry memastikan itu) →
                        // aman mengirim ulang. Error lain bisa saja request masih ada
                        // → pertahankan requestedOnce supaya loop PASANG ULANG listener,
                        // bukan mengirim permintaan dobel.
                        val wasTimeout = error == GateError.TIMEOUT
                        error = null
                        if (wasTimeout) requestedOnce = false
                        state = GateState.CHECKING
                        attempt++
                    },
                    modifier = Modifier
                        .fillMaxWidth(0.72f)
                        .height(48.dp)
                ) {
                    Text(
                        text = stringResource(R.string.membership_retry),
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
            }

            // Batal selalu tersedia (termasuk saat CHECKING — P3 audit keanggotaan:
            // cek yang lama karena jaringan buruk tetap bisa dibatalkan).
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth(0.72f).height(48.dp)
            ) {
                Text(
                    text = stringResource(R.string.action_cancel),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun GateIcon(icon: ImageVector, tint: androidx.compose.ui.graphics.Color) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .size(72.dp)
            .padding(16.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(40.dp)
        )
    }
}
