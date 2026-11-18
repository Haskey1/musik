package api

import (
	"bytes"
	"context"
	"os"
	"os/exec"
	"path/filepath"
	"testing"
)

func TestRecapClipCreatesOnePlayableCachedExcerpt(t *testing.T) {
	ffmpeg, err := exec.LookPath("ffmpeg")
	if err != nil {
		t.Skip("ffmpeg unavailable")
	}
	dir := t.TempDir()
	source := filepath.Join(dir, "song.wav")
	output, err := exec.Command(ffmpeg, "-nostdin", "-v", "error", "-f", "lavfi", "-i",
		"sine=frequency=442:sample_rate=24000:duration=38", source).CombinedOutput()
	if err != nil {
		t.Fatalf("create fixture: %v, %s", err, output)
	}
	cache := filepath.Join(dir, "cache")
	first, err := recapClip(context.Background(), cache, source)
	if err != nil {
		t.Fatal(err)
	}
	replay, err := recapClip(context.Background(), cache, source)
	if err != nil || !bytes.Equal(first, replay) {
		t.Fatalf("clip differs on replay: %v", err)
	}
	entries, err := os.ReadDir(cache)
	if err != nil || len(entries) != 1 {
		t.Fatalf("expected one cached clip, got %v: %v", entries, err)
	}
	decoded, err := exec.Command(ffmpeg, "-nostdin", "-v", "error", "-i", filepath.Join(cache, entries[0].Name()),
		"-ac", "1", "-ar", "8000", "-f", "s16le", "pipe:1").Output()
	if err != nil || len(decoded) < 15*16000 || len(decoded) > 17*16000 {
		t.Fatalf("expected about 16 seconds of playable audio, got %d bytes: %v", len(decoded), err)
	}
}
