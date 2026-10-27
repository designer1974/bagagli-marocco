package com.bagaglimarocco.autista.sync

import android.content.Context
import android.util.Log
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
import com.bagaglimarocco.autista.AutistaApp
import com.bagaglimarocco.autista.data.ApiException
import java.util.concurrent.TimeUnit

/**
 * Invia al server scansioni e consegne salvate offline.
 * Gira solo con rete e riprova da solo in caso di errore; i dati restano sul telefono finché il server non conferma.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repo = (applicationContext as AutistaApp).repository
        return try {
            val remaining = repo.syncNow()
            if (remaining > 0) Result.retry() else Result.success()
        } catch (e: ApiException) {
            Log.w("SyncWorker", "Server: ${e.message}")
            // 401: l'autista deve rientrare; 4xx: non serve ritentare subito.
            if (e.status >= 500) Result.retry() else Result.failure()
        } catch (e: Exception) {
            Log.w("SyncWorker", "Rete non disponibile o errore: ${e.message}")
            if (runAttemptCount < 20) Result.retry() else Result.failure()
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
