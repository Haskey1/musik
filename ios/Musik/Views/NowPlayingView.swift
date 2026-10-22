import SwiftUI

struct NowPlayingView: View {
    enum Pane: String, CaseIterable, Identifiable {
        case queue = "Дальше"
        case lyrics = "Текст"
        var id: String { rawValue }
    }

    @EnvironmentObject var app: AppState
    @EnvironmentObject var player: PlayerController
    @EnvironmentObject var offline: OfflineStore
    @Environment(\.dismiss) private var dismiss

    @State private var pane: Pane = .queue
    @State private var showEqualizer = false
    /// A sideways swipe on the cover is in progress: no scrolling, no closing meanwhile.
    @State private var swiping = false
    @ObservedObject private var eq = Equalizer.shared

    var body: some View {
        ZStack {
            CoverBackdrop(trackId: player.current?.id)
            if let track = player.current {
                ScrollViewReader { scroller in
                ScrollView {
                    VStack(spacing: 22) {
                        topBar
                        ArtworkView(trackId: track.id, size: 640, fallback: track.displayTitle, cornerRadius: 22)
                            .frame(maxWidth: 340)
                            .shadow(color: .black.opacity(0.55), radius: 28, y: 14)
                            .scaleEffect(player.isPlaying ? 1 : 0.94)
                            .animation(.spring(response: 0.4, dampingFraction: 0.8), value: player.isPlaying)
                            .swipeToSkip(threshold: 90) { swiping = $0 }
                            .padding(.horizontal, 24)
                        titleBlock(track)
                        SeekBar()
                        transport
                        actions(track) {
                            // «Текст» swaps the queue below for the lyrics and scrolls to them
                            withAnimation(.easeInOut(duration: 0.25)) {
                                pane = pane == .lyrics ? .queue : .lyrics
                            }
                            if pane == .lyrics {
                                DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) {
                                    withAnimation(.easeInOut(duration: 0.35)) {
                                        scroller.scrollTo("pane", anchor: .top)
                                    }
                                }
                            }
                        }
                        Group {
                            switch pane {
                            case .queue: upNext
                            case .lyrics: LyricsView(trackId: track.id)
                            }
                        }
                        .id("pane")
                        // quiet, at the very bottom — as in Yandex Music
                        HStack {
                            Spacer()
                            Button { showEqualizer = true } label: {
                                Image(systemName: "slider.horizontal.3")
                                    .font(.title3)
                                    .foregroundStyle(eq.enabled ? Color.white : Color.white.opacity(0.7))
                                    .frame(width: 64, height: 48)
                                    .background(Color.white.opacity(eq.enabled ? 0.28 : 0.12), in: Capsule())
                            }
                            .accessibilityLabel("Эквалайзер")
                        }
                        .padding(.horizontal, 20)
                        .padding(.top, 8)
                    }
                    .padding(.bottom, 32)
                }
                .scrollDisabled(swiping)
                }
            } else {
                VStack(spacing: 12) {
                    Text("Ничего не играет").font(.title3.weight(.semibold))
                    Button("Включить радио") {
                        Haptics.press()
                        player.startRadio()
                    }
                        .buttonStyle(PrimaryButtonStyle())
                }
            }
        }
        .tint(.white)
        .overlay(alignment: .top) { ToastView() }
        .sheet(isPresented: $showEqualizer) { EqualizerView() }
        .interactiveDismissDisabled(swiping)
    }

    // MARK: Parts

    private var topBar: some View {
        HStack {
            Button { dismiss() } label: {
                Image(systemName: "chevron.down").font(.title3.weight(.semibold))
            }
            Spacer()
            if let track = player.current {
                if let url = app.shareURL(track: track) {
                    SwiftUI.ShareLink(item: url) {
                        Image(systemName: "square.and.arrow.up").font(.title3)
                    }
                    .padding(.trailing, 14)
                    .accessibilityLabel("Поделиться")
                }
                Menu {
                    Button { showEqualizer = true } label: {
                        Label("Эквалайзер", systemImage: "slider.horizontal.3")
                    }
                    Button(role: .destructive) { player.hide(.track) } label: {
                        Label("Скрыть этот трек", systemImage: "eye.slash")
                    }
                    if let artist = track.artist.nonEmpty {
                        Button(role: .destructive) { player.hide(.artist) } label: {
                            Label("Скрыть артиста «\(artist)»", systemImage: "person.crop.circle.badge.xmark")
                        }
                    }
                } label: {
                    Image(systemName: "ellipsis.circle").font(.title3)
                }
                .accessibilityLabel("Не предлагать")
            } else {
                Color.clear.frame(width: 24, height: 24)
            }
        }
        .padding(.horizontal, 20)
        .padding(.top, 16)
        // the title sits in the middle of the screen whatever the buttons on the sides are
        .overlay(alignment: .center) {
            VStack(spacing: 2) {
                Text(player.listName ?? (player.fixed ? "Плейлист" : "Радио"))
                    .font(.subheadline.weight(.semibold))
                    .lineLimit(1)
                if player.offlineMode {
                    Label("офлайн · с телефона", systemImage: "wifi.slash")
                        .font(.caption2)
                        .foregroundStyle(Color.white)
                } else {
                    Text(Labels.maturity(player.maturity))
                        .font(.caption2)
                        .foregroundStyle(Color.white.opacity(0.8))
                        .lineLimit(1)
                }
            }
            .padding(.top, 16)
            .padding(.horizontal, 110)
        }
    }

    private func titleBlock(_ track: Track) -> some View {
        VStack(spacing: 6) {
            Text(track.displayTitle)
                .font(.title2.weight(.bold))
                .multilineTextAlignment(.center)
                .lineLimit(2)
            // artist and album open their pages
            HStack(spacing: 6) {
                if let artist = track.artist.nonEmpty {
                    Button { app.pendingRoute = .artist(artist) } label: {
                        Text(artist).underline(false)
                    }
                }
                if track.artist.nonEmpty != nil, track.album.nonEmpty != nil {
                    Text("·")
                }
                if let album = track.album.nonEmpty {
                    Button { app.pendingRoute = .album(artist: track.artist, album: album) } label: {
                        Text(album)
                    }
                }
            }
            .buttonStyle(.plain)
            .font(.subheadline)
            .foregroundStyle(Color.white.opacity(0.7))
            .lineLimit(1)
            .padding(.horizontal, 8)
            TrackInfoLine(trackId: track.id)
            if let source = Labels.source(track.source) {
                Text(source)
                    .font(.caption2.weight(.semibold))
                    .padding(.horizontal, 8).padding(.vertical, 3)
                    .background(Color.white.opacity(0.15), in: Capsule())
                    .foregroundStyle(Color.white.opacity(0.8))
            }
        }
        .padding(.horizontal, 24)
    }


    private var transport: some View {
        HStack(spacing: 44) {
            Button {
                Haptics.tap()
                player.back()
            } label: {
                Image(systemName: "backward.fill").font(.title)
            }
            Button {
                Haptics.press()
                player.togglePlay()
            } label: {
                ZStack {
                    Circle()
                        .fill(Color.white)
                        .frame(width: 76, height: 76)
                        .shadow(color: .black.opacity(0.18), radius: 10, y: 4)
                    if player.isBuffering || player.busy {
                        ProgressView().tint(.black)
                    } else {
                        Image(systemName: player.isPlaying ? "pause.fill" : "play.fill")
                            .font(.system(size: 30, weight: .bold))
                            .foregroundStyle(.black.opacity(0.85))
                    }
                }
            }
            Button {
                Haptics.tap()
                player.skip()
            } label: {
                Image(systemName: "forward.fill").font(.title)
            }
            .disabled(player.busy)
        }
        .foregroundStyle(.primary)
    }

    /// Two rows under the transport: rate / save, then explore.
    private func actions(_ track: Track, onLyrics: @escaping () -> Void) -> some View {
        VStack(spacing: 10) {
            HStack(spacing: 10) {
                // a second tap takes the dislike back
                Button {
                    Haptics.tap()
                    player.disliked ? player.undislike() : player.dislike()
                } label: {
                    Image(systemName: player.disliked ? "heart.slash.fill" : "heart.slash")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(GlassChipStyle(active: player.disliked))
                .accessibilityLabel(player.disliked ? "Снять дизлайк" : "Дизлайк")

                Button {
                    if offline.isSaved(track.id) { offline.remove(track.id) } else { offline.save(track) }
                } label: {
                    Group {
                        if offline.downloading.contains(track.id) {
                            ProgressView().controlSize(.small)
                        } else {
                            Image(systemName: offline.isSaved(track.id) ? "arrow.down.circle.fill" : "arrow.down.circle")
                        }
                    }
                    .frame(maxWidth: .infinity)
                }
                .buttonStyle(GlassChipStyle(active: offline.isSaved(track.id)))
                .accessibilityLabel("Сохранить на телефон")

                LikeChip()
            }
            HStack(spacing: 10) {
                Button { player.playSimilarNow() } label: {
                    Label("Похожее", systemImage: "sparkles").frame(maxWidth: .infinity)
                }
                .buttonStyle(GlassChipStyle())

                Button(action: onLyrics) {
                    Label("Текст", systemImage: "quote.bubble").frame(maxWidth: .infinity)
                }
                .buttonStyle(GlassChipStyle(active: pane == .lyrics))

                Button {
                    Haptics.press()
                    player.startRadio(seed: track.id)
                } label: {
                    Label("Радио", systemImage: "dot.radiowaves.left.and.right").frame(maxWidth: .infinity)
                }
                .buttonStyle(GlassChipStyle())
            }
        }
        .labelStyle(.titleAndIcon)
        .padding(.horizontal, 20)
    }

    @ViewBuilder
    private var upNext: some View {
        LazyVStack(alignment: .leading, spacing: 14) {
            if player.fixed {
                Text("В этом списке · \(player.playlist.count)")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(Color.white.opacity(0.7))
                ForEach(Array(player.playlist.enumerated()), id: \.offset) { i, t in
                    TrackRow(track: t, index: i) {
                        player.jump(trackId: t.id, index: t.position ?? i)
                    }
                }
            } else {
                Text("Дальше в радио")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(Color.white.opacity(0.7))
                ForEach(Array(player.queue.enumerated()), id: \.offset) { i, item in
                    QueueRow(item: item) {
                        player.jump(trackId: item.trackId, index: i)
                    }
                }
                if player.queue.isEmpty {
                    EmptyHint(text: "Очередь пополнится после следующего трека")
                }
            }
        }
        .padding(.horizontal, 20)
    }
}

