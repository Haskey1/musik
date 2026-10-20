import Accelerate
import AVFoundation
import Foundation
import MediaToolbox
import os

/// Six-band equalizer for AVPlayer.
///
/// AVPlayer has no EQ of its own, so each player item gets an audio processing tap
/// (`MTAudioProcessingTap`): the decoded audio passes through a cascade of biquad
/// filters (vDSP) before it reaches the output. Works for files on the phone and for
/// the HTTP stream alike. Settings are kept in UserDefaults.
@MainActor
final class Equalizer: ObservableObject {
    static let shared = Equalizer()

    /// Band centres, Hz — the same as Yandex Music's equalizer.
    nonisolated static let bands: [Double] = [60, 150, 400, 1_000, 2_400, 15_000]
    nonisolated static let range: ClosedRange<Double> = -12...12

    struct Preset: Identifiable, Hashable {
        let id: String
        let name: String
        let gains: [Double]
    }

    static let custom = "custom"
    static let presets: [Preset] = [
        Preset(id: "flat", name: "По умолчанию", gains: [0, 0, 0, 0, 0, 0]),
        Preset(id: "classical", name: "Классическая музыка", gains: [3, 2, 0, 0, 2, 4]),
        Preset(id: "club", name: "Клубная музыка", gains: [4, 3, 1, 0, 2, 3]),
        Preset(id: "dance", name: "Танцевальная музыка", gains: [6, 4, 0, -1, 2, 3]),
        Preset(id: "bass", name: "Усиление НЧ", gains: [7, 5, 2, 0, 0, 0]),
        Preset(id: "bassTreble", name: "Усиление НЧ и ВЧ", gains: [6, 4, 0, -1, 3, 6]),
        Preset(id: "treble", name: "Усиление ВЧ", gains: [0, 0, 0, 1, 4, 7]),
        Preset(id: "laptop", name: "Колонки ноутбука", gains: [5, 4, 1, 0, 2, 3]),
        Preset(id: "headphones", name: "Наушники", gains: [3, 2, -1, 0, 2, 4]),
        Preset(id: "vocal", name: "Вокал", gains: [-2, -1, 2, 4, 3, 0]),
        Preset(id: "rock", name: "Рок", gains: [4, 2, -2, -1, 2, 4]),
        Preset(id: "hiphop", name: "Хип-хоп", gains: [6, 4, 0, 1, -1, 2]),
    ]

    @Published var enabled: Bool { didSet { save() } }
    @Published private(set) var gains: [Double]
    @Published private(set) var presetId: String

    private let defaults = UserDefaults.standard

    private init() {
        enabled = defaults.object(forKey: "eq.enabled") as? Bool ?? false
        let stored = defaults.array(forKey: "eq.gains") as? [Double] ?? []
        gains = stored.count == Self.bands.count ? stored : Array(repeating: 0, count: Self.bands.count)
        presetId = defaults.string(forKey: "eq.preset") ?? "flat"
        EQState.shared.update(enabled: enabled, gains: gains)
    }

    func apply(_ preset: Preset) {
        gains = preset.gains
        presetId = preset.id
        if !enabled { enabled = true } else { save() }
    }

    /// Dragging a point: the setting becomes «Своя настройка».
    func setGain(_ value: Double, band: Int) {
        guard gains.indices.contains(band) else { return }
        let v = (value * 2).rounded() / 2   // half-dB steps
        guard v != gains[band] else { return }
        gains[band] = min(max(v, Self.range.lowerBound), Self.range.upperBound)
        presetId = Self.custom
        if !enabled { enabled = true } else { save() }
    }

    private func save() {
        defaults.set(enabled, forKey: "eq.enabled")
        defaults.set(gains, forKey: "eq.gains")
        defaults.set(presetId, forKey: "eq.preset")
        EQState.shared.update(enabled: enabled, gains: gains)
    }

    // MARK: Attaching to playback

    /// Adds the processing tap to a player item. The audio track of a stream is known
    /// only after the asset loads, so this finishes asynchronously; the tap is always
    /// there and simply passes audio through while the equalizer is off.
    nonisolated static func attach(to item: AVPlayerItem) {
        let asset = item.asset
        Task {
            guard let track = try? await asset.loadTracks(withMediaType: .audio).first,
                  let tap = makeTap() else { return }
            let params = AVMutableAudioMixInputParameters(track: track)
            params.audioTapProcessor = tap
            let mix = AVMutableAudioMix()
            mix.inputParameters = [params]
            await MainActor.run { item.audioMix = mix }
        }
    }

