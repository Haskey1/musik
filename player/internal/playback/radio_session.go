package playback

import (
	"math"

	"github.com/torwin-job/musik/player/internal/queue"
	"github.com/torwin-job/musik/player/internal/taste"
)

// longTasteScale says how much a finished track teaches the long-term taste.
// What the listener chose themselves counts fully; what the radio picked counts
// less, and the more adventurous the pick was (new, off-taste) the less: a dump of
// a thousand new tracks played by the radio must not rewrite who the listener is.
// The session itself always learns the full weight (it is "what I want right now").
func longTasteScale(source string) float64 {
	switch source {
	case queue.SourceExploit, queue.SourceTransition, queue.SourceResurface:
		return 0.7
	case queue.SourceExploreAdjacent, queue.SourceNewInLibrary, queue.SourceWildcard:
		return 0.35
	default: // manual, radio_start, back, refill…: the listener's own choice
		return 1
	}
}

// anchorSession makes a radio started on a track keep the artist and the track as its
// anchor: the artist's centroid and the track itself, so "radio on Кино" stays
// Кино-like after the first song instead of falling back to the long-term taste.
func (e *Engine) anchorSession(sess *Session, seed int64) {
	row, ok := e.Idx.RowOf(seed)
	if !ok {
		return
	}
	var artists []string
	if name := e.Idx.MetaAt(row).Artist; name != "" {
		artists = []string{name}
	}
	sess.TasteState.SetAnchor(taste.SeedVector(e.Idx, []int64{seed}, artists, nil))
}

// shiftsRadio: skip-driven queue changes only make sense for radio-like sessions.
func shiftsRadio(sess *Session) bool {
	return len(sess.DailyIDs) == 0 && (sess.Mode == "radio" || sess.Mode == "session")
}

// negativeBoost makes the skipped-neighbourhood penalty bite harder during a run of skips.
func negativeBoost(sess *Session, penalty float64) float64 {
	streak := sess.TasteState.SkipStreak
	if streak > 4 {
		streak = 4
	}
	return math.Min(1.5, penalty*(1+0.3*float64(streak)))
}
