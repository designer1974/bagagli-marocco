package com.bagaglimarocco.autista.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class StopKind { HOME, AGENCY }
enum class StopStatus { PENDING, DELIVERED }
enum class BagStatus { LOADED, UNLOADED, DAMAGED }

/** Una fermata di consegna: casa del destinatario o agenzia di ritiro. */
@Entity(tableName = "stops")
data class StopEntity(
    @PrimaryKey val id: Long,
    val position: Int,
    val name: String,
    val address: String,
    val city: String,
    val latitude: Double,
    val longitude: Double,
    val phone: String,
    val kind: StopKind,
    val recipientCode: String,
    val status: StopStatus = StopStatus.PENDING,
    val deliveredAt: Long? = null,
    val signaturePath: String? = null,
    val synced: Boolean = true,
)

/** Un bagaglio con la sua etichetta QR. */
@Entity(tableName = "bags", indices = [Index("stopId")])
data class BagEntity(
    @PrimaryKey val code: String,
    val stopId: Long,
    val description: String,
    val weightKg: Double,
    val status: BagStatus = BagStatus.LOADED,
    val scannedAt: Long? = null,
    val synced: Boolean = true,
)

/** Il traghetto del viaggio. */
@Entity(tableName = "trip")
data class TripEntity(
    @PrimaryKey val id: Long = 1,
    val vehiclePlate: String,
    val route: String,
    val ferryRoute: String,
    val boardingBy: String,
    val departure: String,
)