    nonisolated private static func makeTap() -> MTAudioProcessingTap? {
        let context = TapContext()
        var callbacks = MTAudioProcessingTapCallbacks(
            version: Int32(kMTAudioProcessingTapCallbacksVersion_0),
            clientInfo: UnsafeMutableRawPointer(Unmanaged.passRetained(context).toOpaque()),
            init: tapInit,
            finalize: tapFinalize,
            prepare: tapPrepare,
            unprepare: tapUnprepare,
            process: tapProcess
        )
        var tap: MTAudioProcessingTap?
        let status = MTAudioProcessingTapCreate(kCFAllocatorDefault, &callbacks,
                                                MTAudioProcessingTapCreationFlags(kMTAudioProcessingTapCreationFlag_PostEffects),
                                                &tap)
        if status != noErr {
            Unmanaged<TapContext>.fromOpaque(callbacks.clientInfo!).release()
            return nil
        }
        return tap
    }
}

// MARK: - Shared state read by the audio thread

/// Gains as the audio thread sees them, behind a lock; `version` tells taps to rebuild
/// their filters.
final class EQState: @unchecked Sendable {
    static let shared = EQState()

    private var lock = os_unfair_lock()
    private var enabled = false
    private var gains: [Double] = Array(repeating: 0, count: Equalizer.bands.count)
    private var version = 0

    func update(enabled: Bool, gains: [Double]) {
        os_unfair_lock_lock(&lock)
        self.enabled = enabled
        self.gains = gains
        version += 1
        os_unfair_lock_unlock(&lock)
    }

    func snapshot() -> (enabled: Bool, gains: [Double], version: Int) {
        os_unfair_lock_lock(&lock)
        defer { os_unfair_lock_unlock(&lock) }
        return (enabled, gains, version)
    }
}

// MARK: - Tap

/// Per-item filter state: one cascade of six biquads per channel.
final class TapContext {
    var sampleRate: Double = 44_100
    var channels = 2
    var supported = false
    var version = -1
    var active = false
    var setup: vDSP_biquad_Setup?
    var delays: [[Float]] = []
    var preamp: Float = 1

    deinit {
        if let setup { vDSP_biquad_DestroySetup(setup) }
    }

    func rebuildIfNeeded() {
        let state = EQState.shared.snapshot()
        guard state.version != version else { return }
        version = state.version
        active = state.enabled && state.gains.contains { abs($0) >= 0.25 }
        if let setup { vDSP_biquad_DestroySetup(setup) }
        setup = nil
        guard active else { return }
        var coefficients: [Double] = []
        for (i, freq) in Equalizer.bands.enumerated() {
            let f = min(freq, sampleRate * 0.45)
            let kind: Biquad.Kind = i == 0 ? .lowShelf : (i == Equalizer.bands.count - 1 ? .highShelf : .peak)
            coefficients += Biquad.coefficients(kind, frequency: f, gainDB: state.gains[i],
                                                q: 1.0, sampleRate: sampleRate)
        }
        setup = vDSP_biquad_CreateSetup(coefficients, vDSP_Length(Equalizer.bands.count))
        delays = Array(repeating: Array(repeating: 0, count: 2 * Equalizer.bands.count + 2), count: channels)
        // leave headroom for boosts so loud tracks do not clip
        let boost = max(state.gains.max() ?? 0, 0)
        preamp = Float(pow(10, -boost / 20))
    }

    func process(_ list: UnsafeMutablePointer<AudioBufferList>, frames: Int) {
        guard supported else { return }
        rebuildIfNeeded()
        guard active, let setup else { return }
        let buffers = UnsafeMutableAudioBufferListPointer(list)
        for (ch, buffer) in buffers.enumerated() where ch < delays.count {
            guard let data = buffer.mData?.assumingMemoryBound(to: Float.self) else { continue }
            let n = vDSP_Length(min(frames, Int(buffer.mDataByteSize) / MemoryLayout<Float>.size))
            var gain = preamp
            vDSP_vsmul(data, 1, &gain, data, 1, n)
            delays[ch].withUnsafeMutableBufferPointer { delay in
                vDSP_biquad(setup, delay.baseAddress!, data, 1, data, 1, n)
            }
        }
    }
}

private let tapInit: MTAudioProcessingTapInitCallback = { _, clientInfo, storageOut in
    storageOut.pointee = clientInfo
}

private let tapFinalize: MTAudioProcessingTapFinalizeCallback = { tap in
    Unmanaged<TapContext>.fromOpaque(MTAudioProcessingTapGetStorage(tap)).release()
}

