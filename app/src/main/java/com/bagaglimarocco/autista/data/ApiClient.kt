package com.bagaglimarocco.autista.data

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Errore con un messaggio da mostrare all'autista. */
class ApiException(val status: Int, message: String) : IOException(message)

data class LoginResult(val token: String, val name: String, val role: String)

data class SyncResult(val acceptedBags: Set<String>, val acceptedStops: Set<Long>, val rejected: List<String>)

/**
 * Client HTTP del server. Usa solo le librerie di Android (niente dipendenze extra).
 * Tutte le chiamate girano fuori dal thread dell'interfaccia.
 */
class ApiClient(baseUrl: String, private val token: String? = null) {
    private val base = baseUrl.trim().trimEnd('/')

    suspend fun login(username: String, password: String): LoginResult {
        val body = JSONObject().put("username", username).put("password", password)
        val json = JSONObject(request("POST", "/api/v1/login", body))
        val user = json.getJSONObject("user")
        return LoginResult(json.getString("token"), user.getString("name"), user.getString("role"))
    }

    suspend fun logout() {
        request("POST", "/api/v1/logout", null)
    }

    /** Il viaggio assegnato, convertito nei dati del database del telefono. */
    suspend fun driverTrip(): Triple<TripEntity, List<StopEntity>, List<BagEntity>> {
        val json = JSONObject(request("GET", "/api/v1/driver/trip", null))
        val t = json.getJSONObject("trip")
        val trip = TripEntity(
            id = t.getLong("id"),
            vehiclePlate = t.getString("vehiclePlate"),
            route = t.getString("route"),
            ferryRoute = t.optString("ferryRoute"),
            boardingBy = t.optString("boardingBy"),
            departure = t.optString("departure"),
        )
        val stops = json.getJSONArray("stops").objects().map { s ->
            StopEntity(
                id = s.getLong("id"),
                position = s.getInt("position"),
                name = s.getString("name"),
                address = s.getString("address"),
                city = s.getString("city"),
                latitude = s.getDouble("latitude"),
                longitude = s.getDouble("longitude"),
                phone = s.optString("phone"),
                kind = StopKind.valueOf(s.getString("kind")),
                recipientCodeHash = s.getString("recipientCodeHash"),
                status = StopStatus.valueOf(s.getString("status")),
                deliveredAt = if (s.isNull("deliveredAt")) null else s.getLong("deliveredAt"),
            )
        }
        val bags = json.getJSONArray("bags").objects().map { b ->
            BagEntity(
                code = b.getString("code"),
                stopId = b.getLong("stopId"),
                description = b.getString("description"),
                weightKg = b.getDouble("weightKg"),
                status = BagStatus.valueOf(b.getString("status")),
                scannedAt = if (b.isNull("scannedAt")) null else b.getLong("scannedAt"),
            )
        }
        return Triple(trip, stops, bags)
    }

    suspend fun sync(bags: List<BagEntity>, deliveries: List<Pair<StopEntity, String>>): SyncResult {
        val body = JSONObject()
            .put("bags", JSONArray().apply {
                bags.forEach { put(JSONObject().put("code", it.code).put("status", it.status.name).put("scannedAt", it.scannedAt ?: 0L)) }
            })
            .put("deliveries", JSONArray().apply {
                deliveries.forEach { (stop, signatureBase64) ->
                    put(
                        JSONObject()
                            .put("stopId", stop.id)
                            .put("deliveredAt", stop.deliveredAt ?: 0L)
                            .put("recipientCode", stop.enteredCode.orEmpty())
                            .put("signaturePng", signatureBase64),
                    )
                }
            })
        val json = JSONObject(request("POST", "/api/v1/driver/sync", body))
        val accBags = json.getJSONArray("acceptedBags").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
        val accStops = json.getJSONArray("acceptedStops").let { a -> (0 until a.length()).map { a.getLong(it) }.toSet() }
        val rejected = json.getJSONArray("rejected").objects().map { "${it.getString("ref")}: ${it.getString("reason")}" }
        return SyncResult(accBags, accStops, rejected)
    }

    private suspend fun request(method: String, path: String, body: JSONObject?): String = withContext(Dispatchers.IO) {
        val conn = URL(base + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("Accept", "application/json")
            token?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val msg = runCatching { JSONObject(text).getString("error") }.getOrDefault("Errore del server ($code)")
                throw ApiException(code, msg)
            }
            text.ifEmpty { "{}" }
        } finally {
            conn.disconnect()
        }
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
}