private struct QueueRow: View {
    let item: QueueItem
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                ArtworkView(trackId: item.trackId, size: 96, fallback: item.title ?? "♪", cornerRadius: 6)
                    .frame(width: 44, height: 44)
                VStack(alignment: .leading, spacing: 2) {
                    Text(item.title.nonEmpty ?? "#\(item.trackId)").lineLimit(1)
                    HStack(spacing: 6) {
                        Text(item.artist ?? "").lineLimit(1)
                        if let why = Labels.why(item) {
                            Text(why).italic().foregroundStyle(Color.white.opacity(0.8)).lineLimit(1)
                        }
                    }
                    .font(.caption)
                    .foregroundStyle(Color.white.opacity(0.7))
                }
                Spacer(minLength: 8)
                Text(formatTime(item.duration))
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(Color.white.opacity(0.7))
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// The seek bar: redraws with the clock without touching the rest of the screen.
/// A tap anywhere on the line jumps there; dragging scrubs, the seek happens on release.
private struct SeekBar: View {
    @EnvironmentObject var player: PlayerController
    @EnvironmentObject var clock: PlaybackClock
    @State private var scrubbing = false
    @State private var scrub: Double = 0

    var body: some View {
        let total = max(clock.duration, 1)
        let value = scrubbing ? scrub : min(clock.position, total)
        VStack(spacing: 4) {
            GeometryReader { geo in
                let width = max(geo.size.width, 1)
                let x = CGFloat(value / total) * width
                ZStack(alignment: .leading) {
                    Capsule().fill(Color.white.opacity(0.25)).frame(height: scrubbing ? 8 : 5)
                    Capsule().fill(Color.white).frame(width: max(x, 0), height: scrubbing ? 8 : 5)
                    Circle()
                        .fill(Color.white)
                        .frame(width: scrubbing ? 22 : 14, height: scrubbing ? 22 : 14)
                        .shadow(color: .black.opacity(0.3), radius: 3)
                        .offset(x: min(max(x - (scrubbing ? 11 : 7), -4), width - 10))
                }
                .frame(maxHeight: .infinity)
                .contentShape(Rectangle())
                .gesture(
                    DragGesture(minimumDistance: 0)
                        .onChanged { g in
                            scrub = Double(min(max(g.location.x / width, 0), 1)) * total
                            if !scrubbing { withAnimation(.easeOut(duration: 0.15)) { scrubbing = true } }
                        }
                        .onEnded { g in
                            let target = Double(min(max(g.location.x / width, 0), 1)) * total
                            scrub = target
                            player.seek(to: target)
                            withAnimation(.easeOut(duration: 0.2)) { scrubbing = false }
                        }
                )
            }
            .frame(height: 28)
            .animation(.easeOut(duration: 0.15), value: scrubbing)
            HStack {
                Text(formatTime(scrubbing ? scrub : clock.position))
                Spacer()
                Text(formatTime(clock.duration))
            }
            .font(.caption.monospacedDigit())
            .foregroundStyle(Color.white.opacity(0.7))
        }
        .padding(.horizontal, 24)
    }
}

/// Plain or time-synced (LRC) lyrics; the synced line under the playhead is highlighted.
struct LyricsView: View {
    @EnvironmentObject var app: AppState
    @EnvironmentObject var player: PlayerController
    @EnvironmentObject var clock: PlaybackClock
    let trackId: Int

