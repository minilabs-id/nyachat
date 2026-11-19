package com.startupmini.nyachat.ui

import androidx.compose.material3.SnackbarDuration
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Audit UX R2-H5: SATU kanal umpan balik untuk seluruh aplikasi.
 *
 * Sebelumnya ada tiga kanal yang aktif bersamaan: SnackbarHost global di
 * MainOverlays (TopCenter, 480dp), SnackbarHost lokal di ChatScreen &
 * ManageMembersScreen (BottomCenter, Short), plus Toast di beberapa tempat.
 * Akibatnya aksi sekelas memunculkan snackbar di posisi/durasi berbeda.
 *
 * Pemanggil cukup `AppSnackbar.show(message)`; MainActivity memasang SATU
 * SnackbarHost yang mengumpulkan event dari sini. Tidak ada parameter
 * SnackbarHostState yang perlu diteruskan lintas layar.
 */
object AppSnackbar {
    data class Event(
        val message: String,
        val duration: SnackbarDuration = SnackbarDuration.Short,
        val actionLabel: String? = null,
        val onAction: (() -> Unit)? = null
    )

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 16)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    fun show(
        message: String,
        duration: SnackbarDuration = SnackbarDuration.Short,
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null
    ) {
        _events.tryEmit(Event(message, duration, actionLabel, onAction))
    }
}
