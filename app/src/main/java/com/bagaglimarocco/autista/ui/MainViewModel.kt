package com.bagaglimarocco.autista.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bagaglimarocco.autista.AutistaApp
import com.bagaglimarocco.autista.R
import com.bagaglimarocco.autista.data.ApiException
import com.bagaglimarocco.autista.data.BagEntity
import com.bagaglimarocco.autista.data.BagStatus
import com.bagaglimarocco.autista.data.DeliveryResult
import com.bagaglimarocco.autista.data.ScanResult
import com.bagaglimarocco.autista.data.Session
import com.bagaglimarocco.autista.data.StopEntity
import com.bagaglimarocco.autista.data.StopStatus
import com.bagaglimarocco.autista.data.TripEntity
import com.bagaglimarocco.autista.data.UnsyncedDataException
import com.bagaglimarocco.autista.sync.SyncWorker
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
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

/** Messaggio da mostrare: da una stringa tradotta oppure testo già pronto (es. errore del server). */
data class UiMessage(
    @StringRes val res: Int,
    val args: List<Any> = emptyList(),
    val isError: Boolean = false,
    val text: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = (app as AutistaApp).repository

    val session: StateFlow<Session?> = repo.sessions.session
    val lastServerUrl: String get() = repo.sessions.lastServerUrl

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

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

    private fun emit(msg: UiMessage) {
        _messages.tryEmit(msg)
    }

    private fun errorMessage(e: Throwable): UiMessage = when (e) {
        is ApiException -> UiMessage(R.string.error_server, isError = true, text = e.message)
        is UnsyncedDataException -> UiMessage(R.string.error_unsynced, isError = true)
        else -> UiMessage(R.string.error_network, isError = true)
    }

    /** Esegue un'operazione lunga mostrando la rotellina; gli errori diventano messaggi, mai crash. */
    private fun runBusy(block: suspend () -> Unit) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                block()
            } catch (e: Exception) {
                emit(errorMessage(e))
            } finally {
                _busy.value = false
            }
        }
    }

    fun login(serverUrl: String, username: String, password: String) {
        val url = serverUrl.trim()
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            emit(UiMessage(R.string.error_url, isError = true))
            return
        }
        runBusy { repo.login(url, username, password) }
    }

    fun startDemo() = runBusy { repo.startDemo() }

    fun refresh() = runBusy {
        repo.refreshFromServer()
        emit(UiMessage(R.string.refreshed))
    }

    fun logout() = runBusy {
        if (!repo.logout()) emit(UiMessage(R.string.error_unsynced, isError = true))
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
                    SyncWorker.syncNow(getApplication())
                    UiMessage(R.string.scan_ok)
                }
                is ScanResult.AlreadyUnloaded -> UiMessage(R.string.scan_already)
                is ScanResult.WrongStop -> UiMessage(R.string.scan_wrong_stop, listOf(r.stopName), isError = true)
                is ScanResult.Unknown -> UiMessage(R.string.scan_unknown, listOf(r.code), isError = true)
            }
            emit(msg)
        }
    }

    fun reportDamage(code: String) {
        viewModelScope.launch {
            if (repo.reportDamage(code)) {
                SyncWorker.syncNow(getApplication())
                emit(UiMessage(R.string.damage_saved, listOf(code)))
            } else {
                emit(UiMessage(R.string.scan_unknown, listOf(code), isError = true))
            }
        }
    }

    fun confirmDelivery(enteredCode: String, signature: Bitmap?, onDone: () -> Unit) {
        val current = state.value.current ?: return
        when {
            current.remaining.isNotEmpty() -> return emit(UiMessage(R.string.error_bags, listOf(current.remaining.size), true))
            enteredCode.length != 4 -> return emit(UiMessage(R.string.error_code, isError = true))
            signature == null -> return emit(UiMessage(R.string.error_signature, isError = true))
        }
        viewModelScope.launch {
            when (repo.confirmDelivery(current.stop.id, enteredCode, signature!!)) {
                DeliveryResult.OK -> {
                    _lastScanned.emit(null)
                    SyncWorker.syncNow(getApplication())
                    emit(UiMessage(R.string.delivered_saved))
                    onDone()
                }
                DeliveryResult.WRONG_CODE -> emit(UiMessage(R.string.error_code, isError = true))
                DeliveryResult.NOT_FOUND -> emit(UiMessage(R.string.no_stop, isError = true))
            }
        }
    }
}
