package main

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"testing"
	"time"
)

type testEnv struct {
	t   *testing.T
	s   *Server
	srv *httptest.Server
}

func newTestEnv(t *testing.T) *testEnv {
	t.Helper()
	dir := t.TempDir()
	db, err := openDB(filepath.Join(dir, "test.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { db.Close() })
	s := &Server{db: db, dataDir: dir, notifier: logNotifier{}, limiter: newLoginLimiter(), now: time.Now}
	if _, err := createUser(db, "admin", "Admin", "admin", "password-admin", s.now()); err != nil {
		t.Fatal(err)
	}
	if err := seedDemo(s); err != nil {
		t.Fatal(err)
	}
	srv := httptest.NewServer(s.routes())
	t.Cleanup(srv.Close)
	return &testEnv{t: t, s: s, srv: srv}
}

func (e *testEnv) do(method, path, token string, body any, out any) int {
	e.t.Helper()
	var buf bytes.Buffer
	if body != nil {
		json.NewEncoder(&buf).Encode(body)
	}
	req, _ := http.NewRequest(method, e.srv.URL+path, &buf)
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		e.t.Fatal(err)
	}
	defer resp.Body.Close()
	if out != nil {
		json.NewDecoder(resp.Body).Decode(out)
	}
	return resp.StatusCode
}

func (e *testEnv) login(user, pass string) string {
	e.t.Helper()
	var out struct{ Token string }
	if code := e.do("POST", "/api/v1/login", "", map[string]string{"username": user, "password": pass}, &out); code != 200 {
		e.t.Fatalf("login %s: %d", user, code)
	}
	return out.Token
}

// Una firma PNG minima valida.
var tinyPNG = base64.StdEncoding.EncodeToString(append(append([]byte{}, pngMagic...), 0, 0, 0, 0))

func TestPasswordHash(t *testing.T) {
	h, err := hashPassword("segreta-123")
	if err != nil {
		t.Fatal(err)
	}
	if !checkPassword(h, "segreta-123") || checkPassword(h, "sbagliata") {
		t.Fatal("verifica password errata")
	}
}

func TestLoginBlockedAfterFailures(t *testing.T) {
	e := newTestEnv(t)
	for i := 0; i < 5; i++ {
		if code := e.do("POST", "/api/v1/login", "", map[string]string{"username": "autista", "password": "x"}, nil); code != 401 {
			t.Fatalf("tentativo %d: atteso 401, ottenuto %d", i, code)
		}
	}
	if code := e.do("POST", "/api/v1/login", "", map[string]string{"username": "autista", "password": "autista2026"}, nil); code != 429 {
		t.Fatalf("atteso blocco 429, ottenuto %d", code)
	}
}

func TestDriverFlow(t *testing.T) {
	e := newTestEnv(t)
	tok := e.login("autista", "autista2026")

	var trip TripDetail
	if code := e.do("GET", "/api/v1/driver/trip", tok, nil, &trip); code != 200 {
		t.Fatalf("trip: %d", code)
	}
	if len(trip.Stops) != 5 || len(trip.Bags) != 18 {
		t.Fatalf("attese 5 fermate e 18 bagagli, ottenute %d e %d", len(trip.Stops), len(trip.Bags))
	}
	if trip.Stops[0].RecipientCode != "" {
		t.Fatal("l'autista non deve ricevere il codice in chiaro")
	}

	// codice reale della prima fermata (letto come farebbe la gestione)
	stop := trip.Stops[0]
	var realCode string
	e.s.db.QueryRow(`SELECT recipient_code FROM stops WHERE id = ?`, stop.ID).Scan(&realCode)
	if codeHash(stop.ID, realCode) != stop.RecipientCodeHash {
		t.Fatal("hash del codice non corrisponde")
	}

	var bagsOfStop []map[string]any
	for _, b := range trip.Bags {
		if b.StopID == stop.ID {
			bagsOfStop = append(bagsOfStop, map[string]any{"code": b.Code, "status": "UNLOADED", "scannedAt": 1000})
		}
	}
	sync := map[string]any{
		"bags":       bagsOfStop,
		"deliveries": []map[string]any{{"stopId": stop.ID, "deliveredAt": 2000, "recipientCode": realCode, "signaturePng": tinyPNG}},
	}
	for round := 0; round < 2; round++ { // due volte: deve essere ripetibile
		var resp SyncResponse
		if code := e.do("POST", "/api/v1/driver/sync", tok, sync, &resp); code != 200 {
			t.Fatalf("sync: %d", code)
		}
		if len(resp.AcceptedBags) != len(bagsOfStop) || len(resp.AcceptedStops) != 1 || len(resp.Rejected) != 0 {
			t.Fatalf("round %d: risposta inattesa %+v", round, resp)
		}
	}

	adminTok := e.login("admin", "password-admin")
	var detail TripDetail
	e.do("GET", "/api/v1/admin/trips/1", adminTok, nil, &detail)
	if detail.Stops[0].Status != "DELIVERED" || detail.Stops[0].CodeOK == nil || !*detail.Stops[0].CodeOK || !detail.Stops[0].HasSignature {
		t.Fatalf("consegna non registrata bene: %+v", detail.Stops[0])
	}

	var track map[string]any
	if code := e.do("GET", "/api/v1/track/"+trip.Bags[0].Code, "", nil, &track); code != 200 || track["status"] != "UNLOADED" {
		t.Fatalf("tracking: %d %v", code, track)
	}
}

func TestSecurityBoundaries(t *testing.T) {
	e := newTestEnv(t)
	driverTok := e.login("autista", "autista2026")
	adminTok := e.login("admin", "password-admin")

	if code := e.do("GET", "/api/v1/driver/trip", "", nil, nil); code != 401 {
		t.Fatalf("senza token: atteso 401, ottenuto %d", code)
	}
	if code := e.do("GET", "/api/v1/admin/trips", driverTok, nil, nil); code != 403 {
		t.Fatalf("autista su gestione: atteso 403, ottenuto %d", code)
	}

	// un secondo autista non può toccare i bagagli del primo
	e.do("POST", "/api/v1/admin/users", adminTok, map[string]string{"username": "altro", "name": "Altro", "role": "driver", "password": "altro-2026"}, nil)
	other := e.login("altro", "altro-2026")
	var resp SyncResponse
	e.do("POST", "/api/v1/driver/sync", other, map[string]any{
		"bags": []map[string]any{{"code": "BM-26-0400", "status": "UNLOADED", "scannedAt": 1}},
	}, &resp)
	if len(resp.AcceptedBags) != 0 || len(resp.Rejected) != 1 {
		t.Fatalf("un altro autista ha modificato un bagaglio: %+v", resp)
	}

	// firma non PNG rifiutata
	e.do("POST", "/api/v1/driver/sync", driverTok, map[string]any{
		"deliveries": []map[string]any{{"stopId": 1, "deliveredAt": 1, "recipientCode": "0000", "signaturePng": base64.StdEncoding.EncodeToString([]byte("<script>"))}},
	}, &resp)
	if len(resp.Rejected) != 1 {
		t.Fatalf("firma non valida accettata: %+v", resp)
	}

	// logout invalida il token
	e.do("POST", "/api/v1/logout", driverTok, nil, nil)
	if code := e.do("GET", "/api/v1/driver/trip", driverTok, nil, nil); code != 401 {
		t.Fatalf("token dopo logout: atteso 401, ottenuto %d", code)
	}
}

func TestAdminCreatesStopAndBag(t *testing.T) {
	e := newTestEnv(t)
	adminTok := e.login("admin", "password-admin")
	var stop struct {
		ID            int64
		RecipientCode string
	}
	code := e.do("POST", "/api/v1/admin/trips/1/stops", adminTok, map[string]any{
		"name": "Agenzia Rabat", "address": "Av. Hassan II 1", "city": "Rabat", "latitude": 34.02, "longitude": -6.84,
		"phone": "+212537000006", "kind": "AGENCY",
	}, &stop)
	if code != 201 || len(stop.RecipientCode) != 4 {
		t.Fatalf("fermata: %d %+v", code, stop)
	}
	var bag struct{ Code string }
	code = e.do("POST", "/api/v1/admin/trips/1/bags", adminTok, map[string]any{"stopId": stop.ID, "description": "Valigia verde", "weightKg": 22.0}, &bag)
	if code != 201 || bag.Code == "" {
		t.Fatalf("bagaglio: %d %+v", code, bag)
	}
}