    @State private var lyrics: Lyrics?
    @State private var lines: [(time: Double, text: String)] = []
    @State private var loading = true

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            if loading {
                ProgressView().frame(maxWidth: .infinity)
            } else if !lines.isEmpty {
                let active = activeIndex
                ForEach(Array(lines.enumerated()), id: \.offset) { i, line in
                    Text(line.text.isEmpty ? "♪" : line.text)
                        .font(.title3.weight(i == active ? .bold : .regular))
                        .foregroundStyle(i == active ? Color.white : Color.white.opacity(0.7))
                        .onTapGesture { player.seek(to: line.time) }
                }
            } else if let plain = lyrics?.plainLyrics.nonEmpty {
                Text(plain).font(.body).foregroundStyle(Color.primary.opacity(0.9))
            } else {
                EmptyHint(text: lyrics?.status == "instrumental" ? "Инструментал" : "Текста нет")
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 24)
        .task(id: trackId) { await load() }
    }

    private var activeIndex: Int? {
        lines.lastIndex { $0.time <= clock.position + 0.3 }
    }

    private func load() async {
        loading = true
        lyrics = try? await app.api.lyrics(trackId: trackId)
        lines = Self.parseLRC(lyrics?.syncedLyrics ?? "")
        loading = false
    }

    static func parseLRC(_ text: String) -> [(time: Double, text: String)] {
        guard let re = try? NSRegularExpression(pattern: #"\[(\d+):(\d+(?:\.\d+)?)\]"#) else { return [] }
        var out: [(time: Double, text: String)] = []
        for raw in text.components(separatedBy: .newlines) {
            let ns = raw as NSString
            let matches = re.matches(in: raw, range: NSRange(location: 0, length: ns.length))
            guard let last = matches.last else { continue }
            let body = ns.substring(from: last.range.location + last.range.length)
                .trimmingCharacters(in: .whitespaces)
            for m in matches {
                let minutes = Double(ns.substring(with: m.range(at: 1))) ?? 0
                let sec = Double(ns.substring(with: m.range(at: 2))) ?? 0
                out.append((minutes * 60 + sec, body))
            }
        }
        return out.sorted { $0.time < $1.time }
    }
}


/// Genre and file quality under the artist, faint: «Рэп · FLAC · 44,1 кГц».
/// Comes from the track's passport on the server; empty while loading or offline.
private struct TrackInfoLine: View {
    @EnvironmentObject var app: AppState
    let trackId: Int
    @State private var text = ""

