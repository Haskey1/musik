package playback

import "testing"

func TestNormalizeMoodPreset(t *testing.T) {
	cases := map[string]string{
		"Бодрое":    "energy",
		"Спокойное": "calm",
		"Весёлое":   "happy",
		"Грустное":  "sad",
		"unknown":   "",
	}
	for input, want := range cases {
		if got := normalizeMoodPreset(input); got != want {
			t.Errorf("normalizeMoodPreset(%q)=%q, want %q", input, got, want)
		}
	}
}
