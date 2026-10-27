package main

import (
	"crypto/rand"
	"database/sql"
	"errors"
	"fmt"
	"math/big"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
)

func pathID(r *http.Request, name string) (int64, bool) {
	id, err := strconv.ParseInt(r.PathValue(name), 10, 64)
	return id, err == nil && id > 0
}

func randomDigits(n int) (string, error) {
	var b strings.Builder
	for i := 0; i < n; i++ {
		d, err := rand.Int(rand.Reader, big.NewInt(10))
		if err != nil {
			return "", err
		}
		b.WriteByte(byte('0' + d.Int64()))
	}
	return b.String(), nil
}

// POST /api/v1/admin/users
func (s *Server) handleCreateUser(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Username string `json:"username"`
		Name     string `json:"name"`
		Role     string `json:"role"`
		Password string `json:"password"`
	}
	if !readJSON(w, r, &req) {
		return
	}
	if req.Role != "admin" && req.Role != "driver" {
		writeError(w, http.StatusBadRequest, "ruolo deve essere admin o driver")
		return
	}
	if strings.TrimSpace(req.Username) == "" || strings.TrimSpace(req.Name) == "" {
		writeError(w, http.StatusBadRequest, "nome utente e nome sono obbligatori")
		return
	}
	id, err := createUser(s.db, req.Username, req.Name, req.Role, req.Password, s.now())
	if err != nil {
		if strings.Contains(err.Error(), "UNIQUE") {
			writeError(w, http.StatusConflict, "nome utente già usato")
		} else {
			writeError(w, http.StatusBadRequest, err.Error())
		}
		return
	}
	writeJSON(w, http.StatusCreated, map[string]any{"id": id})
}

// GET /api/v1/admin/users
func (s *Server) handleListUsers(w http.ResponseWriter, r *http.Request) {
	rows, err := s.db.QueryContext(r.Context(), `SELECT id, username, name, role FROM users WHERE active = 1 ORDER BY name`)
	if err != nil {
		s.internalError(w, err)
		return
	}
	defer rows.Close()
	users := []User{}
	for rows.Next() {
		var u User
		if err := rows.Scan(&u.ID, &u.Username, &u.Name, &u.Role); err != nil {
			s.internalError(w, err)
			return
		}
		users = append(users, u)
	}
	writeJSON(w, http.StatusOK, users)
}

// POST /api/v1/admin/trips
func (s *Server) handleCreateTrip(w http.ResponseWriter, r *http.Request) {
	var t TripDTO
	if !readJSON(w, r, &t) {
		return
	}
	if strings.TrimSpace(t.VehiclePlate) == "" || strings.TrimSpace(t.Route) == "" {
		writeError(w, http.StatusBadRequest, "targa e percorso sono obbligatori")
		return
	}
	if t.DriverID != nil {
		var role string
		if err := s.db.QueryRowContext(r.Context(), `SELECT role FROM users WHERE id = ?`, *t.DriverID).Scan(&role); err != nil || role != "driver" {
			writeError(w, http.StatusBadRequest, "autista non trovato")
			return
		}
	}
	res, err := s.db.ExecContext(r.Context(), `INSERT INTO trips (vehicle_plate, route, ferry_route, boarding_by, departure, driver_id, created_at)
		VALUES (?, ?, ?, ?, ?, ?, ?)`, t.VehiclePlate, t.Route, t.FerryRoute, t.BoardingBy, t.Departure, t.DriverID, s.now().Unix())
	if err != nil {
		s.internalError(w, err)
		return
	}
	id, _ := res.LastInsertId()
	writeJSON(w, http.StatusCreated, map[string]any{"id": id})
}

