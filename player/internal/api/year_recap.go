package api

import (
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"github.com/torwin-job/musik/player/internal/db"
)

const recapYear = 2026

func (s *Server) recapFlag() string {
	return filepath.Join(s.Cfg.DataRoot(), "year-recap-"+strconv.Itoa(recapYear)+".enabled")
}

func (s *Server) recapPublished() bool {
	data, err := os.ReadFile(s.recapFlag())
	return err == nil && string(data) == "on\n"
}

func (s *Server) recapAllowed(r *http.Request) bool {
	p := caller(r)
	if !s.Cfg.MultiUser || p == nil || p.UserID == "" {
		return false
	}
	return s.recapPublished() || p.UserID == strings.TrimSpace(os.Getenv("MUSIK_RECAP_OWNER_USER_ID"))
}

func (s *Server) handleRecapAvailability(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "private, no-store")
	writeJSON(w, map[string]any{"available": s.recapAllowed(r), "year": recapYear})
}

type yearRecap struct {
	Year        int                `json:"year"`
	Stats       db.ListeningPeriod `json:"stats"`
	ArtistTrack *db.ListeningTrack `json:"artist_track"`
}

func (s *Server) loadRecap() (yearRecap, error) {
	from := time.Date(recapYear, 1, 1, 0, 0, 0, 0, time.UTC)
	until := from.AddDate(1, 0, 0)
	stats, err := s.Store.ListeningPeriodStats(from, until)
	recap := yearRecap{Year: recapYear, Stats: stats}
	if err == nil && len(stats.Artists) > 0 {
		recap.ArtistTrack, err = s.Store.ListeningArtistTrack(from, until, stats.Artists[0].Artist)
	}
	return recap, err
}

func (s *Server) handleYearRecap(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "private, no-store")
	if !s.recapAllowed(r) {
		writeErr(w, 404, "not_found", "not found")
		return
	}
	recap, err := s.loadRecap()
	if err != nil {
		writeErr(w, 500, "recap", "recap unavailable")
		return
	}
	writeJSON(w, recap)
}

func (s *Server) handleRecapClip(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "private, no-store")
	if !s.recapAllowed(r) {
		writeErr(w, 404, "not_found", "not found")
		return
	}
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil || id <= 0 {
		writeErr(w, 404, "not_found", "not found")
		return
	}
	recap, err := s.loadRecap()
	if err != nil {
		writeErr(w, 500, "recap", "recap unavailable")
		return
	}
	allowed := recap.ArtistTrack != nil && recap.ArtistTrack.TrackID == id
	for _, track := range recap.Stats.Tracks {
		allowed = allowed || track.TrackID == id
	}
	if !allowed {
		writeErr(w, 404, "not_found", "not found")
		return
	}
	path, err := s.Store.TrackPath(id)
	if err != nil || path == "" {
		writeErr(w, 404, "not_found", "not found")
		return
	}
	clip, err := recapClip(r.Context(), filepath.Join(s.Cfg.DataRoot(), "cache", "year-recap"), path)
	if err != nil {
		writeErr(w, 500, "recap_audio", "recap audio unavailable")
		return
	}
	writeJSON(w, map[string]any{"audio": clip})
}
