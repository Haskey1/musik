package taste

import (
	"math"
	"sync"
	"time"

	"github.com/torwin-job/musik/player/internal/index"
)

const (
	DefaultDaypartMinimum = 5
	maxNegativePrototypes = 8
	maxRecentPositive     = 8
)

type VectorState struct {
	Vector  []float32 `json:"vector,omitempty"`
	Samples int       `json:"samples"`
}

type NegativePrototype struct {
	Vector    []float32 `json:"vector"`
	Mass      float64   `json:"mass"`
	Samples   int       `json:"samples"`
	UpdatedAt time.Time `json:"updated_at"`
}

type SessionState struct {
	Positive       VectorState         `json:"positive"`
	Negative       []NegativePrototype `json:"negative,omitempty"`
	RecentPositive [][]float32         `json:"recent_positive,omitempty"`
	// Anchor is what the radio was started on (an artist or a track): it keeps
	// pulling the queue towards it for the first tracks and fades out (see
	// AnchorWeight), so "radio on Кино" stays Кино-like after the first song.
	Anchor []float32 `json:"anchor,omitempty"`
	// Plays counts the tracks heard (finished, partly or skipped) since the start.
	Plays int `json:"plays,omitempty"`
	// SkipStreak is the number of early skips in a row; any listened track resets it.
	SkipStreak int `json:"skip_streak,omitempty"`
	// SkipClusters counts early skips per cluster in this session: skipping a few
	// tracks of one cluster means "not this kind of music right now".
	SkipClusters map[int]int `json:"skip_clusters,omitempty"`
}

type State struct {
	mu         sync.RWMutex
	Long       *Profile
	Dayparts   map[string]VectorState
	Persistent []NegativePrototype
}

func NewState(long *Profile) *State {
	if long == nil {
		long = New()
	}
	return &State{Long: long, Dayparts: map[string]VectorState{}}
}

func ApplyPositive(state VectorState, vector []float32, weight, alpha float64) VectorState {
	return updatePositive(state, vector, weight, alpha)
}

func updatePositive(state VectorState, vector []float32, weight, alpha float64) VectorState {
	if len(vector) == 0 || weight <= 0 {
		return state
	}
	if alpha <= 0 || alpha > 1 {
		alpha = 0.1
	}
	if len(state.Vector) != len(vector) {
		state.Vector = append([]float32(nil), vector...)
	} else {
		a := float32(alpha)
		w := float32(math.Min(weight, 2))
		for i := range state.Vector {
			state.Vector[i] = (1-a)*state.Vector[i] + a*w*vector[i]
		}
	}
	index.Normalize(state.Vector)
	state.Samples++
	return state
}

func (s *State) UpdatePositive(
	vector []float32,
	weight, alpha float64,
	daypart string,
	session *SessionState,
) {
	s.UpdatePositiveScaled(vector, weight, alpha, 1, daypart, session)
}

// UpdatePositiveScaled is UpdatePositive where the long-term and daypart tastes
// learn longScale × weight while the session learns the full weight. A track the
// radio itself picked says less about the listener's taste than one they chose,
// but it is still a fair signal of the current mood.
func (s *State) UpdatePositiveScaled(
	vector []float32,
	weight, alpha, longScale float64,
	daypart string,
	session *SessionState,
) {
	if len(vector) == 0 || weight <= 0 {
		return
	}
	if longScale <= 0 || longScale > 1 {
		longScale = 1
	}
	s.Long.UpdateEMA(vector, weight*longScale, alpha)
	s.mu.Lock()
	s.Dayparts[daypart] = updatePositive(s.Dayparts[daypart], vector, weight*longScale, alpha)
	s.mu.Unlock()
	if session != nil {
		session.Positive = updatePositive(session.Positive, vector, weight, alpha)
		recent := append([]float32(nil), vector...)
		index.Normalize(recent)
		session.RecentPositive = append(session.RecentPositive, recent)
		if len(session.RecentPositive) > maxRecentPositive {
			session.RecentPositive = append([][]float32(nil),
				session.RecentPositive[len(session.RecentPositive)-maxRecentPositive:]...)
		}
	}
}

