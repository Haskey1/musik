package queue

import (
	"math/rand"
	"sync"
	"time"

	"github.com/torwin-job/musik/player/internal/config"
	"github.com/torwin-job/musik/player/internal/index"
)

type Item struct {
	ImpressionID string  `json:"impression_id,omitempty"`
	RequestID    string  `json:"request_id,omitempty"`
	Source       string  `json:"source,omitempty"`
	FeaturesJSON string  `json:"features_json,omitempty"`
	TrackID      int64   `json:"track_id"`
	Artist       string  `json:"artist"`
	Title        string  `json:"title"`
	Album        string  `json:"album,omitempty"`
	Path         string  `json:"path"`
	Duration     float64 `json:"duration"`
	Score        float64 `json:"score"`
	CosineTaste  float64 `json:"cosine_taste"`
	CosineCur    float64 `json:"cosine_current"`
	Explanation  string  `json:"explanation"`
	Explore      bool    `json:"explore"`
	NewBoost     bool    `json:"new_boost"`
	ClusterID    int     `json:"cluster_id,omitempty"`
}

type Builder struct {
	Idx   *index.Index
	Cfg   config.Config
	Rng   *rand.Rand
	rngMu sync.Mutex
}

func NewBuilder(idx *index.Index, cfg config.Config) *Builder {
	return &Builder{Idx: idx, Cfg: cfg, Rng: rand.New(rand.NewSource(time.Now().UnixNano()))}
}

func (b *Builder) newRNG() *rand.Rand {
	b.rngMu.Lock()
	seed := b.Rng.Int63()
	b.rngMu.Unlock()
	return rand.New(rand.NewSource(seed))
}

func (b *Builder) ForkRNG() *rand.Rand {
	return b.newRNG()
}

func (b *Builder) RandomFloat64() float64 {
	return b.newRNG().Float64()
}

// PickRandom chooses an eligible artist uniformly, then one of their tracks.
// A large discography must not dominate cold starts and random refills.
// It returns 0 when the catalog is empty or every track is excluded.
// Callers stop playback or explicitly relax the exclude; this function does not.
func (b *Builder) PickRandom(exclude map[int64]bool) int64 {
	n := b.Idx.Size()
	if n == 0 {
		return 0
	}
	artists := make([]string, 0)
	byArtist := map[string][]int64{}
	for i := 0; i < n; i++ {
		meta := b.Idx.MetaAt(i)
		if exclude[meta.ID] {
			continue
		}
		key := index.ArtistKey(meta.Artist)
		if key == "" {
			key = "\x00" + index.TrackKey(meta.ID)
		}
		if _, ok := byArtist[key]; !ok {
			artists = append(artists, key)
		}
		byArtist[key] = append(byArtist[key], meta.ID)
	}
	if len(artists) == 0 {
		return 0
	}
	rng := b.newRNG()
	tracks := byArtist[artists[rng.Intn(len(artists))]]
	return tracks[rng.Intn(len(tracks))]
}
