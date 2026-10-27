package main

import (
	"bytes"
	"context"
	"crypto/sha256"
	"crypto/subtle"
	"database/sql"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
)

type TripDTO struct {
	ID           int64  `json:"id"`
	VehiclePlate string `json:"vehiclePlate"`
	Route        string `json:"route"`
	FerryRoute   string `json:"ferryRoute"`
	BoardingBy   string `json:"boardingBy"`
	Departure    string `json:"departure"`
	DriverID     *int64 `json:"driverId,omitempty"`
	Status       string `json:"status"`
}

type StopDTO struct {
	ID                int64   `json:"id"`
	TripID            int64   `json:"tripId"`
	Position          int     `json:"position"`
	Name              string  `json:"name"`
	Address           string  `json:"address"`
	City              string  `json:"city"`
	Latitude          float64 `json:"latitude"`
	Longitude         float64 `json:"longitude"`
	Phone             string  `json:"phone"`
	Kind              string  `json:"kind"`
	RecipientCode     string  `json:"recipientCode,omitempty"` // solo per la gestione
	RecipientCodeHash string  `json:"recipientCodeHash"`       // per l'autista: verifica offline
	Status            string  `json:"status"`
	DeliveredAt       *int64  `json:"deliveredAt"`
	CodeOK            *bool   `json:"codeOk,omitempty"`
	HasSignature      bool    `json:"hasSignature"`
}

type BagDTO struct {
	Code        string  `json:"code"`
	TripID      int64   `json:"tripId"`
	StopID      int64   `json:"stopId"`
	Description string  `json:"description"`
	WeightKg    float64 `json:"weightKg"`
	Status      string  `json:"status"`
	ScannedAt   *int64  `json:"scannedAt"`
}

type TripDetail struct {
	Trip  TripDTO   `json:"trip"`
	Stops []StopDTO `json:"stops"`
	Bags  []BagDTO  `json:"bags"`
}

// codeHash permette all'app di verificare il codice del destinatario senza internet,
// senza che il codice sia leggibile nel telefono.
func codeHash(stopID int64, code string) string {
	sum := sha256.Sum256([]byte(strconv.FormatInt(stopID, 10) + ":" + code))
	return hex.EncodeToString(sum[:])
}

