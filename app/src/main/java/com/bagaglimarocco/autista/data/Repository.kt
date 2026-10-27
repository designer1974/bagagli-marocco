package com.bagaglimarocco.autista.data

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/** Esito di una scansione QR. */
sealed interface ScanResult {
    data class Ok(val bag: BagEntity) : ScanResult
    data class AlreadyUnloaded(val bag: BagEntity) : ScanResult
    data class WrongStop(val bag: BagEntity, val stopName: String) : ScanResult
    data class Unknown(val code: String) : ScanResult
}

/**
 * Unico punto di accesso ai dati. Tutto viene salvato prima sul telefono
 * (synced = false) e poi inviato al server da SyncWorker quando c'è rete.
 */
class Repository(private val context: Context, private val db: AppDatabase) {

    val trip: Flow<TripEntity?> = db.tripDao().observe()
    val stops: Flow<List<StopEntity>> = db.stopDao().observeAll()
    val bags: Flow<List<BagEntity>> = db.bagDao().observeAll()

    suspend fun seedIfEmpty() = withContext(Dispatchers.IO) {
        if (db.stopDao().count() == 0) {
            db.tripDao().upsert(SampleData.trip)
            db.stopDao().insertAll(SampleData.stops)
            db.bagDao().insertAll(SampleData.bags)
        }
    }

    suspend fun scan(rawCode: String, currentStopId: Long?): ScanResult = withContext(Dispatchers.IO) {
        val code = normalize(rawCode)
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

    suspend fun reportDamage(code: String) = withContext(Dispatchers.IO) {
        val bag = db.bagDao().get(normalize(code)) ?: return@withContext
        db.bagDao().update(bag.copy(status = BagStatus.DAMAGED, scannedAt = System.currentTimeMillis(), synced = false))
    }

    suspend fun confirmDelivery(stopId: Long, signature: Bitmap): Boolean = withContext(Dispatchers.IO) {
        val stop = db.stopDao().get(stopId) ?: return@withContext false
        val dir = File(context.filesDir, "firme").apply { mkdirs() }
        val file = File(dir, "stop_${stopId}_${System.currentTimeMillis()}.png")
        FileOutputStream(file).use { signature.compress(Bitmap.CompressFormat.PNG, 100, it) }
        db.stopDao().update(
            stop.copy(
                status = StopStatus.DELIVERED,
                deliveredAt = System.currentTimeMillis(),
                signaturePath = file.absolutePath,
                synced = false,
            ),
        )
        true
    }

    companion object {
        fun normalize(code: String): String = code.trim().uppercase().replace(' ', '-')
    }
}