func addNegative(prototypes []NegativePrototype, vector []float32, weight float64) []NegativePrototype {
	if len(vector) == 0 || weight <= 0 {
		return prototypes
	}
	v := append([]float32(nil), vector...)
	index.Normalize(v)
	best, bestSimilarity := -1, float32(-1)
	for i := range prototypes {
		sim := dot(prototypes[i].Vector, v)
		if sim > bestSimilarity {
			best, bestSimilarity = i, sim
		}
	}
	now := time.Now().UTC()
	if best >= 0 && bestSimilarity >= 0.82 {
		p := &prototypes[best]
		total := p.Mass + weight
		for i := range p.Vector {
			p.Vector[i] = float32(
				(float64(p.Vector[i])*p.Mass + float64(v[i])*weight) / total,
			)
		}
		index.Normalize(p.Vector)
		p.Mass = total
		p.Samples++
		p.UpdatedAt = now
		return prototypes
	}
	prototypes = append(prototypes, NegativePrototype{
		Vector: v, Mass: weight, Samples: 1, UpdatedAt: now,
	})
	if len(prototypes) <= maxNegativePrototypes {
		return prototypes
	}
	lightest := 0
	for i := 1; i < len(prototypes); i++ {
		if prototypes[i].Mass < prototypes[lightest].Mass {
			lightest = i
		}
	}
	return append(prototypes[:lightest], prototypes[lightest+1:]...)
}

// AddEarlySkip affects only the current listening session.
func (s *State) AddEarlySkip(session *SessionState, vector []float32, weight float64) {
	if session == nil {
		return
	}
	session.Negative = addNegative(session.Negative, vector, weight)
}

// AddDislike records a persistent negative prototype without subtracting the
// track from the positive long/daypart/session centroids.
func (s *State) AddDislike(vector []float32, weight float64) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.Persistent = addNegative(s.Persistent, vector, weight)
	s.Long.mu.Lock()
	s.Long.NNegative++
	s.Long.mu.Unlock()
}

func (s *State) Effective(daypart string, session *SessionState, daypartMinimum int) []float32 {
	if daypartMinimum < 1 {
		daypartMinimum = DefaultDaypartMinimum
	}
	long := s.Long.Get()
	s.mu.RLock()
	dp := s.Dayparts[daypart]
	s.mu.RUnlock()
	if len(long) == 0 {
		if session != nil {
			return normalizedCopy(session.Positive.Vector)
		}
		return nil
	}
	// A run of skips and a fresh anchor both say "not what you usually listen to":
	// the long-term and daypart parts are turned down, the session parts stay.
	scale := 1.0
	if session != nil {
		scale = session.LongScale()
	}
	out := make([]float32, len(long))
	for i := range out {
		out[i] = float32(scale) * long[i]
	}
	total := float32(scale)
	if dp.Samples >= daypartMinimum && len(dp.Vector) == len(out) {
		for i := range out {
			out[i] += 0.4 * float32(scale) * dp.Vector[i]
		}
		total += 0.4 * float32(scale)
	}
	if session != nil && len(session.Positive.Vector) == len(out) {
		for i := range out {
			out[i] += 0.8 * session.Positive.Vector[i]
		}
		total += 0.8
	}
	// Recent completed tracks provide a small coherence term without becoming a
	// fourth persistent taste vector.
	if session != nil && len(session.RecentPositive) > 0 {
		recent := meanDirection(session.RecentPositive, len(out))
		if len(recent) == len(out) {
			for i := range out {
				out[i] += 0.25 * recent[i]
			}
			total += 0.25
		}
	}
	if session != nil {
		if w := float32(AnchorStrength * session.AnchorWeight()); w > 0 && len(session.Anchor) == len(out) {
			for i := range out {
				out[i] += w * session.Anchor[i]
			}
			total += w
		}
	}
	for i := range out {
		out[i] /= total
	}
	index.Normalize(out)
	if session != nil {
		rejectNegatives(out, session.Negative)
	}
	return out
}

