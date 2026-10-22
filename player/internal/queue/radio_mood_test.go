package queue

import (
	"fmt"
	"testing"

	"github.com/torwin-job/musik/player/internal/config"
	"github.com/torwin-job/musik/player/internal/db"
	"github.com/torwin-job/musik/player/internal/index"
	"github.com/torwin-job/musik/player/internal/taste"
)

// moodLibrary is the situation from real life: a library flooded with electronic
// tracks of many artists (cluster 0, a thousand of them in the real one) and a
// smaller rock part (cluster 1, "Кино" among them).
func moodLibrary(t *testing.T) *index.Index {
	t.Helper()
	idx := index.New(config.Config{NewTrackDays: 30, NewBoostTauDays: 14})
	var rows []db.TrackRow
	id := int64(0)
	add := func(artist string, cluster int, vec []float32) {
		id++
		rows = append(rows, db.TrackRow{
			ID: id, Artist: artist, Title: fmt.Sprintf("T%d", id), Album: artist,
			Path: "/x.mp3", Duration: 200, ClusterID: cluster,
			Embedding: index.Float32Bytes(vec), Dim: 3, Plays: 1,
		})
	}
	for i := 0; i < 150; i++ { // one track each of many electronic artists
		add(fmt.Sprintf("DJ %d", i), 0, []float32{1, float32(i%7) * 0.02, float32(i%5) * 0.03})
	}
	for a := 0; a < 8; a++ { // rock: eight artists, three tracks each
		for k := 0; k < 3; k++ {
			add(fmt.Sprintf("Rock %d", a), 1, []float32{0.05, 1, float32(k) * 0.02})
		}
	}
	if err := idx.Load(rows); err != nil {
		t.Fatal(err)
	}
	return idx
}

func rockShare(items []Item) float64 {
	rock := 0
	for _, it := range items {
		if it.ClusterID == 1 {
			rock++
		}
	}
	return float64(rock) / float64(len(items))
}

func listenerTaste() *taste.State {
	long := taste.New()
	long.SetWithMeta([]float32{1, 0.05, 0}, 500, 0, "test") // a day of electronic
	return taste.NewState(long)
}

func build(b *Builder, idx *index.Index, tasteVec []float32, sess *taste.SessionState, st *taste.State) []Item {
	centroids := [][]float32{}
	if len(sess.Anchor) > 0 && sess.AnchorWeight() >= 0.15 {
		centroids = append(centroids, sess.Anchor)
	}
	return b.BuildCore(CoreOpts{
		CurrentID: 0, Taste: tasteVec, Session: sess.SessionVector(), Centroids: centroids,
		Size: 6,
		Negative: func(v []float32) float64 {
			return st.NegativePenalty(v, sess)
		},
		ClusterPenalty: sess.ClusterPenalty,
		Shifted:        sess.Shifted(),
	})
}

func TestRadioOnRockAfterADayOfElectronic(t *testing.T) {
	idx := moodLibrary(t)
	b := NewBuilder(idx, config.Config{QueueSize: 6})
	st := listenerTaste()

	plain := &taste.SessionState{}
	before := rockShare(build(b, idx, st.Effective("day", plain, 5), plain, st))
	if before > 0.34 {
		t.Fatalf("setup: the long-term taste is electronic, yet rock share is %.2f", before)
	}

	anchored := &taste.SessionState{}
	anchored.SetAnchor([]float32{0.05, 1, 0.02})
	after := rockShare(build(b, idx, st.Effective("day", anchored, 5), anchored, st))
	if after < 0.66 {
		t.Fatalf("radio started on rock must give mostly rock, got %.2f (was %.2f)", after, before)
	}
}

func TestSkippingElectronicMovesTheRadioOn(t *testing.T) {
	idx := moodLibrary(t)
	b := NewBuilder(idx, config.Config{QueueSize: 6})
	st := listenerTaste()
	sess := &taste.SessionState{}

	first := rockShare(build(b, idx, st.Effective("day", sess, 5), sess, st))
	// the listener skips three electronic tracks in a row
	for i := 0; i < 3; i++ {
		st.AddEarlySkip(sess, []float32{1, float32(i) * 0.02, 0}, 0.9)
		sess.NoteSkip(0)
	}
	later := rockShare(build(b, idx, st.Effective("day", sess, 5), sess, st))
	if later <= first || later < 0.5 {
		t.Fatalf("after three skips the radio must change: rock %.2f -> %.2f", first, later)
	}
}

func TestOneSkipDoesNotThrowAClusterOut(t *testing.T) {
	idx := moodLibrary(t)
	b := NewBuilder(idx, config.Config{QueueSize: 6})
	st := listenerTaste()
	sess := &taste.SessionState{}
	st.AddEarlySkip(sess, []float32{1, 0, 0}, 0.9)
	sess.NoteSkip(0)
	items := build(b, idx, st.Effective("day", sess, 5), sess, st)
	if rockShare(items) > 0.5 {
		t.Fatalf("one skip is not a verdict, rock share %.2f", rockShare(items))
	}
}

func TestMoodShiftLeavesTheCurrentKindOfMusic(t *testing.T) {
	idx := moodLibrary(t)
	b := NewBuilder(idx, config.Config{QueueSize: 6})
	st := listenerTaste()
	sess := &taste.SessionState{}
	sess.ResetMood([]float32{1, 0.03, 0.02}, 0)
	items := build(b, idx, st.Effective("day", sess, 5), sess, st)
	if rockShare(items) < 0.66 {
		t.Fatalf("after 'another mood' the queue must leave the current cluster, rock share %.2f", rockShare(items))
	}
}
