package main

import (
	"fmt"
	"log/slog"
	"os"
)

// seedDemo crea un autista e un viaggio di prova con gli stessi dati dell'app.
func seedDemo(s *Server) error {
	var n int
	if err := s.db.QueryRow(`SELECT COUNT(*) FROM trips`).Scan(&n); err != nil || n > 0 {
		return err
	}
	pass := env("DEMO_DRIVER_PASSWORD", "autista2026")
	driverID, err := createUser(s.db, "autista", "Autista Demo", "driver", pass, s.now())
	if err != nil {
		return err
	}
	now := s.now()
	res, err := s.db.Exec(`INSERT INTO trips (vehicle_plate, route, ferry_route, boarding_by, departure, driver_id, created_at)
		VALUES ('FR-482-KL', 'Milano → Algeciras → Marocco', 'Algeciras → Tanger Med', '12:30', '14:00', ?, ?)`, driverID, now.Unix())
	if err != nil {
		return err
	}
	tripID, _ := res.LastInsertId()

	type stop struct {
		name, addr, city, phone, kind string
		lat, lon                      float64
		bags                          int
	}
	stops := []stop{
		{"Agenzia Tanger Centre", "Bd Mohammed V 12", "Tanger", "+212539000001", "AGENCY", 35.7767, -5.8039, 4},
		{"Fam. El Amrani", "Rue Al Jazair 8", "Tétouan", "+212661000002", "HOME", 35.5785, -5.3684, 3},
		{"Agenzia Fès Médina", "Rue Talaa Kebira 41", "Fès", "+212535000003", "AGENCY", 34.0631, -4.9773, 5},
		{"Sig.ra Benali", "Av. des FAR 22", "Meknès", "+212662000004", "HOME", 33.8935, -5.5473, 2},
		{"Agenzia Casablanca Maârif", "Rue Abou Al Waqt 5", "Casablanca", "+212522000005", "AGENCY", 33.5822, -7.6327, 4},
	}
	items := []struct {
		d string
		w float64
	}{{"Valigia blu", 23.5}, {"Valigia grigia", 19}, {"Borsone nero", 18}, {"Scatola cartone", 30}, {"Valigia rossa", 21}, {"Sacco tela", 15.5}}

	num := 400
	for i, st := range stops {
		code, err := randomDigits(4)
		if err != nil {
			return err
		}
		r, err := s.db.Exec(`INSERT INTO stops (trip_id, position, name, address, city, latitude, longitude, phone, kind, recipient_code, updated_at)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`, tripID, i+1, st.name, st.addr, st.city, st.lat, st.lon, st.phone, st.kind, code, now.UnixMilli())
		if err != nil {
			return err
		}
		stopID, _ := r.LastInsertId()
		fmt.Fprintf(os.Stdout, "demo: fermata %d %-28s codice destinatario %s\n", stopID, st.name, code)
		for j := 0; j < st.bags; j++ {
			it := items[(num+j)%len(items)]
			if _, err := s.db.Exec(`INSERT INTO bags (code, trip_id, stop_id, description, weight_kg, updated_at) VALUES (?, ?, ?, ?, ?, ?)`,
				fmt.Sprintf("BM-26-%04d", num), tripID, stopID, it.d, it.w, now.UnixMilli()); err != nil {
				return err
			}
			num++
		}
	}
	slog.Info("dati demo creati", "autista", "autista", "viaggio", tripID)
	return nil
}
