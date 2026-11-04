package playback

import (
	"testing"

	"github.com/torwin-job/musik/player/internal/queue"
)

// The phone plays its prefetched copy of the queue ahead of the server's answer, so the
// server's queue can still hold the track the phone is already on. It must never come
// back as "next": that is the same track twice in a row.
func TestAdvanceNeverRepeatsCurrentTrack(t *testing.T) {
	engine := testEngine(t)
	sess := engine.NewSession("radio")
	sess.Lock()
	defer sess.Unlock()
	sess.Current = 22
	sess.Queue = []queue.Item{{TrackID: 22}, {TrackID: 33}}

	if next := engine.Advance(sess); next == 22 {
		t.Fatalf("Advance returned the current track %d again", next)
	}
}

func TestTrackStartDropsStartedTrackFromRadioQueue(t *testing.T) {
	engine := testEngine(t)
	sess := engine.NewSession("radio")
	sess.Lock()
	defer sess.Unlock()
	// the server thinks 11 is playing and queued 22 behind 33; the phone went to 22
	sess.Current = 11
	sess.Queue = []queue.Item{{TrackID: 33}, {TrackID: 22}}

	if res := engine.ApplyEvent(sess, Event{Type: "track_start", TrackID: 22}); res.Error != "" {
		t.Fatal(res.Error)
	}
	for _, item := range sess.Queue {
		if item.TrackID == 22 {
			t.Fatalf("started track still queued: %+v", sess.Queue)
		}
	}
}