    private struct Detail: Decodable { var passport: Passport? }
    private struct Passport: Decodable {
        var format: String?
        var bitrate: Int?
        var sampleRate: Int?
        var genres: [String]?
    }

    private static var cache: [Int: String] = [:]

    var body: some View {
        // a blank line keeps the layout from jumping while the text loads
        Text(text.isEmpty ? " " : text)
            .font(.caption)
            .foregroundStyle(Color.white.opacity(0.45))
            .lineLimit(1)
            .padding(.horizontal, 8)
            .task(id: trackId) {
                if let hit = Self.cache[trackId] { text = hit; return }
                text = ""
                guard let detail = try? await app.api.get("/api/tracks/\(trackId)", as: Detail.self) else { return }
                let line = Self.describe(detail.passport)
                Self.cache[trackId] = line
                text = line
            }
    }

    private static func describe(_ p: Passport?) -> String {
        guard let p else { return "" }
        var parts: [String] = []
        let genres = (p.genres ?? []).filter { !$0.isEmpty }.prefix(2)
        if !genres.isEmpty { parts.append(genres.joined(separator: ", ")) }
        if let format = p.format, !format.isEmpty {
            let lossless = ["FLAC", "WAV", "ALAC", "AIFF", "APE", "WV"].contains(format.uppercased())
            if lossless {
                parts.append(format + (p.sampleRate.map { " · " + khz($0) } ?? ""))
            } else if let bitrate = p.bitrate, bitrate > 0 {
                parts.append("\(format) · \(Int((Double(bitrate) / 1000).rounded())) кбит/с")
            } else {
                parts.append(format)
            }
        }
        return parts.joined(separator: " · ")
    }

