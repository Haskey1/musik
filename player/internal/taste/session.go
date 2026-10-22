package taste

import (
	"math"

	"github.com/torwin-job/musik/player/internal/index"
)

const (
	// AnchorStrength is the weight of a fresh anchor next to the long-term taste (1.0).
	AnchorStrength = 1.6
	// anchorDecayTracks: the anchor weight falls to 1/e after this many tracks.
	anchorDecayTracks = 12.0
	// skipsPerClusterCap bounds the per-cluster skip counter.
	skipsPerClusterCap = 6
)

// SetAnchor makes the radio start on this vector (an artist's or a track's).
func (s *SessionState) SetAnchor(vector []float32) {
	if len(vector) == 0 {
		s.Anchor = nil
		return
	}
	s.Anchor = normalizedCopy(vector)
	s.Plays = 0
}

// AnchorWeight is 1 for a fresh anchor and fades towards 0 as tracks are heard.
func (s *SessionState) AnchorWeight() float64 {
	if s == nil || len(s.Anchor) == 0 {
		return 0
	}
	return math.Exp(-float64(s.Plays) / anchorDecayTracks)
}

// LongScale turns the long-term and daypart tastes down while an anchor is fresh
// and after a run of skips (1 = unchanged).
func (s *SessionState) LongScale() float64 {
	if s == nil {
		return 1
	}
	scale := 1 / (1 + 1.2*s.AnchorWeight())
	switch {
	case s.SkipStreak >= 4:
		scale *= 0.2
	case s.SkipStreak == 3:
		scale *= 0.35
	case s.SkipStreak == 2:
		scale *= 0.6
	}
	return scale
}

// NoteSkip records an early skip of a track from this cluster (-1: unknown).
func (s *SessionState) NoteSkip(cluster int) {
	s.Plays++
	s.SkipStreak++
	if cluster < 0 {
		return
	}
	if s.SkipClusters == nil {
		s.SkipClusters = map[int]int{}
	}
	if s.SkipClusters[cluster] < skipsPerClusterCap {
		s.SkipClusters[cluster]++
	}
}

// NoteListened records a track that was heard (partly or to the end). Finishing a
// track of a skipped cluster takes that cluster back by one skip.
func (s *SessionState) NoteListened(cluster int, finished bool) {
	s.Plays++
	s.SkipStreak = 0
	if finished && cluster >= 0 && s.SkipClusters[cluster] > 0 {
		s.SkipClusters[cluster]--
		if s.SkipClusters[cluster] == 0 {
			delete(s.SkipClusters, cluster)
		}
	}
}

// ClusterPenalty is 0 for a cluster that was not skipped and grows to 1 with skips:
// one skip is a track, two are a pattern, three are a verdict.
func (s *SessionState) ClusterPenalty(cluster int) float64 {
	if s == nil || cluster < 0 {
		return 0
	}
	switch n := s.SkipClusters[cluster]; {
	case n <= 0:
		return 0
	case n == 1:
		return 0.2
	case n == 2:
		return 0.7
	default:
		return 1
	}
}

// ShiftsAway is true when the queue built before the last skips no longer fits:
// two skips in a row, or two in one cluster.
func (s *SessionState) ShiftsAway(cluster int) bool {
	if s == nil {
		return false
	}
	return s.SkipStreak >= 2 || (cluster >= 0 && s.SkipClusters[cluster] >= 2)
}

// ResetMood is "another mood": everything the session learned is dropped, the
// current track and its cluster are marked as unwanted for the rest of the session
// and the long-term taste is turned down for the next tracks.
func (s *SessionState) ResetMood(current []float32, cluster int) {
	s.Positive = VectorState{}
	s.RecentPositive = nil
	s.Anchor = nil
	s.SkipStreak = 4
	if cluster >= 0 {
		if s.SkipClusters == nil {
			s.SkipClusters = map[int]int{}
		}
		s.SkipClusters[cluster] = 3
	}
	if len(current) > 0 {
		v := append([]float32(nil), current...)
		index.Normalize(v)
		s.Negative = addNegative(s.Negative, v, 3)
	}
}

// SessionVector is the session's "what I want right now": the positive session
// vector, with a fresh anchor mixed in so the session similarity pulls the same way.
func (s *SessionState) SessionVector() []float32 {
	if s == nil {
		return nil
	}
	w := s.AnchorWeight()
	if w < 0.05 || len(s.Anchor) == 0 {
		return s.Positive.Vector
	}
	if len(s.Positive.Vector) != len(s.Anchor) {
		return s.Anchor
	}
	out := make([]float32, len(s.Anchor))
	for i := range out {
		out[i] = s.Positive.Vector[i] + float32(w)*s.Anchor[i]
	}
	index.Normalize(out)
	return out
}

// Shifted reports whether the listener is skipping away from the current music.
func (s *SessionState) Shifted() bool { return s != nil && s.SkipStreak >= 2 }
