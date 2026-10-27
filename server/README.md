# Server Bagagli Marocco

Server in **Go** con database **SQLite** (un solo file, backup facile).
Gestisce autisti, viaggi, fermate, bagagli, consegne con firma e tracciamento pubblico.

## Sicurezza

- Password salvate con PBKDF2-SHA256 (600.000 iterazioni), mai in chiaro
- Accesso con token che scade dopo 30 giorni; logout lo annulla subito
- Blocco dopo 5 password sbagliate (15 minuti)
- L'autista vede solo il **suo** viaggio e non riceve i codici dei destinatari in chiaro
- HTTPS automatico con Caddy
- Tracciamento pubblico: solo stato e città, nessun nome o telefono

## Provarlo sul computer

```bash
cd server
ADMIN_USER=admin ADMIN_PASSWORD=una-password-lunga go run . -demo
```

`-demo` crea l'autista `autista` / `autista2026` e un viaggio di prova con 5 fermate e 18 bagagli.
I codici dei destinatari sono stampati all'avvio.

Test: `go test ./...`

## Metterlo online (VPS con Docker)

```bash
cp .env.example .env      # poi modifica DOMAIN e ADMIN_PASSWORD
docker compose up -d
```

Il dominio deve puntare all'indirizzo IP del server. Backup: copia la cartella `data/`.

## API principali

| Metodo | Percorso | Chi |
|---|---|---|
| POST | `/api/v1/login` | tutti |
| GET | `/api/v1/driver/trip` | autista |
| POST | `/api/v1/driver/sync` | autista |
| POST | `/api/v1/admin/users` | gestione |
| POST | `/api/v1/admin/trips` | gestione |
| GET | `/api/v1/admin/trips/{id}` | gestione |
| POST | `/api/v1/admin/trips/{id}/stops` | gestione (manda SMS col codice) |
| POST | `/api/v1/admin/trips/{id}/bags` | gestione (crea codice QR) |
| GET | `/api/v1/admin/stops/{id}/signature` | gestione |
| GET | `/api/v1/track/{codice}` | pubblico |

L'invio SMS è per ora simulato (`notify.go`): va collegato a un fornitore SMS o WhatsApp.