    private static func khz(_ hz: Int) -> String {
        let k = Double(hz) / 1000
        if k.truncatingRemainder(dividingBy: 1) == 0 { return "\(Int(k)) кГц" }
        return String(format: "%.1f кГц", k).replacingOccurrences(of: ".", with: ",")
    }
}

/// ♥ in the player: a fresh like makes the heart beat and fill with scarlet.
private struct LikeChip: View {
    @EnvironmentObject var app: AppState
    @EnvironmentObject var player: PlayerController
    @State private var beat = false

    var body: some View {
        // read from AppState so an unlike (no player update follows it) redraws the heart
        let liked = player.current.map { app.favoriteTracks.contains($0.id) } ?? false
        Button {
            if liked { Haptics.tap() } else { pulse() }
            player.like()
        } label: {
            Image(systemName: liked ? "heart.fill" : "heart")
                .foregroundStyle(liked ? Theme.love : Color.white)
                .scaleEffect(beat ? 1.45 : 1)
                .shadow(color: Theme.love.opacity(beat ? 0.8 : 0), radius: 8)
                .frame(maxWidth: .infinity)
                .animation(.easeInOut(duration: 0.2), value: liked)
        }
        .buttonStyle(GlassChipStyle(active: liked))
        .accessibilityLabel(liked ? "Убрать из любимых" : "В любимые")
    }

    /// Two quick beats, like a heart.
    private func pulse() {
        Haptics.heartbeat()
        let up = Animation.spring(response: 0.18, dampingFraction: 0.45)
        let down = Animation.spring(response: 0.28, dampingFraction: 0.6)
        withAnimation(up) { beat = true }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.16) { withAnimation(down) { beat = false } }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.36) { withAnimation(up) { beat = true } }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.52) { withAnimation(down) { beat = false } }
    }
}

/// Chips over the cover-coloured background: frosted white instead of the app's dark
/// surface and orange, so they read on any cover colour.
struct GlassChipStyle: ButtonStyle {
    var active = false
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.subheadline.weight(.semibold))
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .background(Color.white.opacity(active ? 0.3 : 0.12), in: Capsule())
            .overlay(Capsule().stroke(Color.white.opacity(active ? 0.55 : 0.14)))
            .foregroundStyle(Color.white)
            .opacity(configuration.isPressed ? 0.7 : 1)
    }
}
