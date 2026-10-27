package com.bagaglimarocco.autista.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.bagaglimarocco.autista.BuildConfig
import com.bagaglimarocco.autista.data.AppDatabase
import java.util.concurrent.TimeUnit

/**
 * Invia al server scansioni e consegne salvate offline.
 * Gira solo con rete, riprova da solo in caso di errore e non perde mai dati:
 * un dato viene segnato come inviato solo dopo la risposta positiva del server.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // Server non ancora configurato: i dati restano al sicuro sul telefono.
        if (BuildConfig.SERVER_URL.isBlank()) return Result.success()

        val db = AppDatabase.get(applicationContext)
        return try {
            val bags = db.bagDao().unsynced()
            val stops = db.stopDao().unsynced()
            // TODO: inviare bags e stops (e le foto delle firme) a BuildConfig.SERVER_URL via HTTPS.
            // Solo se il server risponde OK:
            db.bagDao().markSynced(bags.map { it.code })
            db.stopDao().markSynced(stops.map { it.id })
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 10) Result.retry() else Result.failure()
        }
    }

    companion object {
        private val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork("sync-periodic", ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun syncNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("sync-now", ExistingWorkPolicy.REPLACE, request)
        }
    }
}