// GET /api/v1/admin/trips
func (s *Server) handleListTrips(w http.ResponseWriter, r *http.Request) {
	rows, err := s.db.QueryContext(r.Context(), `SELECT id, vehicle_plate, route, ferry_route, boarding_by, departure, driver_id, status
		FROM trips ORDER BY id DESC LIMIT 200`)
	if err != nil {
		s.internalError(w, err)
		return
	}
	defer rows.Close()
	trips := []TripDTO{}
	for rows.Next() {
		var t TripDTO
		var driver sql.NullInt64
		if err := rows.Scan(&t.ID, &t.VehiclePlate, &t.Route, &t.FerryRoute, &t.BoardingBy, &t.Departure, &driver, &t.Status); err != nil {
			s.internalError(w, err)
			return
		}
		if driver.Valid {
			t.DriverID = &driver.Int64
		}
		trips = append(trips, t)
	}
	writeJSON(w, http.StatusOK, trips)
}

// GET /api/v1/admin/trips/{id}
func (s *Server) handleGetTrip(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(r, "id")
	if !ok {
		writeError(w, http.StatusBadRequest, "id non valido")
		return
	}
	d, err := s.loadTripDetail(r.Context(), id, true)
	if errors.Is(err, sql.ErrNoRows) {
		writeError(w, http.StatusNotFound, "viaggio non trovato")
		return
	}
	if err != nil {
		s.internalError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, d)
}