// rejectNegatives takes what this session rejected out of the taste vector: the
// component along every negative prototype is removed, the more the more mass the
// prototype has (one skip barely, three skips of the same kind almost completely).
// Without this a taste made of one kind of music keeps asking for it however often
// it is skipped, because nothing else is left in the vector to ask for.
func rejectNegatives(vector []float32, prototypes []NegativePrototype) {
	if len(prototypes) == 0 {
		return
	}
	original := append([]float32(nil), vector...)
	for _, p := range prototypes {
		if len(p.Vector) != len(vector) {
			continue
		}
		gamma := math.Min(0.95, 0.05+0.12*p.Mass*p.Mass)
		if gamma <= 0.06 {
			continue
		}
		along := float64(dot(vector, p.Vector))
		if along <= 0 {
			continue
		}
		for i := range vector {
			vector[i] -= float32(gamma * along * float64(p.Vector[i]))
		}
	}
	var norm float64
	for _, x := range vector {
		norm += float64(x) * float64(x)
	}
	if norm < 1e-12 {
		copy(vector, original) // nothing is left: better the old taste than no direction
		return
	}
	index.Normalize(vector)
}

func (s *State) NegativePenalty(vector []float32, session *SessionState) float64 {
	s.mu.RLock()
	persistent := append([]NegativePrototype(nil), s.Persistent...)
	s.mu.RUnlock()
	all := persistent
	if session != nil {
		all = append(all, session.Negative...)
	}
	var penalty float64
	for _, prototype := range all {
		sim := float64(dot(prototype.Vector, vector))
		if sim <= 0 {
			continue
		}
		value := sim * math.Min(1, 0.25+math.Log1p(prototype.Mass)/2)
		if value > penalty {
			penalty = value
		}
	}
	return penalty
}

func (s *State) Daypart(daypart string) VectorState {
	s.mu.RLock()
	defer s.mu.RUnlock()
	state := s.Dayparts[daypart]
	state.Vector = append([]float32(nil), state.Vector...)
	return state
}

func (s *State) RestoreDaypart(daypart string, state VectorState) {
	state.Vector = normalizedCopy(state.Vector)
	s.mu.Lock()
	s.Dayparts[daypart] = state
	s.mu.Unlock()
}

func (s *State) RestorePersistentNegatives(prototypes []NegativePrototype) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.Persistent = append([]NegativePrototype(nil), prototypes...)
	for i := range s.Persistent {
		s.Persistent[i].Vector = normalizedCopy(s.Persistent[i].Vector)
	}
}

func (s *State) PersistentNegatives() []NegativePrototype {
	s.mu.RLock()
	defer s.mu.RUnlock()
	out := make([]NegativePrototype, len(s.Persistent))
	copy(out, s.Persistent)
	for i := range out {
		out[i].Vector = append([]float32(nil), out[i].Vector...)
	}
	return out
}

func normalizedCopy(vector []float32) []float32 {
	if len(vector) == 0 {
		return nil
	}
	out := append([]float32(nil), vector...)
	index.Normalize(out)
	return out
}

func meanDirection(vectors [][]float32, dim int) []float32 {
	if dim == 0 {
		return nil
	}
	out := make([]float32, dim)
	var count int
	for _, vector := range vectors {
		if len(vector) != dim {
			continue
		}
		for i := range out {
			out[i] += vector[i]
		}
		count++
	}
	if count == 0 {
		return nil
	}
	index.Normalize(out)
	return out
}

func dot(a, b []float32) float32 {
	if len(a) != len(b) {
		return 0
	}
	var sum float32
	for i := range a {
		sum += a[i] * b[i]
	}
	return sum
}
