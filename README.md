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
- **Server:** Go + SQLite, HTTPS con Caddy (vedi `server/README.md`)
- **Mappe:** Google Maps SDK + Routes API; Waze tramite link di apertura
- **Stabilità:** Firebase Crashlytics, test automatici, rilascio graduale
- **Lingue:** italiano, francese, spagnolo, arabo (da destra a sinistra)

## Cartelle

- `app/` — app Android dell'autista (Kotlin + Jetpack Compose)
- `server/` — server in Go: login, viaggi, bagagli, consegne, tracciamento
- `mockup/autista.html` — prototipo interattivo delle schermate dell'autista (apri il file nel browser)

## App Autista: cosa fa già

- **Viaggio:** traghetto, lista fermate in Marocco, avanzamento consegne
- **Scansiona:** legge il QR del bagaglio con la fotocamera (funziona senza internet), oppure codice scritto a mano; avvisa se il bagaglio è per un'altra fermata; segnalazione danni
- **Consegna:** indirizzo, apertura con un tocco di Google Maps o Waze, chiamata al destinatario
- **Firma:** codice a 4 cifre del destinatario + firma col dito, salvata come immagine
- **Offline-first:** tutto è salvato nel database del telefono e inviato al server quando c'è rete (il server è il prossimo passo)
- **Lingue:** italiano, francese, arabo (segue la lingua del telefono)

All'apertura l'autista accede con l'indirizzo del server, nome utente e password. Il viaggio arriva dal server; scansioni e firme tornano al server da sole quando c'è rete.

Senza server si può premere **Prova senza server**: 5 fermate di esempio tra Tanger e Casablanca, codici dei destinatari 1234, 5521, 4719, 8830, 2468.

## Come provare l'app sul telefono

1. Su GitHub apri la scheda **Actions** → l'ultima esecuzione di **Build APK** con la spunta verde
2. In fondo, sotto **Artifacts**, scarica **bagagli-autista-debug** (è uno zip)
3. Estrai il file `.apk` e aprilo sul telefono Android (consenti "installa app sconosciute")

Per lavorarci al computer: apri la cartella con **Android Studio** e premi ▶.

## Sicurezza

Questo repository è **pubblico**: non caricare mai qui chiavi API, password o dati reali dei clienti.