private let tapPrepare: MTAudioProcessingTapPrepareCallback = { tap, _, format in
    let ctx = Unmanaged<TapContext>.fromOpaque(MTAudioProcessingTapGetStorage(tap)).takeUnretainedValue()
    let f = format.pointee
    ctx.sampleRate = f.mSampleRate
    ctx.channels = Int(f.mChannelsPerFrame)
    // AVPlayer hands taps 32-bit float, one buffer per channel; anything else passes untouched
    ctx.supported = f.mFormatID == kAudioFormatLinearPCM
        && f.mFormatFlags & kAudioFormatFlagIsFloat != 0
        && f.mFormatFlags & kAudioFormatFlagIsNonInterleaved != 0
        && f.mBitsPerChannel == 32
    ctx.version = -1
}

private let tapUnprepare: MTAudioProcessingTapUnprepareCallback = { _ in }

private let tapProcess: MTAudioProcessingTapProcessCallback = { tap, frames, _, bufferList, framesOut, flagsOut in
    let status = MTAudioProcessingTapGetSourceAudio(tap, frames, bufferList, flagsOut, nil, framesOut)
    guard status == noErr else { return }
    let ctx = Unmanaged<TapContext>.fromOpaque(MTAudioProcessingTapGetStorage(tap)).takeUnretainedValue()
    ctx.process(bufferList, frames: Int(framesOut.pointee))
}

// MARK: - Filter maths

/// RBJ "Audio EQ Cookbook" biquads, normalised for vDSP: [b0, b1, b2, a1, a2].
enum Biquad {
    enum Kind { case peak, lowShelf, highShelf }

    static func coefficients(_ kind: Kind, frequency: Double, gainDB: Double, q: Double,
                             sampleRate: Double) -> [Double] {
        let a = pow(10, gainDB / 40)
        let w0 = 2 * Double.pi * frequency / sampleRate
        let cosw = cos(w0), sinw = sin(w0)
        var b0, b1, b2, a0, a1, a2: Double
        switch kind {
        case .peak:
            let alpha = sinw / (2 * q)
            b0 = 1 + alpha * a; b1 = -2 * cosw; b2 = 1 - alpha * a
            a0 = 1 + alpha / a; a1 = -2 * cosw; a2 = 1 - alpha / a
        case .lowShelf:
            let alpha = sinw / 2 * sqrt(2)   // shelf slope S = 1
            let s = 2 * sqrt(a) * alpha
            b0 = a * ((a + 1) - (a - 1) * cosw + s)
            b1 = 2 * a * ((a - 1) - (a + 1) * cosw)
            b2 = a * ((a + 1) - (a - 1) * cosw - s)
            a0 = (a + 1) + (a - 1) * cosw + s
            a1 = -2 * ((a - 1) + (a + 1) * cosw)
            a2 = (a + 1) + (a - 1) * cosw - s
        case .highShelf:
            let alpha = sinw / 2 * sqrt(2)
            let s = 2 * sqrt(a) * alpha
            b0 = a * ((a + 1) + (a - 1) * cosw + s)
            b1 = -2 * a * ((a - 1) + (a + 1) * cosw)
            b2 = a * ((a + 1) + (a - 1) * cosw - s)
            a0 = (a + 1) - (a - 1) * cosw + s
            a1 = 2 * ((a - 1) - (a + 1) * cosw)
            a2 = (a + 1) - (a - 1) * cosw - s
        }
        return [b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0]
    }

    /// Gain in dB of the whole cascade at `frequency` — for drawing the curve.
    static func response(gains: [Double], at frequency: Double, sampleRate: Double = 44_100) -> Double {
        var total = 0.0
        for (i, f0) in Equalizer.bands.enumerated() {
            let kind: Kind = i == 0 ? .lowShelf : (i == Equalizer.bands.count - 1 ? .highShelf : .peak)
            let c = coefficients(kind, frequency: f0, gainDB: gains[i], q: 1, sampleRate: sampleRate)
            let w = 2 * Double.pi * frequency / sampleRate
            // |H(e^jw)|² from the numerator and denominator polynomials
            func mag2(_ p0: Double, _ p1: Double, _ p2: Double) -> Double {
                let re = p0 + p1 * cos(w) + p2 * cos(2 * w)
                let im = -(p1 * sin(w) + p2 * sin(2 * w))
                return re * re + im * im
            }
            total += 10 * log10(mag2(c[0], c[1], c[2]) / mag2(1, c[3], c[4]))
        }
        return total
    }
}
