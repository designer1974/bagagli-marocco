package main

import (
	"database/sql"
	"fmt"

	_ "github.com/mattn/go-sqlite3"
)

// openDB apre il database SQLite in modalità WAL: letture veloci, scritture sicure
// anche se il server si spegne all'improvviso.
func openDB(path string) (*sql.DB, error) {
	dsn := fmt.Sprintf("file:%s?_journal_mode=WAL&_foreign_keys=on&_busy_timeout=5000&_synchronous=NORMAL", path)
	db, err := sql.Open("sqlite3", dsn)
	if err != nil {
		return nil, err
	}
	// Una sola connessione in scrittura evita conflitti "database is locked".
	db.SetMaxOpenConns(1)
	if err := db.Ping(); err != nil {
		return nil, err
	}
	if err := migrate(db); err != nil {
		return nil, fmt.Errorf("migrazione database: %w", err)
	}
	return db, nil
}

var migrations = []string{
	// 1: struttura iniziale
	`
CREATE TABLE users (
	id            INTEGER PRIMARY KEY,
	username      TEXT NOT NULL UNIQUE,
	name          TEXT NOT NULL,
	role          TEXT NOT NULL CHECK (role IN ('admin','driver')),
	password_hash TEXT NOT NULL,
	active        INTEGER NOT NULL DEFAULT 1,
	created_at    INTEGER NOT NULL
);
CREATE TABLE tokens (
	hash       TEXT PRIMARY KEY,
	user_id    INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
	created_at INTEGER NOT NULL,
	expires_at INTEGER NOT NULL
);
CREATE TABLE trips (
	id            INTEGER PRIMARY KEY,
	vehicle_plate TEXT NOT NULL,
	route         TEXT NOT NULL,
	ferry_route   TEXT NOT NULL DEFAULT '',
	boarding_by   TEXT NOT NULL DEFAULT '',
	departure     TEXT NOT NULL DEFAULT '',
	driver_id     INTEGER REFERENCES users(id),
	status        TEXT NOT NULL DEFAULT 'open' CHECK (status IN ('open','closed')),
	created_at    INTEGER NOT NULL
);
CREATE TABLE stops (
	id             INTEGER PRIMARY KEY,
	trip_id        INTEGER NOT NULL REFERENCES trips(id) ON DELETE CASCADE,
	position       INTEGER NOT NULL,
	name           TEXT NOT NULL,
	address        TEXT NOT NULL,
	city           TEXT NOT NULL,
	latitude       REAL NOT NULL,
	longitude      REAL NOT NULL,
	phone          TEXT NOT NULL DEFAULT '',
	kind           TEXT NOT NULL CHECK (kind IN ('HOME','AGENCY')),
	recipient_code TEXT NOT NULL,
	status         TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','DELIVERED')),
	delivered_at   INTEGER,
	code_ok        INTEGER,
	signature_file TEXT,
	updated_at     INTEGER NOT NULL
);
CREATE INDEX stops_trip ON stops(trip_id);
CREATE TABLE bags (
	code        TEXT PRIMARY KEY,
	trip_id     INTEGER NOT NULL REFERENCES trips(id) ON DELETE CASCADE,
	stop_id     INTEGER NOT NULL REFERENCES stops(id) ON DELETE CASCADE,
	description TEXT NOT NULL,
	weight_kg   REAL NOT NULL,
	status      TEXT NOT NULL DEFAULT 'LOADED' CHECK (status IN ('LOADED','UNLOADED','DAMAGED')),
	scanned_at  INTEGER,
	updated_at  INTEGER NOT NULL
);
CREATE INDEX bags_stop ON bags(stop_id);
CREATE TABLE events (
	id      INTEGER PRIMARY KEY,
	user_id INTEGER,
	kind    TEXT NOT NULL,
	ref     TEXT NOT NULL,
	detail  TEXT NOT NULL DEFAULT '',
	at      INTEGER NOT NULL
);
`,
}

func migrate(db *sql.DB) error {
	var version int
	if err := db.QueryRow(`PRAGMA user_version`).Scan(&version); err != nil {
		return err
	}
	for i := version; i < len(migrations); i++ {
		tx, err := db.Begin()
		if err != nil {
			return err
		}
		if _, err := tx.Exec(migrations[i]); err != nil {
			tx.Rollback()
			return fmt.Errorf("versione %d: %w", i+1, err)
		}
		if _, err := tx.Exec(fmt.Sprintf(`PRAGMA user_version = %d`, i+1)); err != nil {
			tx.Rollback()
			return err
		}
		if err := tx.Commit(); err != nil {
			return err
		}
	}
	return nil
}
