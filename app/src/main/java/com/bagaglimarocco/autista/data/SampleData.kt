package com.bagaglimarocco.autista.data

/** Dati di esempio finché non c'è il server. Nessun dato reale. */
object SampleData {
    val trip = TripEntity(
        vehiclePlate = "FR-482-KL",
        route = "Milano → Algeciras → Marocco",
        ferryRoute = "Algeciras → Tanger Med",
        boardingBy = "12:30",
        departure = "14:00",
    )

    val stops = listOf(
        StopEntity(1, 1, "Agenzia Tanger Centre", "Bd Mohammed V 12", "Tanger", 35.7767, -5.8039, "+212 539 000 001", StopKind.AGENCY, "1234"),
        StopEntity(2, 2, "Fam. El Amrani", "Rue Al Jazair 8", "Tétouan", 35.5785, -5.3684, "+212 661 000 002", StopKind.HOME, "5521"),
        StopEntity(3, 3, "Agenzia Fès Médina", "Rue Talaa Kebira 41", "Fès", 34.0631, -4.9773, "+212 535 000 003", StopKind.AGENCY, "4719"),
        StopEntity(4, 4, "Sig.ra Benali", "Av. des FAR 22", "Meknès", 33.8935, -5.5473, "+212 662 000 004", StopKind.HOME, "8830"),
        StopEntity(5, 5, "Agenzia Casablanca Maârif", "Rue Abou Al Waqt 5", "Casablanca", 33.5822, -7.6327, "+212 522 000 005", StopKind.AGENCY, "2468"),
    )

    val bags = buildList {
        var n = 400
        val items = listOf(
            "Valigia blu" to 23.5, "Valigia grigia" to 19.0, "Borsone nero" to 18.0,
            "Scatola cartone" to 30.0, "Valigia rossa" to 21.0, "Sacco tela" to 15.5,
        )
        val perStop = mapOf(1L to 4, 2L to 3, 3L to 5, 4L to 2, 5L to 4)
        perStop.forEach { (stopId, count) ->
            repeat(count) { i ->
                val (desc, kg) = items[(n + i) % items.size]
                add(BagEntity(code = "BM-26-%04d".format(n), stopId = stopId, description = desc, weightKg = kg))
                n++
            }
        }
    }
}
