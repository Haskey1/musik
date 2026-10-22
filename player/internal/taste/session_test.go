package taste

import (
	"math"
	"testing"
)

// Two directions stand for the listener's world: electronic (what a thousand new
// tracks made the long-term taste) and rock (Кино).
var (
	electronic = []float32{1, 0, 0}
	rock       = []float32{0, 1, 0}
	mixed      = []float32{0.7, 0.7, 0}
)

func cos(a, b []float32) float64 {
	var d, na, nb float64
	for i := range a {
		d += float64(a[i]) * float64(b[i])
		na += float64(a[i]) * float64(a[i])
		nb += float64(b[i]) * float64(b[i])
	}
	return d / (math.Sqrt(na) * math.Sqrt(nb))
}

func electronicState() *State {
	long := New()
	long.SetWithMeta([]float32{1, 0.05, 0}, 100, 0, "test")
	return NewState(long)
}

func TestFreshAnchorPullsTasteTowardsTheArtist(t *testing.T) {
	st := electronicState()
	plain := st.Effective("day", &SessionState{}, 5)
	if cos(plain, rock) > 0.2 {
		t.Fatalf("without an anchor the long-term taste must stay electronic, cos(rock)=%.2f", cos(plain, rock))
	}
	sess := &SessionState{}
	sess.SetAnchor(rock)
	fresh := st.Effective("day", sess, 5)
	if cos(fresh, rock) <= cos(fresh, electronic) {
		t.Fatalf("a fresh anchor must win over the long-term taste: rock %.2f electronic %.2f",
			cos(fresh, rock), cos(fresh, electronic))
	}
}

func TestAnchorFadesAsTracksArePlayed(t *testing.T) {
	sess := &SessionState{}
	sess.SetAnchor(rock)
	w0 := sess.AnchorWeight()
	sess.Plays = 12
	w12 := sess.AnchorWeight()
	sess.Plays = 40
	w40 := sess.AnchorWeight()
	if !(w0 == 1 && w12 < 0.4 && w12 > 0.3 && w40 < 0.05) {
		t.Fatalf("weights %.2f %.2f %.2f", w0, w12, w40)
	}
	// and the long-term taste comes back with it
	if sess.LongScale() < 0.95 {
		t.Fatalf("a faded anchor must give the long-term taste back, scale=%.2f", sess.LongScale())
	}
}

func TestListeningToTheAnchorKeepsTheSessionThere(t *testing.T) {
	st := electronicState()
	sess := &SessionState{}
	sess.SetAnchor(rock)
	// ten rock tracks heard to the end
	for i := 0; i < 10; i++ {
		st.UpdatePositiveScaled(rock, 1, 0.1, 0.7, "day", sess)
		sess.NoteListened(1, true)
	}
	v := st.Effective("day", sess, 5)
	if cos(v, rock) < 0.8 {
		t.Fatalf("after ten rock tracks the session must still be rock, cos=%.2f", cos(v, rock))
	}
}

func TestSkipStreakTurnsDownTheLongTermTaste(t *testing.T) {
	st := electronicState()
	sess := &SessionState{}
	sess.Positive = VectorState{Vector: mixed, Samples: 3}
	base := st.Effective("day", sess, 5)
	for i := 0; i < 4; i++ {
		sess.NoteSkip(7)
	}
	if sess.LongScale() > 0.21 {
		t.Fatalf("scale after 4 skips = %.2f", sess.LongScale())
	}
	after := st.Effective("day", sess, 5)
	if cos(after, rock) <= cos(base, rock) {
		t.Fatalf("a skip streak must move the taste to the session: %.2f -> %.2f", cos(base, rock), cos(after, rock))
	}
	sess.NoteListened(1, false)
	if sess.SkipStreak != 0 {
		t.Fatal("a listened track ends the streak")
	}
}

