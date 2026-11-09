package db

import (
	"context"
	"testing"
	"time"
)

func TestListeningPeriodStats(t *testing.T) {
	store, _ := openTestStore(t)
	if _, err := store.DB.Exec(`INSERT INTO tracks(id,path,title,artist,created_at,updated_at)
        VALUES (1,'/one','One','Artist A','2026-01-01','2026-01-01'),
               (2,'/two','Two','Artist B','2026-01-01','2026-01-01')`); err != nil {
		t.Fatal(err)
	}
	_, profile, err := store.CreateUserWithDefaultProfile(context.Background(), "Other")
	if err != nil {
		t.Fatal(err)
	}
	other := store.ForProfile(profile.ID)
	from := time.Date(2026, 11, 2, 0, 0, 0, 0, time.UTC)
	until := from.AddDate(0, 0, 7)
	add := func(s *Store, track int64, action, source, impression, stamp string, seconds float64) {
		t.Helper()
		var imp any
		if impression != "" {
			imp = impression
		}
		_, err := s.DB.Exec(`INSERT INTO listening_history
            (profile_id, track_id, ts, source, action, session_id, duration_sec, listened_sec, impression_id)
            VALUES (?,?,?,?,?,'session',100,?,?)`,
			s.ProfileID, track, stamp, source, action, seconds, imp)
		if err != nil {
			t.Fatal(err)
		}
	}
	// Two complete listens of the same song: progress is cumulative, not additive.
	add(store, 1, "track_start", "manual", "play-1", "2026-11-03T01:00:00Z", 0)
	add(store, 1, "progress", "manual", "play-1", "2026-11-03T01:00:45Z", 45)
	add(store, 1, "track_end", "manual", "play-1", "2026-11-03T01:01:40Z", 100)
	add(store, 1, "track_start", "manual", "play-2", "2026-11-03T02:00:00Z", 0)
	add(store, 1, "track_end", "manual", "play-2", "2026-11-03T02:01:40Z", 100)
	// Two legacy plays in one session must remain separate. Includes old SQL timestamps.
	add(store, 2, "track_start", "radio", "", "2026-11-04 01:00:00", 0)
	add(store, 2, "skip", "radio", "", "2026-11-04 01:00:10", 10)
	add(store, 2, "track_start", "radio", "", "2026-11-04 02:00:00", 0)
	add(store, 2, "skip", "radio", "", "2026-11-04 02:00:20", 20)
	// Exclude another profile, share listeners, unplayed queue and out-of-period listens.
	add(other, 1, "track_end", "manual", "other", "2026-11-03T03:00:00Z", 100)
	add(store, 1, "track_end", "share", "share", "2026-11-03T03:00:00Z", 100)
	add(store, 1, "track_end", "manual", "old", "2026-10-03T03:00:00Z", 100)
	add(store, 1, "track_start", "manual", "future", "2026-11-09T00:00:00Z", 0)
	got, err := store.ListeningPeriodStats(from, until)
	if err != nil {
		t.Fatal(err)
	}
	if got.Plays != 4 || got.ListenedSeconds != 230 || got.FinishRate != 0.5 {
		t.Fatalf("totals = %+v", got)
	}
	if len(got.Tracks) != 2 || got.Tracks[0].TrackID != 1 || got.Tracks[0].Count != 2 {
		t.Fatalf("tracks = %+v", got.Tracks)
	}
	if len(got.Artists) != 2 || got.Artists[0].Artist != "Artist A" || got.Artists[0].Count != 2 {
		t.Fatalf("artists = %+v", got.Artists)
	}
	empty, err := store.ListeningPeriodStats(until.AddDate(1, 0, 0), until.AddDate(2, 0, 0))
	if err != nil || empty.Plays != 0 || empty.FinishRate != 0 || empty.Tracks == nil || empty.Artists == nil {
		t.Fatalf("empty = %+v, %v", empty, err)
	}
	// A calendar-year query includes the older listen that the weekly query omits.
	yearly, err := store.ListeningPeriodStats(time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC), until)
	if err != nil || yearly.Plays != 5 || yearly.ListenedSeconds != 330 {
		t.Fatalf("yearly = %+v, %v", yearly, err)
	}
}
