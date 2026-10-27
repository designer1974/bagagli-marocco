package com.bagaglimarocco.autista.data

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import androidx.room.withTransaction
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Esito di una scansione QR. */
sealed interface ScanResult {
    data class Ok(val bag: BagEntity) : ScanResult
    data class AlreadyUnloaded(val bag: BagEntity) : ScanResult
    data class WrongStop(val bag: BagEntity, val stopName: String) : ScanResult
    data class Unknown(val code: String) : ScanResult
}

/** Esito della conferma di consegna. */
enum class DeliveryResult { OK, WRONG_CODE, NOT_FOUND }

/** Ci sono scansioni o consegne non ancora inviate: non si può sostituire il viaggio. */
class UnsyncedDataException : IllegalStateException("Ci sono dati non ancora inviati al server")

/**
 * Unico punto di accesso ai dati. Tutto viene salvato prima sul telefono
 * (synced = false) e poi inviato al server quando c'è rete.
 */
class Repository(private val context: Context, private val db: AppDatabase, val sessions: SessionStore) {

    val trip: Flow<TripEntity?> = db.tripDao().observe()
    val stops: Flow<List<StopEntity>> = db.stopDao().observeAll()
    val bags: Flow<List<BagEntity>> = db.bagDao().observeAll()

    /** Una sola sincronizzazione alla volta (app e lavoro in background). */
    private val syncMutex = Mutex()

    private fun api(): ApiClient? {
        val s = sessions.session.value ?: return null
        if (s.demo || s.serverUrl.isBlank()) return null
        return ApiClient(s.serverUrl, s.token)
    }

    suspend fun login(serverUrl: String, username: String, password: String) {
        val url = serverUrl.trim().trimEnd('/')
        val result = ApiClient(url).login(username.trim(), password)
        if (result.role != "driver") throw ApiException(403, "Questo account non è di un autista")
        sessions.save(Session(url, result.token, result.name))
        refreshFromServer()
    }

    suspend fun startDemo() {
        sessions.save(Session(serverUrl = "", token = "demo", driverName = "Demo", demo = true))
        replaceAll(SampleData.trip, SampleData.stops, SampleData.bags)
    }

    /** Esce. Restituisce false se ci sono dati non inviati (non si esce per non perderli). */
    suspend fun logout(force: Boolean = false): Boolean {
        val demo = sessions.session.value?.demo == true
        if (!force && !demo && unsyncedCount() > 0) return false
        runCatching { api()?.logout() }
        sessions.clear()
        replaceAll(null, emptyList(), emptyList())
        return true
    }

    suspend fun unsyncedCount(): Int = withContext(Dispatchers.IO) {
        db.stopDao().unsyncedCount() + db.bagDao().unsyncedCount()
    }

    /** Scarica il viaggio dal server. Prima invia i dati offline, così non si perde niente. */
    suspend fun refreshFromServer() {
        val api = api() ?: return
        syncNow()
        if (unsyncedCount() > 0) throw UnsyncedDataException()
        val (trip, stops, bags) = try {
            api.driverTrip()
        } catch (e: ApiException) {
            if (e.status == 404) {
                replaceAll(null, emptyList(), emptyList())
                return
            }
            throw e
        }
        syncMutex.withLock {
            if (unsyncedCount() > 0) throw UnsyncedDataException()
            replaceAll(trip, stops, bags)
        }
    }

    private suspend fun replaceAll(trip: TripEntity?, stops: List<StopEntity>, bags: List<BagEntity>) = withContext(Dispatchers.IO) {
        db.withTransaction {
            db.bagDao().deleteAll()
            db.stopDao().deleteAll()
            db.tripDao().deleteAll()
            trip?.let { db.tripDao().upsert(it.copy(id = 1)) }
            db.stopDao().insertAll(stops)
            db.bagDao().insertAll(bags)
        }
    }

