// Server per Bagagli Marocco: viaggi, bagagli, consegne e login degli autisti.
package main

import (
	"context"
	"database/sql"
	"errors"
	"flag"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"
	"time"
)

type Server struct {
	db       *sql.DB
	dataDir  string
	notifier Notifier
	limiter  *loginLimiter
	now      func() time.Time
}

func (s *Server) routes() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, r *http.Request) {
		if err := s.db.PingContext(r.Context()); err != nil {
			writeError(w, http.StatusServiceUnavailable, "database non disponibile")
			return
		}
		writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
	})

	mux.HandleFunc("POST /api/v1/login", s.handleLogin)
	mux.HandleFunc("POST /api/v1/logout", s.handleLogout)
	mux.HandleFunc("GET /api/v1/track/{code}", s.handleTrack)

	driver := func(h http.HandlerFunc) http.HandlerFunc { return s.requireRole("driver", h) }
	mux.HandleFunc("GET /api/v1/driver/trip", driver(s.handleDriverTrip))
	mux.HandleFunc("POST /api/v1/driver/sync", driver(s.handleDriverSync))

	admin := func(h http.HandlerFunc) http.HandlerFunc { return s.requireRole("admin", h) }
	mux.HandleFunc("GET /api/v1/admin/users", admin(s.handleListUsers))
	mux.HandleFunc("POST /api/v1/admin/users", admin(s.handleCreateUser))
	mux.HandleFunc("GET /api/v1/admin/trips", admin(s.handleListTrips))
	mux.HandleFunc("POST /api/v1/admin/trips", admin(s.handleCreateTrip))
	mux.HandleFunc("GET /api/v1/admin/trips/{id}", admin(s.handleGetTrip))
	mux.HandleFunc("POST /api/v1/admin/trips/{id}/close", admin(s.handleCloseTrip))
	mux.HandleFunc("POST /api/v1/admin/trips/{id}/stops", admin(s.handleAddStop))
	mux.HandleFunc("POST /api/v1/admin/trips/{id}/bags", admin(s.handleAddBag))
	mux.HandleFunc("GET /api/v1/admin/stops/{id}/signature", admin(s.handleSignature))

	return recoverer(securityHeaders(mux))
}

func env(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

func main() {
	demo := flag.Bool("demo", false, "crea un autista e un viaggio di prova (solo per test)")
	flag.Parse()

	slog.SetDefault(slog.New(slog.NewJSONHandler(os.Stdout, nil)))
	addr := env("ADDR", ":8080")
	dataDir := env("DATA_DIR", "./data")
	if err := os.MkdirAll(dataDir, 0o750); err != nil {
		slog.Error("cartella dati", "err", err)
		os.Exit(1)
	}
	db, err := openDB(filepath.Join(dataDir, "bagagli.db"))
	if err != nil {
		slog.Error("database", "err", err)
		os.Exit(1)
	}
	defer db.Close()

	s := &Server{db: db, dataDir: dataDir, notifier: logNotifier{}, limiter: newLoginLimiter(), now: time.Now}

	if err := bootstrapAdmin(s); err != nil {
		slog.Error("primo amministratore", "err", err)
		os.Exit(1)
	}
	if *demo {
		if err := seedDemo(s); err != nil {
			slog.Error("dati demo", "err", err)
			os.Exit(1)
		}
	}

	srv := &http.Server{
		Addr:              addr,
		Handler:           s.routes(),
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       60 * time.Second,
		WriteTimeout:      60 * time.Second,
		IdleTimeout:       120 * time.Second,
	}

	go func() {
		slog.Info("server avviato", "addr", addr)
		if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			slog.Error("server", "err", err)
			os.Exit(1)
		}
	}()

	// Spegnimento ordinato: finisce le richieste in corso prima di chiudere.
	stop := make(chan os.Signal, 1)
	signal.Notify(stop, syscall.SIGINT, syscall.SIGTERM)
	<-stop
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	_ = srv.Shutdown(ctx)
	slog.Info("server spento")
}

// bootstrapAdmin crea il primo amministratore da ADMIN_USER / ADMIN_PASSWORD
// solo se nel database non esiste ancora nessun utente.
func bootstrapAdmin(s *Server) error {
	var n int
	if err := s.db.QueryRow(`SELECT COUNT(*) FROM users`).Scan(&n); err != nil {
		return err
	}
	if n > 0 {
		return nil
	}
	user, pass := os.Getenv("ADMIN_USER"), os.Getenv("ADMIN_PASSWORD")
	if user == "" || pass == "" {
		slog.Warn("nessun utente: imposta ADMIN_USER e ADMIN_PASSWORD al primo avvio")
		return nil
	}
	_, err := createUser(s.db, user, "Amministratore", "admin", pass, s.now())
	if err == nil {
		slog.Info("amministratore creato", "username", user)
	}
	return err
}
