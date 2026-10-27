package com.bagaglimarocco.autista.data

import java.security.MessageDigest

object Codes {
    /** Uguale a codeHash() del server: sha256("idFermata:codice") in esadecimale. */
    fun recipientCodeHash(stopId: Long, code: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("$stopId:$code".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun normalizeBag(code: String): String = code.trim().uppercase().replace(' ', '-')
}