// POST /api/v1/admin/trips/{id}/close
func (s *Server) handleCloseTrip(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(r, "id")
	if !ok {
		writeError(w, http.StatusBadRequest, "id non valido")
		return
	}
	if _, err := s.db.ExecContext(r.Context(), `UPDATE trips SET status = 'closed' WHERE id = ?`, id); err != nil {
		s.internalError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// POST /api/v1/admin/trips/{id}/stops — crea la fermata e manda l'SMS con il codice al destinatario.
func (s *Server) handleAddStop(w http.ResponseWriter, r *http.Request) {
	tripID, ok := pathID(r, "id")
	if !ok {
		writeError(w, http.StatusBadRequest, "id non valido")
		return
	}
	var st StopDTO
	if !readJSON(w, r, &st) {
		return
	}
	if st.Kind != "HOME" && st.Kind != "AGENCY" {
		writeError(w, http.StatusBadRequest, "kind deve essere HOME o AGENCY")
		return
	}
	if strings.TrimSpace(st.Name) == "" || strings.TrimSpace(st.Address) == "" || strings.TrimSpace(st.City) == "" {
		writeError(w, http.StatusBadRequest, "nome, indirizzo e città sono obbligatori")
		return
	}
	if st.Latitude < -90 || st.Latitude > 90 || st.Longitude < -180 || st.Longitude > 180 {
		writeError(w, http.StatusBadRequest, "coordinate non valide")
		return
	}
	code, err := randomDigits(4)
	if err != nil {
		s.internalError(w, err)
		return
	}
	if st.Position == 0 {
		_ = s.db.QueryRowContext(r.Context(), `SELECT COALESCE(MAX(position), 0) + 1 FROM stops WHERE trip_id = ?`, tripID).Scan(&st.Position)
	}
	res, err := s.db.ExecContext(r.Context(), `INSERT INTO stops (trip_id, position, name, address, city, latitude, longitude, phone, kind,
		recipient_code, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
		tripID, st.Position, st.Name, st.Address, st.City, st.Latitude, st.Longitude, st.Phone, st.Kind, code, s.now().UnixMilli())
	if err != nil {
		if strings.Contains(err.Error(), "FOREIGN KEY") {
			writeError(w, http.StatusNotFound, "viaggio non trovato")
			return
		}
		s.internalError(w, err)
		return
	}
	id, _ := res.LastInsertId()
	if st.Phone != "" {
		if err := s.notifier.SendRecipientCode(r.Context(), st.Phone, st.Name, code); err != nil {
			s.logEvent(r.Context(), userFrom(r.Context()).ID, "sms_failed", strconv.FormatInt(id, 10), err.Error())
		}
	}
	writeJSON(w, http.StatusCreated, map[string]any{"id": id, "recipientCode": code})
}

// POST /api/v1/admin/trips/{id}/bags — registra un bagaglio e ne crea il codice per l'etichetta QR.
func (s *Server) handleAddBag(w http.ResponseWriter, r *http.Request) {
	tripID, ok := pathID(r, "id")
	if !ok {
		writeError(w, http.StatusBadRequest, "id non valido")
		return
	}
	var b BagDTO
	if !readJSON(w, r, &b) {
		return
	}
	if b.WeightKg <= 0 || b.WeightKg > 200 || strings.TrimSpace(b.Description) == "" {
		writeError(w, http.StatusBadRequest, "descrizione e peso (0-200 kg) sono obbligatori")
		return
	}
	var stopTrip int64
	if err := s.db.QueryRowContext(r.Context(), `SELECT trip_id FROM stops WHERE id = ?`, b.StopID).Scan(&stopTrip); err != nil || stopTrip != tripID {
		writeError(w, http.StatusBadRequest, "la fermata non appartiene a questo viaggio")
		return
	}
	if b.Code == "" {
		yy := s.now().Format("06")
		var next int
		if err := s.db.QueryRowContext(r.Context(), `SELECT COALESCE(MAX(CAST(substr(code, 7) AS INTEGER)), 0) + 1
			FROM bags WHERE code LIKE ?`, "BM-"+yy+"-%").Scan(&next); err != nil {
			s.internalError(w, err)
			return
		}
		b.Code = fmt.Sprintf("BM-%s-%04d", yy, next)
	}
	b.Code = strings.ToUpper(strings.TrimSpace(b.Code))
	if _, err := s.db.ExecContext(r.Context(), `INSERT INTO bags (code, trip_id, stop_id, description, weight_kg, updated_at)
		VALUES (?, ?, ?, ?, ?, ?)`, b.Code, tripID, b.StopID, b.Description, b.WeightKg, s.now().UnixMilli()); err != nil {
		if strings.Contains(err.Error(), "UNIQUE") {
			writeError(w, http.StatusConflict, "codice bagaglio già esistente")
			return
		}
		s.internalError(w, err)
		return
	}
	writeJSON(w, http.StatusCreated, map[string]any{"code": b.Code})
}

// GET /api/v1/admin/stops/{id}/signature — immagine della firma di consegna.
func (s *Server) handleSignature(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(r, "id")
	if !ok {
		writeError(w, http.StatusBadRequest, "id non valido")
		return
	}
	var file sql.NullString
	if err := s.db.QueryRowContext(r.Context(), `SELECT signature_file FROM stops WHERE id = ?`, id).Scan(&file); err != nil || !file.Valid {
		writeError(w, http.StatusNotFound, "firma non trovata")
		return
	}
	data, err := os.ReadFile(filepath.Join(s.dataDir, "firme", filepath.Base(file.String)))
	if err != nil {
		writeError(w, http.StatusNotFound, "firma non trovata")
		return
	}
	w.Header().Set("Content-Type", "image/png")
	w.Write(data)
}

// GET /api/v1/track/{code} — tracciamento pubblico: solo stato e città, nessun dato personale.
func (s *Server) handleTrack(w http.ResponseWriter, r *http.Request) {
	code := strings.ToUpper(strings.TrimSpace(r.PathValue("code")))
	var status, city, stopStatus string
	var updated int64
	err := s.db.QueryRowContext(r.Context(), `SELECT b.status, s.city, s.status, b.updated_at
		FROM bags b JOIN stops s ON s.id = b.stop_id WHERE b.code = ?`, code).Scan(&status, &city, &stopStatus, &updated)
	if err != nil {
		writeError(w, http.StatusNotFound, "codice non trovato")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"code": code, "status": status, "city": city, "delivered": stopStatus == "DELIVERED", "updatedAt": updated,
	})
}
