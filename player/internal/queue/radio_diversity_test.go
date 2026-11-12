package queue

import (
	"fmt"
	"math/rand"
	"slices"
	"testing"
	"time"

	"github.com/torwin-job/musik/player/internal/config"
	"github.com/torwin-job/musik/player/internal/db"
	"github.com/torwin-job/musik/player/internal/index"
)

func TestSelectDiversifiesBothQuotaMinimumsAndRemainingSlots(t *testing.T) {
	for _, minimum := range []int{0, 6} {
		for _, sameArtist := range []bool{false, true} {
			t.Run(fmt.Sprintf("minimum_%d_same_artist_%v", minimum, sameArtist), func(t *testing.T) {
				idx := index.New(config.Config{})
				secondArtist := "B"
				thirdVector := []float32{0, 1}
				lambda := 0.7
				if sameArtist {
					secondArtist = " a " // canonical artist identity, not raw casing/spacing
					thirdVector = []float32{1, 0}
					lambda = 1
				}
				rows := []db.TrackRow{
					{ID: 1, Artist: "A", Title: "One", Embedding: index.Float32Bytes([]float32{1, 0}), Dim: 2},
					{ID: 2, Artist: secondArtist, Title: "Two", Embedding: index.Float32Bytes([]float32{1, 0}), Dim: 2},
					{ID: 3, Artist: "C", Title: "Three", Embedding: index.Float32Bytes(thirdVector), Dim: 2},
				}
				if err := idx.Load(rows); err != nil {
					t.Fatal(err)
				}
				cands := []ScoredCandidate{
					{Candidate: Candidate{Row: 0, TrackID: 1, Primary: SourceExploit}, Score: 1},
					{Candidate: Candidate{Row: 1, TrackID: 2, Primary: SourceExploit}, Score: .99},
					{Candidate: Candidate{Row: 2, TrackID: 3, Primary: SourceExploit}, Score: .9},
				}
				got := Select(cands, SelectOpts{Idx: idx, Size: 2, MMRLambda: lambda,
					Quotas: map[string]Quota{SourceExploit: {Min: minimum, Max: 6}}})
				if len(got) != 2 || got[0].TrackID != 1 || got[1].TrackID != 3 {
					t.Fatalf("selection lost diversity: %+v", got)
				}
			})
		}
	}
}

func discographyIndex(t *testing.T, singles int) *index.Index {
	t.Helper()
	rows := make([]db.TrackRow, 300+singles)
	now := time.Now().UTC().Format(time.RFC3339Nano)
	for i := range rows {
		artist := "Large"
		if i >= 300 {
			artist = fmt.Sprintf("Single %d", i)
		}
		rows[i] = db.TrackRow{ID: int64(i + 1), Artist: artist, Title: fmt.Sprintf("Track %d", i),
			CreatedAt: now, Embedding: index.Float32Bytes([]float32{1, 0}), Dim: 2}
	}
	idx := index.New(config.Config{})
	if err := idx.Load(rows); err != nil {
		t.Fatal(err)
	}
	return idx
}

func TestCandidateSourcesKeepSmallDiscographies(t *testing.T) {
	idx := discographyIndex(t, 60)
	score := make([]float32, idx.Size())
	edges := map[int64]float64{}
	for row := range score {
		score[row] = .3
		edges[int64(row+1)] = 1
	}
	allow := func(row int) bool { return row != 300 } // excluded single must not reappear on backfill
	tests := map[string]func() []int{
		"taste":      func() []int { return topArtistRows(idx, score, 48, allow) },
		"adjacent":   func() []int { return adjacentRows(idx, score, 48, allow) },
		"new":        func() []int { return newInLibraryRows(idx, time.Now(), 48, allow) },
		"wildcard":   func() []int { return wildcardRows(idx, nil, score, nil, 48, allow) },
		"transition": func() []int { return transitionRows(idx, nil, score, edges, 48, allow) },
	}
	for name, retrieve := range tests {
		t.Run(name, func(t *testing.T) {
			got := retrieve()
			singles := 0
			seen := map[int]bool{}
			for _, row := range got {
				if row == 300 || seen[row] {
					t.Fatalf("excluded/duplicate row %d", row)
				}
				seen[row] = true
				if row >= 300 {
					singles++
				}
			}
			if singles < 44 {
				t.Fatalf("large discography crowded out singles: %d of %d", singles, len(got))
			}
			if !slices.Equal(got, retrieve()) {
				t.Fatal("retrieval is not deterministic")
			}
		})
	}
}

func TestArtistBalancedRetrievalBackfillsSmallCatalog(t *testing.T) {
	idx := discographyIndex(t, 0)
	score := make([]float32, idx.Size())
	got := topArtistRows(idx, score, 48, func(row int) bool { return row >= 10 })
	if len(got) != 48 {
		t.Fatalf("single-artist source starved: %d", len(got))
	}
	for i, row := range got {
		if row != i+10 {
			t.Fatalf("backfill changed rank order or exclusions: %v", got)
		}
	}
}

func TestPickRandomBalancesArtistsInsteadOfTrackCounts(t *testing.T) {
	b := NewBuilder(discographyIndex(t, 1), config.Config{})
	b.Rng = rand.New(rand.NewSource(7))
	single := 0
	for i := 0; i < 2000; i++ {
		if b.PickRandom(nil) == 301 {
			single++
		}
	}
	if single < 850 || single > 1150 {
		t.Fatalf("300-track artist dominates one-track artist: single=%d/2000", single)
	}
	excluded := map[int64]bool{301: true}
	for i := 0; i < 20; i++ {
		if got := b.PickRandom(excluded); got == 301 || got == 0 {
			t.Fatalf("excluded/empty pick: %d", got)
		}
	}
}
