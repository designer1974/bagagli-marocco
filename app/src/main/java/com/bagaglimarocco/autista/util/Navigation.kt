package com.bagaglimarocco.autista.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/** Apre le app di navigazione esterne. Restituisce false se non è stato possibile. */
object NavApps {

    fun openWaze(context: Context, lat: Double, lon: Double): Boolean =
        start(context, Uri.parse("waze://?ll=$lat,$lon&navigate=yes")) ||
            start(context, Uri.parse("https://waze.com/ul?ll=$lat,$lon&navigate=yes"))

    fun openGoogleMaps(context: Context, lat: Double, lon: Double): Boolean {
        val nav = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$lat,$lon&mode=d"))
            .setPackage("com.google.android.apps.maps")
        return start(context, nav) ||
            start(context, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$lat,$lon&travelmode=driving"))
    }

    fun dial(context: Context, phone: String): Boolean =
        start(context, Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + phone.filter { it.isDigit() || it == '+' })))

    private fun start(context: Context, uri: Uri): Boolean = start(context, Intent(Intent.ACTION_VIEW, uri))

    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }
}
