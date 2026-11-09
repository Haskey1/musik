package api

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"testing"
	"time"

	"github.com/torwin-job/musik/player/internal/config"
	"github.com/torwin-job/musik/player/internal/db"
	"github.com/torwin-job/musik/player/internal/index"
	"github.com/torwin-job/musik/player/internal/taste"
	"github.com/torwin-job/musik/player/internal/testdb"
)

func TestListeningMetricsRoute(t *testing.T) {
	path := filepath.Join(t.TempDir(), "listening.db")
	databaseURL, path := testdb.Target(t, path)
	store, err := db.OpenDatabase(databaseURL, path)
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	cfg := config.Config{DBPath: path, QueueSize: 6, AuthDisabled: true}
	handler := New(cfg, store, index.New(cfg), taste.New(), nil).Handler()
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, httptest.NewRequest(http.MethodGet, "/api/metrics/listening", nil))
	if response.Code != http.StatusOK {
		t.Fatalf("status=%d body=%s", response.Code, response.Body.String())
	}
	var body struct {
		Year   int                `json:"year"`
		Weekly db.ListeningPeriod `json:"weekly"`
		Yearly db.ListeningPeriod `json:"yearly"`
	}
	if err := json.Unmarshal(response.Body.Bytes(), &body); err != nil {
		t.Fatal(err)
	}
	if body.Year != time.Now().UTC().Year() || body.Weekly.Plays != 0 || body.Yearly.Plays != 0 || body.Weekly.Tracks == nil || body.Yearly.Artists == nil {
		t.Fatalf("unexpected response: %s", response.Body.String())
	}
}
