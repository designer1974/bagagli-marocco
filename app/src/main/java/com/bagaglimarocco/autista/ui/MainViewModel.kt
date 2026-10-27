package com.bagaglimarocco.autista.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bagaglimarocco.autista.AutistaApp
import com.bagaglimarocco.autista.R
import com.bagaglimarocco.autista.data.BagEntity
import com.bagaglimarocco.autista.data.BagStatus
import com.bagaglimarocco.autista.data.ScanResult
import com.bagaglimarocco.autista.data.StopEntity
import com.bagaglimarocco.autista.data.StopStatus
import com.bagaglimarocco.autista.data.TripEntity
import com.bagaglimarocco.autista.sync.SyncWorker
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class StopWithBags(val stop: StopEntity, val bags: List<BagEntity>) {
    val unloaded: Int get() = bags.count { it.status != BagStatus.LOADED }
    val remaining: List<BagEntity> get() = bags.filter { it.status == BagStatus.LOADED }
}

data class UiState(
    val loading: Boolean = true,
    val trip: TripEntity? = null,
    val stops: List<StopWithBags> = emptyList(),
    val unsyncedCount: Int = 0,
) {
    val current: StopWithBags? get() = stops.firstOrNull { it.stop.status == StopStatus.PENDING }
    val deliveredCount: Int get() = stops.count { it.stop.status == StopStatus.DELIVERED }
    val totalBags: Int get() = stops.sumOf { it.bags.size }
}

data class UiMessage(@StringRes val res: Int, val args: List<Any> = emptyList(), val isError: Boolean = false)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = (app as AutistaApp).repository

    private val _messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 4)
    val messages: SharedFlow<UiMessage> = _messages

    /** Ultimo bagaglio letto correttamente, mostrato nella schermata di scansione. */
    private val _lastScanned = MutableSharedFlow<BagEntity?>(replay = 1)
    val lastScanned: SharedFlow<BagEntity?> = _lastScanned

    val state: StateFlow<UiState> = combine(repo.trip, repo.stops, repo.bags) { trip, stops, bags ->
        val byStop = bags.groupBy { it.stopId }
        UiState(
            loading = false,
            trip = trip,
            stops = stops.map { StopWithBags(it, byStop[it.id].orEmpty()) },
            unsyncedCount = stops.count { !it.synced } + bags.count { !it.synced },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    init {
        viewModelScope.launch { repo.seedIfEmpty() }
    }

    /** Evita di leggere lo stesso QR più volte di seguito mentre è inquadrato. */
    private var lastCode: String? = null
    private var lastCodeAt = 0L

    fun onScanned(raw: String, fromCamera: Boolean = true) {
        val now = System.currentTimeMillis()
        if (fromCamera && raw == lastCode && now - lastCodeAt < 3_000) return
        lastCode = raw
        lastCodeAt = now

        viewModelScope.launch {
            val msg = when (val r = repo.scan(raw, state.value.current?.stop?.id)) {
                is ScanResult.Ok -> {
                    _lastScanned.emit(r.bag)
                    UiMessage(R.string.scan_ok)
                }
                is ScanResult.AlreadyUnloaded -> UiMessage(R.string.scan_already)
                is ScanResult.WrongStop -> UiMessage(R.string.scan_wrong_stop, listOf(r.stopName), isError = true)
                is ScanResult.Unknown -> UiMessage(R.string.scan_unknown, listOf(r.code), isError = true)
            }
            _messages.emit(msg)
        }
    }

    fun reportDamage(code: String) {
        viewModelScope.launch {
            repo.reportDamage(code)
            _messages.emit(UiMessage(R.string.damage_saved, listOf(code)))
        }
    }

    /** Restituisce true se la consegna è stata salvata. */
    fun confirmDelivery(enteredCode: String, signature: Bitmap?, onDone: () -> Unit) {
        val current = state.value.current ?: return
        viewModelScope.launch {
            val error = when {
                current.remaining.isNotEmpty() -> UiMessage(R.string.error_bags, listOf(current.remaining.size), true)
                enteredCode.trim() != current.stop.recipientCode -> UiMessage(R.string.error_code, isError = true)
                signature == null -> UiMessage(R.string.error_signature, isError = true)
                else -> null
            }
            if (error != null) {
                _messages.emit(error)
                return@launch
            }
            if (repo.confirmDelivery(current.stop.id, signature!!)) {
                _lastScanned.emit(null)
                SyncWorker.syncNow(getApplication())
                _messages.emit(UiMessage(R.string.delivered_saved))
                onDone()
            }
        }
    }
}