func (s *Server) loadTripDetail(ctx context.Context, tripID int64, forAdmin bool) (*TripDetail, error) {
	var d TripDetail
	t := &d.Trip
	var driver sql.NullInt64
	err := s.db.QueryRowContext(ctx, `SELECT id, vehicle_plate, route, ferry_route, boarding_by, departure, driver_id, status
		FROM trips WHERE id = ?`, tripID).
		Scan(&t.ID, &t.VehiclePlate, &t.Route, &t.FerryRoute, &t.BoardingBy, &t.Departure, &driver, &t.Status)
	if err != nil {
		return nil, err
	}
	if driver.Valid {
		t.DriverID = &driver.Int64
	}

	rows, err := s.db.QueryContext(ctx, `SELECT id, trip_id, position, name, address, city, latitude, longitude, phone, kind,
		recipient_code, status, delivered_at, code_ok, signature_file FROM stops WHERE trip_id = ? ORDER BY position, id`, tripID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	d.Stops = []StopDTO{}
	for rows.Next() {
		var st StopDTO
		var code string
		var delivered sql.NullInt64
		var codeOK sql.NullBool
		var sig sql.NullString
		if err := rows.Scan(&st.ID, &st.TripID, &st.Position, &st.Name, &st.Address, &st.City, &st.Latitude, &st.Longitude,
			&st.Phone, &st.Kind, &code, &st.Status, &delivered, &codeOK, &sig); err != nil {
			return nil, err
		}
		st.RecipientCodeHash = codeHash(st.ID, code)
		if forAdmin {
			st.RecipientCode = code
			if codeOK.Valid {
				st.CodeOK = &codeOK.Bool
			}
		}
		if delivered.Valid {
			st.DeliveredAt = &delivered.Int64
		}
		st.HasSignature = sig.Valid && sig.String != ""
		d.Stops = append(d.Stops, st)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}

	brows, err := s.db.QueryContext(ctx, `SELECT code, trip_id, stop_id, description, weight_kg, status, scanned_at
		FROM bags WHERE trip_id = ? ORDER BY code`, tripID)
	if err != nil {
		return nil, err
	}
	defer brows.Close()
	d.Bags = []BagDTO{}
	for brows.Next() {
		var b BagDTO
		var scanned sql.NullInt64
		if err := brows.Scan(&b.Code, &b.TripID, &b.StopID, &b.Description, &b.WeightKg, &b.Status, &scanned); err != nil {
			return nil, err
		}
		if scanned.Valid {
			b.ScannedAt = &scanned.Int64
		}
		d.Bags = append(d.Bags, b)
	}
	return &d, brows.Err()
}

// GET /api/v1/driver/trip — il viaggio aperto assegnato all'autista.
func (s *Server) handleDriverTrip(w http.ResponseWriter, r *http.Request) {
	u := userFrom(r.Context())
	var tripID int64
	err := s.db.QueryRowContext(r.Context(),
		`SELECT id FROM trips WHERE driver_id = ? AND status = 'open' ORDER BY id DESC LIMIT 1`, u.ID).Scan(&tripID)
	if errors.Is(err, sql.ErrNoRows) {
		writeError(w, http.StatusNotFound, "nessun viaggio assegnato")
		return
	}
	if err != nil {
		s.internalError(w, err)
		return
	}
	d, err := s.loadTripDetail(r.Context(), tripID, false)
	if err != nil {
		s.internalError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, d)
}

type SyncRequest struct {
	Bags []struct {
		Code      string `json:"code"`
		Status    string `json:"status"`
		ScannedAt int64  `json:"scannedAt"`
	} `json:"bags"`
	Deliveries []struct {
		StopID        int64  `json:"stopId"`
		DeliveredAt   int64  `json:"deliveredAt"`
		RecipientCode string `json:"recipientCode"`
		SignaturePng  string `json:"signaturePng"` // base64
	} `json:"deliveries"`
}

type Rejected struct {
	Ref    string `json:"ref"`
	Reason string `json:"reason"`
}

type SyncResponse struct {
	AcceptedBags  []string   `json:"acceptedBags"`
	AcceptedStops []int64    `json:"acceptedStops"`
	Rejected      []Rejected `json:"rejected"`
}

var pngMagic = []byte{0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'}

// POST /api/v1/driver/sync — riceve scansioni e consegne fatte offline.
// Si può ripetere senza problemi: inviare due volte gli stessi dati non cambia nulla.
func (s *Server) handleDriverSync(w http.ResponseWriter, r *http.Request) {
	u := userFrom(r.Context())
	var req SyncRequest
	if !readJSON(w, r, &req) {
		return
	}
	resp := SyncResponse{AcceptedBags: []string{}, AcceptedStops: []int64{}, Rejected: []Rejected{}}
	now := s.now().UnixMilli()
	ctx := r.Context()

	for _, b := range req.Bags {
		if b.Status != "UNLOADED" && b.Status != "DAMAGED" {
			resp.Rejected = append(resp.Rejected, Rejected{b.Code, "stato non valido"})
			continue
		}
		res, err := s.db.ExecContext(ctx, `
			UPDATE bags SET status = ?, scanned_at = ?, updated_at = ?
			WHERE code = ?
			  AND trip_id IN (SELECT id FROM trips WHERE driver_id = ? AND status = 'open')
			  AND (scanned_at IS NULL OR scanned_at <= ?)`,
			b.Status, b.ScannedAt, now, b.Code, u.ID, b.ScannedAt)
		if err != nil {
			s.internalError(w, err)
			return
		}
		if n, _ := res.RowsAffected(); n == 0 {
			// Già aggiornato con un dato più recente? Allora va bene lo stesso.
			var exists int
			err := s.db.QueryRowContext(ctx, `SELECT 1 FROM bags WHERE code = ?
				AND trip_id IN (SELECT id FROM trips WHERE driver_id = ? AND status = 'open')`, b.Code, u.ID).Scan(&exists)
			if err != nil {
				resp.Rejected = append(resp.Rejected, Rejected{b.Code, "bagaglio non trovato in questo viaggio"})
				continue
			}
		}
		s.logEvent(ctx, u.ID, "bag_"+b.Status, b.Code, "")
		resp.AcceptedBags = append(resp.AcceptedBags, b.Code)
	}

	for _, d := range req.Deliveries {
		ref := strconv.FormatInt(d.StopID, 10)
		var code, status string
		err := s.db.QueryRowContext(ctx, `SELECT recipient_code, status FROM stops
			WHERE id = ? AND trip_id IN (SELECT id FROM trips WHERE driver_id = ? AND status = 'open')`, d.StopID, u.ID).
			Scan(&code, &status)
		if err != nil {
			resp.Rejected = append(resp.Rejected, Rejected{ref, "fermata non trovata in questo viaggio"})
			continue
		}
		if status == "DELIVERED" {
			resp.AcceptedStops = append(resp.AcceptedStops, d.StopID) // già ricevuta
			continue
		}
		png, err := base64.StdEncoding.DecodeString(d.SignaturePng)
		if err != nil || len(png) > 1<<20 || !bytes.HasPrefix(png, pngMagic) {
			resp.Rejected = append(resp.Rejected, Rejected{ref, "firma non valida"})
			continue
		}
		file, err := s.saveSignature(d.StopID, png)
		if err != nil {
			s.internalError(w, err)
			return
		}
		codeOK := subtle.ConstantTimeCompare([]byte(code), []byte(d.RecipientCode)) == 1
		if _, err := s.db.ExecContext(ctx, `UPDATE stops SET status = 'DELIVERED', delivered_at = ?, code_ok = ?,
			signature_file = ?, updated_at = ? WHERE id = ?`, d.DeliveredAt, codeOK, file, now, d.StopID); err != nil {
			s.internalError(w, err)
			return
		}
		detail := ""
		if !codeOK {
			detail = "codice destinatario errato"
		}
		s.logEvent(ctx, u.ID, "delivered", ref, detail)
		resp.AcceptedStops = append(resp.AcceptedStops, d.StopID)
	}

	writeJSON(w, http.StatusOK, resp)
}

// saveSignature scrive prima un file temporaneo e poi lo rinomina:
// così non resta mai un'immagine a metà se il server si spegne.
func (s *Server) saveSignature(stopID int64, png []byte) (string, error) {
	dir := filepath.Join(s.dataDir, "firme")
	if err := os.MkdirAll(dir, 0o750); err != nil {
		return "", err
	}
	name := fmt.Sprintf("stop_%d.png", stopID)
	tmp, err := os.CreateTemp(dir, "tmp-*")
	if err != nil {
		return "", err
	}
	if _, err := tmp.Write(png); err != nil {
		tmp.Close()
		os.Remove(tmp.Name())
		return "", err
	}
	if err := tmp.Sync(); err != nil {
		tmp.Close()
		os.Remove(tmp.Name())
		return "", err
	}
	tmp.Close()
	if err := os.Rename(tmp.Name(), filepath.Join(dir, name)); err != nil {
		os.Remove(tmp.Name())
		return "", err
	}
	return name, nil
}

func (s *Server) logEvent(ctx context.Context, userID int64, kind, ref, detail string) {
	_, _ = s.db.ExecContext(ctx, `INSERT INTO events (user_id, kind, ref, detail, at) VALUES (?, ?, ?, ?, ?)`,
		userID, kind, ref, detail, s.now().UnixMilli())
}
