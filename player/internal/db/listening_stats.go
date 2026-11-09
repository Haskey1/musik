package db

import (
	"database/sql"
	"time"
)

type ListeningTrack struct {
	TrackID int64  `json:"track_id"`
	Title   string `json:"title"`
	Artist  string `json:"artist"`
	Count   int    `json:"count"`
}

type ListeningPeriod struct {
	ListenedSeconds float64          `json:"listened_seconds"`
	Plays           int              `json:"plays"`
	FinishRate      float64          `json:"finish_rate"`
	Tracks          []ListeningTrack `json:"tracks"`
	Artists         []ArtistCount    `json:"artists"`
}

// ListeningPeriodStats describes actual listens, including manual playback. A
// progress event contains cumulative time: take its maximum per play instead of
// summing progress and end events. Stable impression IDs deduplicate modern
// plays; track_start boundaries distinguish repeated legacy plays in a session.
func (s *Store) ListeningPeriodStats(from, until time.Time) (ListeningPeriod, error) {
	out := ListeningPeriod{Tracks: []ListeningTrack{}, Artists: []ArtistCount{}}
	const plays = `WITH events AS (
        SELECT h.*,
            SUM(CASE WHEN action='track_start' THEN 1 ELSE 0 END) OVER (
                PARTITION BY session_id, track_id ORDER BY ts, id
                ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
            ) AS run
        FROM listening_history h
        WHERE profile_id=? AND COALESCE(source,'') != 'share'
          AND action IN ('track_start','progress','track_end','skip')
    ), plays AS (
        SELECT track_id, MIN(ts) AS started_at,
            MAX(CASE WHEN action IN ('track_start','track_end','skip') THEN 1 ELSE 0 END) AS started,
            MAX(CASE WHEN reason='completed' OR
                (duration_sec > 0 AND listened_sec / duration_sec >= 0.8)
                THEN 1 ELSE 0 END) AS finished,
            MAX(CASE WHEN listened_sec > 0 THEN listened_sec ELSE 0 END) AS seconds
        FROM events
        GROUP BY track_id, COALESCE(NULLIF(impression_id,''),
            'legacy:' || COALESCE(session_id,'') || ':' || CAST(run AS TEXT))
    ), period AS (
        SELECT * FROM plays WHERE started=1 AND REPLACE(started_at,' ','T') >= ? AND REPLACE(started_at,' ','T') < ?
    ) `
	args := []any{s.ProfileID, from.UTC().Format("2006-01-02T15:04:05"), until.UTC().Format("2006-01-02T15:04:05")}
	var finished int
	err := s.DB.QueryRow(plays+`SELECT COUNT(*), COALESCE(SUM(seconds),0), COALESCE(SUM(finished),0) FROM period`, args...).Scan(&out.Plays, &out.ListenedSeconds, &finished)
	if err != nil {
		return out, err
	}
	if out.Plays > 0 {
		out.FinishRate = float64(finished) / float64(out.Plays)
	}
	out.Tracks, err = QueryAll(s.DB, func(rows *sql.Rows, track *ListeningTrack) error {
		return rows.Scan(&track.TrackID, &track.Title, &track.Artist, &track.Count)
	}, plays+`SELECT p.track_id, COALESCE(t.title,''), COALESCE(t.artist,''), COUNT(*)
        FROM period p JOIN tracks t ON t.id=p.track_id
        GROUP BY p.track_id, t.title, t.artist ORDER BY COUNT(*) DESC, p.track_id LIMIT 5`, args...)
	if err != nil {
		return out, err
	}
	out.Artists, err = QueryAll(s.DB, func(rows *sql.Rows, artist *ArtistCount) error {
		return rows.Scan(&artist.Artist, &artist.Count)
	}, plays+`SELECT t.artist, COUNT(*) FROM period p JOIN tracks t ON t.id=p.track_id
        WHERE COALESCE(t.artist,'') != '' GROUP BY t.artist
        ORDER BY COUNT(*) DESC, t.artist LIMIT 8`, args...)
	if out.Tracks == nil {
		out.Tracks = []ListeningTrack{}
	}
	if out.Artists == nil {
		out.Artists = []ArtistCount{}
	}
	return out, err
}
