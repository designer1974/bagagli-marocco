package main

import (
	"context"
	"crypto/pbkdf2"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"database/sql"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	pbkdf2Iterations = 600_000 // raccomandazione OWASP per PBKDF2-SHA256
	tokenLifetime    = 30 * 24 * time.Hour
)

// hashPassword salva la password in modo che non si possa mai rileggere.
func hashPassword(password string) (string, error) {
	salt := make([]byte, 16)
	if _, err := rand.Read(salt); err != nil {
		return "", err
	}
	key, err := pbkdf2.Key(sha256.New, password, salt, pbkdf2Iterations, 32)
	if err != nil {
		return "", err
	}
	enc := base64.RawStdEncoding
	return fmt.Sprintf("pbkdf2-sha256$%d$%s$%s", pbkdf2Iterations, enc.EncodeToString(salt), enc.EncodeToString(key)), nil
}

func checkPassword(stored, password string) bool {
	parts := strings.Split(stored, "$")
	if len(parts) != 4 || parts[0] != "pbkdf2-sha256" {
		return false
	}
	iter, err := strconv.Atoi(parts[1])
	if err != nil || iter < 1 {
		return false
	}
	enc := base64.RawStdEncoding
	salt, err1 := enc.DecodeString(parts[2])
	want, err2 := enc.DecodeString(parts[3])
	if err1 != nil || err2 != nil {
		return false
	}
	got, err := pbkdf2.Key(sha256.New, password, salt, iter, len(want))
	if err != nil {
		return false
	}
	return subtle.ConstantTimeCompare(got, want) == 1
}

func newToken() (plain, hash string, err error) {
	b := make([]byte, 32)
	if _, err = rand.Read(b); err != nil {
		return "", "", err
	}
	plain = base64.RawURLEncoding.EncodeToString(b)
	return plain, hashToken(plain), nil
}

func hashToken(plain string) string {
	sum := sha256.Sum256([]byte(plain))
	return hex.EncodeToString(sum[:])
}

type User struct {
	ID       int64  `json:"id"`
	Username string `json:"username"`
	Name     string `json:"name"`
	Role     string `json:"role"`
}

type ctxKey struct{}

func userFrom(ctx context.Context) *User {
	u, _ := ctx.Value(ctxKey{}).(*User)
	return u
}

// requireRole accetta solo richieste con un token valido del ruolo indicato.
func (s *Server) requireRole(role string, next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		h := r.Header.Get("Authorization")
		plain, ok := strings.CutPrefix(h, "Bearer ")
		if !ok || plain == "" {
			writeError(w, http.StatusUnauthorized, "accesso richiesto")
			return
		}
		var u User
		var active int
		err := s.db.QueryRowContext(r.Context(), `
			SELECT u.id, u.username, u.name, u.role, u.active
			FROM tokens t JOIN users u ON u.id = t.user_id
			WHERE t.hash = ? AND t.expires_at > ?`, hashToken(plain), s.now().Unix()).
			Scan(&u.ID, &u.Username, &u.Name, &u.Role, &active)
		if errors.Is(err, sql.ErrNoRows) || (err == nil && active == 0) {
			writeError(w, http.StatusUnauthorized, "sessione scaduta, accedi di nuovo")
			return
		}
		if err != nil {
			s.internalError(w, err)
			return
		}
		if u.Role != role {
			writeError(w, http.StatusForbidden, "permesso negato")
			return
		}
		next(w, r.WithContext(context.WithValue(r.Context(), ctxKey{}, &u)))
	}
}

// loginLimiter blocca i tentativi ripetuti di indovinare una password.
type loginLimiter struct {
	mu       sync.Mutex
	failures map[string][]time.Time
	max      int
	window   time.Duration
}

func newLoginLimiter() *loginLimiter {
	return &loginLimiter{failures: map[string][]time.Time{}, max: 5, window: 15 * time.Minute}
}

func (l *loginLimiter) blocked(key string, now time.Time) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	recent := l.failures[key][:0]
	for _, t := range l.failures[key] {
		if now.Sub(t) < l.window {
			recent = append(recent, t)
		}
	}
	if len(recent) == 0 {
		delete(l.failures, key)
		return false
	}
	l.failures[key] = recent
	return len(recent) >= l.max
}

func (l *loginLimiter) fail(key string, now time.Time) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.failures[key] = append(l.failures[key], now)
}

func (l *loginLimiter) reset(key string) {
	l.mu.Lock()
	defer l.mu.Unlock()
	delete(l.failures, key)
}

func (s *Server) handleLogin(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Username string `json:"username"`
		Password string `json:"password"`
	}
	if !readJSON(w, r, &req) {
		return
	}
	req.Username = strings.ToLower(strings.TrimSpace(req.Username))
	key := clientIP(r) + "|" + req.Username
	now := s.now()
	if s.limiter.blocked(key, now) {
		writeError(w, http.StatusTooManyRequests, "troppi tentativi, riprova tra 15 minuti")
		return
	}

	var u User
	var hash string
	var active int
	err := s.db.QueryRowContext(r.Context(),
		`SELECT id, username, name, role, password_hash, active FROM users WHERE username = ?`, req.Username).
		Scan(&u.ID, &u.Username, &u.Name, &u.Role, &hash, &active)
	if err != nil && !errors.Is(err, sql.ErrNoRows) {
		s.internalError(w, err)
		return
	}
	if err != nil || active == 0 || !checkPassword(hash, req.Password) {
		s.limiter.fail(key, now)
		writeError(w, http.StatusUnauthorized, "nome utente o password errati")
		return
	}
	s.limiter.reset(key)

	plain, th, err := newToken()
	if err != nil {
		s.internalError(w, err)
		return
	}
	if _, err := s.db.ExecContext(r.Context(),
		`INSERT INTO tokens (hash, user_id, created_at, expires_at) VALUES (?, ?, ?, ?)`,
		th, u.ID, now.Unix(), now.Add(tokenLifetime).Unix()); err != nil {
		s.internalError(w, err)
		return
	}
	s.logEvent(r.Context(), u.ID, "login", u.Username, "")
	writeJSON(w, http.StatusOK, map[string]any{"token": plain, "user": u})
}

func (s *Server) handleLogout(w http.ResponseWriter, r *http.Request) {
	plain, _ := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer ")
	if _, err := s.db.ExecContext(r.Context(), `DELETE FROM tokens WHERE hash = ?`, hashToken(plain)); err != nil {
		s.internalError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func createUser(db *sql.DB, username, name, role, password string, now time.Time) (int64, error) {
	if len(password) < 8 {
		return 0, errors.New("la password deve avere almeno 8 caratteri")
	}
	h, err := hashPassword(password)
	if err != nil {
		return 0, err
	}
	res, err := db.Exec(`INSERT INTO users (username, name, role, password_hash, created_at) VALUES (?, ?, ?, ?, ?)`,
		strings.ToLower(strings.TrimSpace(username)), name, role, h, now.Unix())
	if err != nil {
		return 0, err
	}
	return res.LastInsertId()
}