func TestClusterPenaltyGrowsWithSkipsAndFadesWithFinishes(t *testing.T) {
	sess := &SessionState{}
	if sess.ClusterPenalty(5) != 0 {
		t.Fatal("no skips, no penalty")
	}
	sess.NoteSkip(5)
	p1 := sess.ClusterPenalty(5)
	sess.NoteSkip(5)
	p2 := sess.ClusterPenalty(5)
	sess.NoteSkip(5)
	p3 := sess.ClusterPenalty(5)
	if !(p1 < p2 && p2 < p3 && p3 == 1) {
		t.Fatalf("penalties %.2f %.2f %.2f", p1, p2, p3)
	}
	if sess.ClusterPenalty(6) != 0 || sess.ClusterPenalty(-1) != 0 {
		t.Fatal("other and unknown clusters are untouched")
	}
	sess.NoteListened(5, true)
	if sess.ClusterPenalty(5) != p2 {
		t.Fatalf("a finished track takes one skip back: %.2f", sess.ClusterPenalty(5))
	}
	if !sess.ShiftsAway(5) || sess.ShiftsAway(9) && sess.SkipStreak < 2 {
		t.Fatal("two skips in a cluster ask for a new queue")
	}
}

func TestOneSkipDoesNotShiftTheRadio(t *testing.T) {
	sess := &SessionState{}
	sess.NoteSkip(3)
	if sess.ShiftsAway(3) {
		t.Fatal("a single skip is a track, not a pattern")
	}
	sess.NoteSkip(4)
	if !sess.ShiftsAway(4) {
		t.Fatal("two skips in a row shift the radio")
	}
}

func TestResetMoodForgetsTheSessionAndRejectsTheCurrentKind(t *testing.T) {
	st := electronicState()
	sess := &SessionState{}
	sess.SetAnchor(rock)
	st.UpdatePositiveScaled(electronic, 1, 0.1, 1, "day", sess)
	sess.ResetMood(electronic, 2)
	if len(sess.Anchor) != 0 || sess.Positive.Samples != 0 || len(sess.RecentPositive) != 0 {
		t.Fatal("the session must forget what it learned")
	}
	if sess.ClusterPenalty(2) != 1 {
		t.Fatalf("the current cluster is rejected, penalty=%.2f", sess.ClusterPenalty(2))
	}
	if st.NegativePenalty(electronic, sess) < 0.5 {
		t.Fatalf("the current kind of track is a negative prototype, penalty=%.2f", st.NegativePenalty(electronic, sess))
	}
	if st.NegativePenalty(rock, sess) > 0.1 {
		t.Fatal("other music is not penalised")
	}
	if sess.LongScale() > 0.21 {
		t.Fatalf("the long-term taste is turned down after a mood shift: %.2f", sess.LongScale())
	}
}

func TestRadioPickedTracksTeachTheLongTasteLess(t *testing.T) {
	full, scaled := electronicState(), electronicState()
	for i := 0; i < 30; i++ {
		full.UpdatePositiveScaled(rock, 1, 0.1, 1, "day", &SessionState{})
		scaled.UpdatePositiveScaled(rock, 1, 0.1, 0.35, "day", &SessionState{})
	}
	if cos(scaled.Long.Get(), rock) >= cos(full.Long.Get(), rock) {
		t.Fatalf("a scaled update must move the long-term taste less: %.2f vs %.2f",
			cos(scaled.Long.Get(), rock), cos(full.Long.Get(), rock))
	}
	sess := &SessionState{}
	scaled.UpdatePositiveScaled(rock, 1, 0.1, 0.35, "day", sess)
	if cos(sess.Positive.Vector, rock) < 0.99 {
		t.Fatal("the session learns the full weight")
	}
}

func TestSessionStateJSONKeepsTheNewFields(t *testing.T) {
	sess := &SessionState{}
	sess.SetAnchor(rock)
	sess.NoteSkip(4)
	sess.NoteSkip(4)
	raw, err := jsonMarshal(sess)
	if err != nil {
		t.Fatal(err)
	}
	var back SessionState
	if err := jsonUnmarshal(raw, &back); err != nil {
		t.Fatal(err)
	}
	if back.SkipStreak != 2 || back.SkipClusters[4] != 2 || len(back.Anchor) != 3 || back.Plays != 2 {
		t.Fatalf("lost fields: %+v", back)
	}
	var old SessionState // a session saved before this change
	if err := jsonUnmarshal([]byte(`{"positive":{"samples":2}}`), &old); err != nil || old.SkipClusters != nil {
		t.Fatalf("old sessions must load: %v", err)
	}
}
