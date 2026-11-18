package api

import (
	"context"
	"crypto/sha256"
	"encoding/binary"
	"fmt"
	"math"
	"math/cmplx"
	"os"
	"os/exec"
	"path/filepath"
	"time"

	"golang.org/x/sync/singleflight"
)

var recapAudioWork singleflight.Group
var recapAudioSlots = make(chan struct{}, 2)

const recapClipSeconds = 16
const recapSampleRate = 8000

func recapClip(ctx context.Context, dir, source string) ([]byte, error) {
	info, err := os.Stat(source)
	if err != nil {
		return nil, err
	}
	key := fmt.Sprintf("%x", sha256.Sum256(fmt.Appendf(nil, "v1|%s|%d|%d", source, info.Size(), info.ModTime().UnixNano())))
	target := filepath.Join(dir, key+".mp3")
	if data, err := os.ReadFile(target); err == nil {
		return data, nil
	}
	// The work belongs to the cache, so canceling one phone's request does not
	// stop generation for other listeners waiting on the same track.
	result := recapAudioWork.DoChan(target, func() (any, error) {
		if data, err := os.ReadFile(target); err == nil {
			return data, nil
		}
		work, cancel := context.WithTimeout(context.Background(), 35*time.Second)
		defer cancel()
		select {
		case recapAudioSlots <- struct{}{}:
			defer func() { <-recapAudioSlots }()
		case <-work.Done():
			return nil, work.Err()
		}
		pcm, err := exec.CommandContext(work, "ffmpeg", "-nostdin", "-v", "error", "-i", source,
			"-vn", "-t", "600", "-ac", "1", "-ar", "8000", "-f", "s16le", "pipe:1").Output()
		if err != nil {
			return nil, err
		}
		start := recapExcerptStart(pcm)
		clip, err := exec.CommandContext(work, "ffmpeg", "-nostdin", "-v", "error", "-ss", fmt.Sprint(start),
			"-i", source, "-vn", "-t", fmt.Sprint(recapClipSeconds), "-af",
			"afade=t=in:d=0.35,afade=t=out:st=15:d=1", "-ac", "1", "-b:a", "64k", "-f", "mp3", "pipe:1").Output()
		if err != nil {
			return nil, err
		}
		if err := os.MkdirAll(dir, 0700); err != nil {
			return nil, err
		}
		if err := recapWriteFile(target, clip); err != nil {
			return nil, err
		}
		return clip, nil
	})
	select {
	case <-ctx.Done():
		return nil, ctx.Err()
	case value := <-result:
		if value.Err != nil {
			return nil, value.Err
		}
		return value.Val.([]byte), nil
	}
}

func recapWriteFile(path string, data []byte) error {
	f, err := os.CreateTemp(filepath.Dir(path), ".recap-*")
	if err != nil {
		return err
	}
	defer os.Remove(f.Name())
	_, writeErr := f.Write(data)
	closeErr := f.Close()
	if writeErr != nil {
		return writeErr
	}
	if closeErr != nil {
		return closeErr
	}
	return os.Rename(f.Name(), path)
}

type recapFrame struct {
	chroma [12]float64
	energy float64
}

func recapExcerptStart(pcm []byte) int {
	seconds := len(pcm) / (2 * recapSampleRate)
	if seconds <= recapClipSeconds {
		return 0
	}
	frames := make([]recapFrame, seconds)
	peak := 0.0
	for second := range frames {
		frames[second] = recapFeatures(pcm[second*2*recapSampleRate:])
		peak = max(peak, frames[second].energy)
	}
	best, bestScore := 0, -1.0
	fallback, fallbackScore := 0, -1.0
	for start := 0; start+recapClipSeconds <= seconds; start++ {
		energy := 0.0
		for _, frame := range frames[start : start+recapClipSeconds] {
			energy += frame.energy
		}
		energy /= recapClipSeconds * max(peak, 1)
		// Give the body of the song preference when there is no clear repeat.
		position := float64(start) / float64(seconds-recapClipSeconds)
		emphasis := energy * (1 - 0.35*math.Abs(position-0.42))
		if emphasis > fallbackScore {
			fallback, fallbackScore = start, emphasis
		}
		if energy < 0.28 {
			continue
		}
		for other := start + 20; other+recapClipSeconds <= seconds; other++ {
			similarity := 0.0
			for offset := 0; offset < recapClipSeconds; offset += 2 {
				for pitch, weight := range frames[start+offset].chroma {
					similarity += weight * frames[other+offset].chroma[pitch]
				}
			}
			similarity /= recapClipSeconds / 2
			if similarity >= 0.83 {
				score := similarity * emphasis
				if score > bestScore {
					best, bestScore = start, score
				}
			}
		}
	}
	if bestScore < 0 {
		return fallback
	}
	return best
}

func recapFeatures(pcm []byte) recapFrame {
	const size = 2048
	var result recapFrame
	var bins [size]complex128
	for _, offset := range []int{0, 2900, 5900} {
		for i := range bins {
			at := (offset + i) * 2
			sample := float64(int16(binary.LittleEndian.Uint16(pcm[at:])))
			result.energy += sample * sample
			bins[i] = complex(sample*(0.5-0.5*math.Cos(2*math.Pi*float64(i)/size)), 0)
		}
		recapFFT(bins[:])
		for k := 16; k < 440; k++ {
			pitch := int(math.Round(69+12*math.Log2(float64(k)*recapSampleRate/(size*440.0)))) % 12
			result.chroma[pitch] += cmplx.Abs(bins[k])
		}
	}
	result.energy = math.Sqrt(result.energy / (3 * size))
	norm := 0.0
	mean := 0.0
	for _, weight := range result.chroma {
		mean += weight / 12
	}
	for i, weight := range result.chroma {
		result.chroma[i] = max(0, weight-mean)
		norm += result.chroma[i] * result.chroma[i]
	}
	if norm > 0 {
		for i := range result.chroma {
			result.chroma[i] /= math.Sqrt(norm)
		}
	}
	return result
}

func recapFFT(values []complex128) {
	for i, j := 1, 0; i < len(values); i++ {
		bit := len(values) >> 1
		for ; j&bit != 0; bit >>= 1 {
			j ^= bit
		}
		j ^= bit
		if i < j {
			values[i], values[j] = values[j], values[i]
		}
	}
	for width := 2; width <= len(values); width *= 2 {
		step := cmplx.Rect(1, -2*math.Pi/float64(width))
		for start := 0; start < len(values); start += width {
			phase := complex(1, 0)
			for i := 0; i < width/2; i++ {
				left, right := start+i, start+i+width/2
				a, b := values[left], values[right]*phase
				values[left], values[right] = a+b, a-b
				phase *= step
			}
		}
	}
}
