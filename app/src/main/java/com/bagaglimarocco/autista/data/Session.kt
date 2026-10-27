package com.bagaglimarocco.autista.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Chi è collegato. demo = true significa: dati di prova, nessun server. */
data class Session(val serverUrl: String, val token: String, val driverName: String, val demo: Boolean = false)

/**
 * Salva la sessione nelle preferenze private dell'app (non accessibili da altre app,
 * escluse dai backup: allowBackup = false).
 */
class SessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)
    private val _session = MutableStateFlow(read())
    val session: StateFlow<Session?> = _session

    val lastServerUrl: String get() = prefs.getString("lastServerUrl", null).orEmpty()

    private fun read(): Session? {
        val token = prefs.getString("token", null) ?: return null
        return Session(
            serverUrl = prefs.getString("serverUrl", "").orEmpty(),
            token = token,
            driverName = prefs.getString("driverName", "").orEmpty(),
            demo = prefs.getBoolean("demo", false),
        )
    }

    fun save(s: Session) {
        prefs.edit()
            .putString("serverUrl", s.serverUrl)
            .putString("token", s.token)
            .putString("driverName", s.driverName)
            .putBoolean("demo", s.demo)
            .apply { if (!s.demo) putString("lastServerUrl", s.serverUrl) }
            .apply()
        _session.value = s
    }

    fun clear() {
        prefs.edit().remove("token").remove("serverUrl").remove("driverName").remove("demo").apply()
        _session.value = null
    }
}
