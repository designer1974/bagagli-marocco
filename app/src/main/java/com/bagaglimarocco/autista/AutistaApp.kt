package com.bagaglimarocco.autista

import android.app.Application
import com.bagaglimarocco.autista.data.AppDatabase
import com.bagaglimarocco.autista.data.Repository
import com.bagaglimarocco.autista.data.SessionStore
import com.bagaglimarocco.autista.sync.SyncWorker

class AutistaApp : Application() {
    lateinit var repository: Repository
        private set

    override fun onCreate() {
        super.onCreate()
        repository = Repository(this, AppDatabase.get(this), SessionStore(this))
        SyncWorker.schedulePeriodic(this)
    }
}
