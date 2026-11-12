package playback

import (
	"fmt"
	"math/rand"
	"testing"

	"github.com/torwin-job/musik/player/internal/db"
	"github.com/torwin-job/musik/player/internal/index"
)

func TestTasteStartSearchesBeyondExcludedTopTracks(t *testing.T) {
	for _, mood := range []string{"", "calm"} {
		t.Run("mood_"+mood, func(t *testing.T) {
			engine := testEngine(t)
			engine.Taste.SetWithMeta([]float32{1, 0}, 20, 0, "test")
			engine.Builder.Rng = rand.New(rand.NewSource(5))
			rows := make([]db.TrackRow, 81)
			for i := range rows {
				vec := []float32{1, 0}
				if i == 80 {
					vec = []float32{0, 1}
				}
				rows[i] = db.TrackRow{ID: int64(i + 1), Artist: fmt.Sprintf("Artist %d", i),
					Title: fmt.Sprintf("Track %d", i), Embedding: index.Float32Bytes(vec), Dim: 2}
			}
			if err := engine.Idx.Load(rows); err != nil {
				t.Fatal(err)
			}
			sess := engine.NewSession("radio")
			sess.MoodPreset = mood
			for id := int64(1); id <= 80; id++ {
				sess.Exclude[id] = true
			}
			for i := 0; i < 20; i++ {
				if got := engine.pickTasteStart(sess); got != 81 {
					t.Fatalf("start=%d, want only unheard track 81", got)
				}
			}
		})
	}
}

func TestTasteStartExcludesRecentSongClones(t *testing.T) {
	engine := anchoredEngine(t)
	rows := []db.TrackRow{
		{ID: 11, Artist: "A", Title: "One", Embedding: index.Float32Bytes([]float32{0, 1}), Dim: 2},
		{ID: 12, Artist: "A", Title: "One", Embedding: index.Float32Bytes([]float32{0, 1}), Dim: 2},
		{ID: 22, Artist: "B", Title: "Two", Embedding: index.Float32Bytes([]float32{1, 0}), Dim: 2},
	}
	if err := engine.Idx.Load(rows); err != nil {
		t.Fatal(err)
	}
	if _, err := engine.Store.InsertListen(11, "track_end", "radio", "", "completed", nil, nil, nil); err != nil {
		t.Fatal(err)
	}
	sess := engine.NewSession("radio")
	if got := engine.pickTasteStart(sess); got != 22 {
		t.Fatalf("recent track or clone selected: %d", got)
	}
}

func TestRadioHistoryProtectsMoreThan120Tracks(t *testing.T) {
	engine := testEngine(t)
	rows := make([]db.TrackRow, 130)
	for i := range rows {
		id := int64(i + 1)
		rows[i] = db.TrackRow{ID: id, Artist: "A", Title: fmt.Sprintf("Track %d", id),
			Embedding: index.Float32Bytes([]float32{1, 0}), Dim: 2}
		if _, err := engine.Store.DB.Exec(`INSERT INTO tracks(id,path,title,artist,created_at,updated_at) VALUES (?,?,?,?,?,?)`,
			id, fmt.Sprintf("/history-%d.flac", id), rows[i].Title, "A", "2026-01-01", "2026-01-01"); err != nil {
			t.Fatal(err)
		}
		if _, err := engine.Store.InsertListen(id, "track_end", "radio", "", "completed", nil, nil, nil); err != nil {
			t.Fatal(err)
		}
	}
	if err := engine.Idx.Load(rows); err != nil {
		t.Fatal(err)
	}
	sess := engine.NewSession("radio")
	engine.SeedRadioExclude(sess)
	for _, row := range rows {
		if !sess.Exclude[row.ID] {
			t.Fatalf("recent history truncated at track %d", row.ID)
		}
	}
}

func TestAnchoredStartDoesNotFallBackToRecentOrBlockedAnchor(t *testing.T) {
	for _, blocked := range []bool{false, true} {
		t.Run(fmt.Sprintf("blocked_%v", blocked), func(t *testing.T) {
			engine := anchoredEngine(t)
			sess := engine.NewSession("radio")
			sess.Anchor = RadioAnchor{Kind: "artist", Artist: "North", Proximity: ProximityNear}
			if blocked {
				if _, err := engine.Store.CreateRadioRule(db.RadioRule{TargetType: "artist", Action: "block",
					Scope: "global", TargetKey: "north", Strength: 1}); err != nil {
					t.Fatal(err)
				}
			} else {
				sess.Exclude[11], sess.Exclude[12] = true, true
			}
			got := engine.pickAnchoredStart(sess, nil)
			if got != 22 && got != 33 {
				t.Fatalf("start=%d, want an eligible track outside the exhausted anchor", got)
			}
		})
	}
}
