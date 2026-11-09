package api

import (
	"github.com/torwin-job/musik/player/internal/db"
	"net/http"
	"time"
)

// Adapt the web statistics without independently counting history events.
func (s *Server) handleListeningMetrics(w http.ResponseWriter, _ *http.Request) {
	weekly, err := s.Store.WeeklyMetrics()
	if err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	memory, err := s.Store.ListeningMemory(time.Now().UTC())
	if err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	convert := func(period db.WeeklyListening) db.ListeningPeriod {
		out := db.ListeningPeriod{ListenedSeconds: float64(period.ListenedSec), Plays: period.Plays, FinishRate: period.FinishRate, Tracks: []db.ListeningTrack{}, Artists: period.TopArtists}
		if out.Artists == nil {
			out.Artists = []db.ArtistCount{}
		}
		for _, track := range period.TopTracks {
			out.Tracks = append(out.Tracks, db.ListeningTrack{TrackID: track.TrackID, Title: track.Title, Artist: track.Artist, Count: track.Plays})
		}
		return out
	}
	writeJSON(w, map[string]any{"year": memory.Year.Year, "weekly": convert(weekly.Listening), "yearly": convert(memory.Year.WeeklyListening)})
}
