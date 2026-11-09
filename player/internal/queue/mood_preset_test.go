package queue

import (
	"testing"

	"github.com/torwin-job/musik/player/internal/index"
)

func TestMoodPresetScoreSeparatesEnergyAndCalm(t *testing.T) {
	energetic := index.Meta{BPM: 152, HasBPM: true, LUFS: -9, HasLUFS: true, KeyName: "8B"}
	calm := index.Meta{BPM: 72, HasBPM: true, LUFS: -22, HasLUFS: true, KeyName: "8A"}
	if MoodPresetScore(energetic, "energy") <= MoodPresetScore(calm, "energy") {
		t.Fatal("energy preset must prefer the faster, louder track")
	}
	if MoodPresetScore(calm, "calm") <= MoodPresetScore(energetic, "calm") {
		t.Fatal("calm preset must prefer the slower, quieter track")
	}
}

func TestMoodPresetScoreUsesModeForHappyAndSad(t *testing.T) {
	major := index.Meta{BPM: 110, HasBPM: true, LUFS: -16, HasLUFS: true, KeyName: "8B"}
	minor := index.Meta{BPM: 110, HasBPM: true, LUFS: -16, HasLUFS: true, KeyName: "8A"}
	if MoodPresetScore(major, "happy") <= MoodPresetScore(minor, "happy") {
		t.Fatal("happy preset must prefer a major key when energy is equal")
	}
	if MoodPresetScore(minor, "sad") <= MoodPresetScore(major, "sad") {
		t.Fatal("sad preset must prefer a minor key when energy is equal")
	}
}

func TestUnknownMoodPresetIsNeutral(t *testing.T) {
	meta := index.Meta{BPM: 150, HasBPM: true, LUFS: -8, HasLUFS: true}
	if got := MoodPresetScore(meta, "anything"); got != 0 {
		t.Fatalf("unknown preset score=%v, want 0", got)
	}
}

func TestMoodPresetDoesNotTreatFlatNoteAsCamelotMajor(t *testing.T) {
	flatNote := index.Meta{BPM: 110, HasBPM: true, LUFS: -16, HasLUFS: true, KeyName: "Ab"}
	unknown := index.Meta{BPM: 110, HasBPM: true, LUFS: -16, HasLUFS: true}
	if got, want := MoodPresetScore(flatNote, "happy"), MoodPresetScore(unknown, "happy"); got != want {
		t.Fatalf("flat note score=%v, want neutral mode score=%v", got, want)
	}
}