    /**
     * Invia al server tutto quello che non è ancora stato inviato.
     * Un dato è segnato come inviato solo dopo la risposta positiva del server.
     * Restituisce il numero di dati ancora da inviare.
     */
    suspend fun syncNow(): Int = syncMutex.withLock {
        withContext(Dispatchers.IO) {
            val api = api() ?: return@withContext 0
            val bags = db.bagDao().unsynced()
            val stops = db.stopDao().unsynced().filter { it.status == StopStatus.DELIVERED }
            if (bags.isEmpty() && stops.isEmpty()) return@withContext 0

            val deliveries = stops.mapNotNull { stop ->
                val file = stop.signaturePath?.let(::File)
                if (file == null || !file.exists()) {
                    Log.w(TAG, "Firma mancante per la fermata ${stop.id}")
                    null
                } else {
                    stop to Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
                }
            }
            val result = try {
                api.sync(bags, deliveries)
            } catch (e: ApiException) {
                if (e.status == 401) sessions.clear() // token scaduto: l'autista rientra, i dati restano
                throw e
            }
            if (result.rejected.isNotEmpty()) Log.w(TAG, "Rifiutati dal server: ${result.rejected}")

            // I dati rifiutati per sempre (es. bagaglio non di questo viaggio) non si ritentano.
            val rejectedRefs = result.rejected.map { it.substringBefore(':') }.toSet()
            db.withTransaction {
                db.bagDao().markSynced(bags.map { it.code }.filter { it in result.acceptedBags || it in rejectedRefs })
                db.stopDao().markSynced(stops.map { it.id }.filter { it in result.acceptedStops || it.toString() in rejectedRefs })
            }
            db.stopDao().unsyncedCount() + db.bagDao().unsyncedCount()
        }
    }

    suspend fun scan(rawCode: String, currentStopId: Long?): ScanResult = withContext(Dispatchers.IO) {
        val code = Codes.normalizeBag(rawCode)
        val bag = db.bagDao().get(code) ?: return@withContext ScanResult.Unknown(code)
        if (currentStopId != null && bag.stopId != currentStopId) {
            val stopName = db.stopDao().get(bag.stopId)?.name ?: "?"
            return@withContext ScanResult.WrongStop(bag, stopName)
        }
        if (bag.status != BagStatus.LOADED) return@withContext ScanResult.AlreadyUnloaded(bag)
        val updated = bag.copy(status = BagStatus.UNLOADED, scannedAt = System.currentTimeMillis(), synced = false)
        db.bagDao().update(updated)
        ScanResult.Ok(updated)
    }

    suspend fun reportDamage(code: String): Boolean = withContext(Dispatchers.IO) {
        val bag = db.bagDao().get(Codes.normalizeBag(code)) ?: return@withContext false
        db.bagDao().update(bag.copy(status = BagStatus.DAMAGED, scannedAt = System.currentTimeMillis(), synced = false))
        true
    }

    suspend fun confirmDelivery(stopId: Long, enteredCode: String, signature: Bitmap): DeliveryResult = withContext(Dispatchers.IO) {
        val stop = db.stopDao().get(stopId) ?: return@withContext DeliveryResult.NOT_FOUND
        if (Codes.recipientCodeHash(stopId, enteredCode.trim()) != stop.recipientCodeHash) {
            return@withContext DeliveryResult.WRONG_CODE
        }
        val dir = File(context.filesDir, "firme").apply { mkdirs() }
        val file = File(dir, "stop_$stopId.png")
        val tmp = File(dir, "stop_$stopId.tmp")
        FileOutputStream(tmp).use {
            signature.compress(Bitmap.CompressFormat.PNG, 100, it)
            it.fd.sync()
        }
        tmp.renameTo(file)
        db.stopDao().update(
            stop.copy(
                status = StopStatus.DELIVERED,
                deliveredAt = System.currentTimeMillis(),
                enteredCode = enteredCode.trim(),
                signaturePath = file.absolutePath,
                synced = false,
            ),
        )
        DeliveryResult.OK
    }

    companion object {
        private const val TAG = "Repository"
    }
}
