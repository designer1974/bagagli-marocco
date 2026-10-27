# Bagagli Marocco

App Android per il trasporto di bagagli dall'Europa al Marocco.
L'autista carica i bagagli in Europa, prende il traghetto (Genova, Livorno, Sète, Barcellona, Almería, Algeciras…) e li consegna a casa dei destinatari o in agenzia per il ritiro.

## Parti del sistema

| Parte | Chi la usa | Cosa fa |
|---|---|---|
| App Autista (Android) | Autista | Lista consegne, scansione QR, navigazione Waze / Google Maps, firma e foto di consegna, funziona offline |
| App Cliente (Android) | Mittente / destinatario | Registra il bagaglio, etichetta QR, segue il furgone, codice di ritiro |
| Gestione (web) | Titolare / agenzie | Viaggi, porti e traghetti, prezzi, pagamenti |

## Tecnologie

- **Android:** Kotlin + Jetpack Compose, Material 3, database locale Room, sincronizzazione con WorkManager (offline-first)
- **Server:** Go oppure Kotlin (Ktor), PostgreSQL + PostGIS
- **Mappe:** Google Maps SDK + Routes API; Waze tramite link di apertura
- **Stabilità:** Firebase Crashlytics, test automatici, rilascio graduale
- **Lingue:** italiano, francese, spagnolo, arabo (da destra a sinistra)

## Cartelle

- `mockup/autista.html` — prototipo interattivo delle schermate dell'autista (apri il file nel browser)

## Sicurezza

Questo repository è **pubblico**: non caricare mai qui chiavi API, password o dati reali dei clienti.
