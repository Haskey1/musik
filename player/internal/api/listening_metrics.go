package api

import (
	"net/http"
	"time"
)

// Personal history rather than recommendation quality: manual plays count too.
func (s *Server) handleListeningMetrics(w http.ResponseWriter, _ *http.Request) {
	now := time.Now().UTC()
	weekly, err := s.Store.ListeningPeriodStats(now.AddDate(0, 0, -7), now.Add(time.Second))
	if err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	yearStart := time.Date(now.Year(), time.January, 1, 0, 0, 0, 0, time.UTC)
	yearly, err := s.Store.ListeningPeriodStats(yearStart, now.Add(time.Second))
	if err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	writeJSON(w, map[string]any{"year": now.Year(), "weekly": weekly, "yearly": yearly})
}
