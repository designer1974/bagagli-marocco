package main

import (
	"encoding/json"
	"errors"
	"log/slog"
	"net"
	"net/http"
)

const maxBodyBytes = 8 << 20 // 8 MB: abbastanza per molte firme, non per abusi

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func writeError(w http.ResponseWriter, status int, msg string) {
	writeJSON(w, status, map[string]string{"error": msg})
}

func (s *Server) internalError(w http.ResponseWriter, err error) {
	slog.Error("errore interno", "err", err)
	writeError(w, http.StatusInternalServerError, "errore del server, riprova")
}

func readJSON(w http.ResponseWriter, r *http.Request, v any) bool {
	r.Body = http.MaxBytesReader(w, r.Body, maxBodyBytes)
	dec := json.NewDecoder(r.Body)
	dec.DisallowUnknownFields()
	if err := dec.Decode(v); err != nil {
		var tooBig *http.MaxBytesError
		if errors.As(err, &tooBig) {
			writeError(w, http.StatusRequestEntityTooLarge, "richiesta troppo grande")
		} else {
			writeError(w, http.StatusBadRequest, "dati non validi: "+err.Error())
		}
		return false
	}
	return true
}

// clientIP usa l'indirizzo della connessione. Dietro il proxy HTTPS (Caddy)
// si fida di X-Real-IP solo se la connessione arriva dalla macchina stessa.
func clientIP(r *http.Request) string {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		host = r.RemoteAddr
	}
	if ip := net.ParseIP(host); ip != nil && (ip.IsLoopback() || ip.IsPrivate()) {
		if real := r.Header.Get("X-Real-IP"); real != "" {
			return real
		}
	}
	return host
}

// securityHeaders aggiunge le intestazioni di sicurezza a ogni risposta.
func securityHeaders(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		h := w.Header()
		h.Set("X-Content-Type-Options", "nosniff")
		h.Set("X-Frame-Options", "DENY")
		h.Set("Referrer-Policy", "no-referrer")
		h.Set("Cache-Control", "no-store")
		next.ServeHTTP(w, r)
	})
}

// recoverer impedisce che un errore imprevisto spenga il server.
func recoverer(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		defer func() {
			if v := recover(); v != nil {
				slog.Error("panic", "value", v, "path", r.URL.Path)
				writeError(w, http.StatusInternalServerError, "errore del server, riprova")
			}
		}()
		next.ServeHTTP(w, r)
	})
}
