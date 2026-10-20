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
    @ObservedObject private var eq = Equalizer.shared

    var body: some View {
        ZStack {
            Theme.backdrop
            if let track = player.current {
                ScrollView {
                    VStack(spacing: 22) {
                        topBar
                        ArtworkView(trackId: track.id, size: 640, fallback: track.displayTitle, cornerRadius: 22)
                            .frame(maxWidth: 340)
                            .shadow(color: .black.opacity(0.55), radius: 28, y: 14)
                            .scaleEffect(player.isPlaying ? 1 : 0.94)
                            .animation(.spring(response: 0.4, dampingFraction: 0.8), value: player.isPlaying)
                            .swipeToSkip(threshold: 90)
                            .padding(.horizontal, 24)
                        titleBlock(track)
                        SeekBar()
                        transport
                        actions(track)
                        Picker("", selection: $pane) {
                            ForEach(Pane.allCases) { Text($0.rawValue).tag($0) }
                        }
                        .pickerStyle(.segmented)
                        .padding(.horizontal, 20)
                        switch pane {
                        case .queue: upNext
                        case .lyrics: LyricsView(trackId: track.id)
                        }
                        // quiet, at the very bottom — as in Yandex Music
                        HStack {
                            Spacer()
                            Button { showEqualizer = true } label: {
                                Image(systemName: "slider.horizontal.3")
                                    .font(.title3)
                                    .foregroundStyle(eq.enabled ? Theme.accent : Theme.muted)
                                    .frame(width: 64, height: 48)
                                    .background(Theme.surface, in: Capsule())
                            }
                            .accessibilityLabel("Эквалайзер")
                        }
                        .padding(.horizontal, 20)
                        .padding(.top, 8)
                    }
                    .padding(.bottom, 32)
                }
            } else {
                VStack(spacing: 12) {
                    Text("Ничего не играет").font(.title3.weight(.semibold))
                    Button("Включить радио") { player.startRadio() }
                        .buttonStyle(PrimaryButtonStyle())
                }
            }
        }
        .overlay(alignment: .top) { ToastView() }
        .sheet(isPresented: $showEqualizer) { EqualizerView() }
    }

    // MARK: Parts

    private var topBar: some View {
        HStack {
            Button { dismiss() } label: {
                Image(systemName: "chevron.down").font(.title3.weight(.semibold))
            }
            Spacer()
            VStack(spacing: 2) {
                Text(player.listName ?? (player.fixed ? "Плейлист" : "Радио"))
                    .font(.subheadline.weight(.semibold))
                    .lineLimit(1)
                if player.offlineMode {
                    Label("офлайн · с телефона", systemImage: "wifi.slash")
                        .font(.caption2)
                        .foregroundStyle(Theme.accentBright)
                } else {
                    Text(Labels.maturity(player.maturity))
                        .font(.caption2)
                        .foregroundStyle(Theme.teal)
                }
            }
            Spacer()
            if let track = player.current {
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
            .foregroundStyle(Theme.muted)
            .lineLimit(1)
            .padding(.horizontal, 8)
            if let source = Labels.source(track.source) {
                Text(source)
                    .font(.caption2.weight(.semibold))
                    .padding(.horizontal, 8).padding(.vertical, 3)
                    .background(Theme.teal.opacity(0.25), in: Capsule())
                    .foregroundStyle(Theme.teal)
            }
        }
        .padding(.horizontal, 24)
    }


    private var transport: some View {
        HStack(spacing: 44) {
            Button { player.back() } label: {
                Image(systemName: "backward.fill").font(.title)
            }
            Button { player.togglePlay() } label: {
                ZStack {
                    Circle()
                        .fill(LinearGradient(colors: [Theme.accentBright, Theme.accent],
                                             startPoint: .topLeading, endPoint: .bottomTrailing))
                        .frame(width: 76, height: 76)
                    if player.isBuffering || player.busy {
                        ProgressView().tint(.black)
                    } else {
                        Image(systemName: player.isPlaying ? "pause.fill" : "play.fill")
                            .font(.system(size: 30, weight: .bold))
                            .foregroundStyle(.black.opacity(0.85))
                    }
                }
            }
            Button { player.skip() } label: {
                Image(systemName: "forward.fill").font(.title)
            }
            .disabled(player.busy)
        }
        .foregroundStyle(.primary)
    }

    private func actions(_ track: Track) -> some View {
        VStack(spacing: 10) {
            HStack(spacing: 10) {
                Button { player.like() } label: {
                    Image(systemName: player.isFavorite ? "heart.fill" : "heart")
                }
                .buttonStyle(ChipButtonStyle(active: player.isFavorite))
                .accessibilityLabel("Любимая")

                Button { player.dislike() } label: {
                    Image(systemName: player.disliked ? "hand.thumbsdown.fill" : "hand.thumbsdown")
                }
                .buttonStyle(ChipButtonStyle(active: player.disliked))
                .disabled(player.disliked)
                .accessibilityLabel("Дизлайк")

                Button {
                    if offline.isSaved(track.id) { offline.remove(track.id) } else { offline.save(track) }
                } label: {
                    if offline.downloading.contains(track.id) {
                        ProgressView().controlSize(.small)
                    } else {
                        Image(systemName: offline.isSaved(track.id) ? "arrow.down.circle.fill" : "arrow.down.circle")
                    }
                }
                .buttonStyle(ChipButtonStyle(active: offline.isSaved(track.id)))
                .accessibilityLabel("Сохранить на телефон")

                Button { Task { await app.addLater(track.id) } } label: {
                    Label("Потом", systemImage: "clock")
                }
                .buttonStyle(ChipButtonStyle())

                Button { player.playSimilarNow() } label: {
                    Label("Похожее", systemImage: "sparkles")
                }
                .buttonStyle(ChipButtonStyle())
            }
            HStack(spacing: 10) {
                if let artist = track.artist.nonEmpty {
                    Button { Task { await app.toggleFavoriteArtist(artist) } } label: {
                        Label("артист", systemImage: app.favoriteArtists.contains(artist) ? "heart.fill" : "heart")
                    }
                    .buttonStyle(ChipButtonStyle(active: app.favoriteArtists.contains(artist)))
                }
                if let album = track.album.nonEmpty {
                    let fav = app.favoriteAlbums.contains(albumKey(track.artist, album))
                    Button { Task { await app.toggleFavoriteAlbum(artist: track.artist, album: album) } } label: {
                        Label("альбом", systemImage: fav ? "heart.fill" : "heart")
                    }
                    .buttonStyle(ChipButtonStyle(active: fav))
                }
                Button { player.startRadio(seed: track.id) } label: {
                    Label("Радио", systemImage: "dot.radiowaves.left.and.right")
                }
                .buttonStyle(ChipButtonStyle())
            }
        }
        .labelStyle(.titleAndIcon)
        .padding(.horizontal, 12)
    }

    @ViewBuilder
    private var upNext: some View {
        LazyVStack(alignment: .leading, spacing: 14) {
            if player.fixed {
                Text("В этом списке · \(player.playlist.count)")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(Theme.muted)
                ForEach(Array(player.playlist.enumerated()), id: \.offset) { i, t in
                    TrackRow(track: t, index: i) {
                        player.jump(trackId: t.id, index: t.position ?? i)
                    }
                }
            } else {
                Text("Дальше в радио · нажми — сразу она")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(Theme.muted)
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
                            Text(why).italic().foregroundStyle(Theme.teal).lineLimit(1)
                        }
                    }
                    .font(.caption)
                    .foregroundStyle(Theme.muted)
                }
                Spacer(minLength: 8)
                Text(formatTime(item.duration))
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(Theme.muted)
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
                    Capsule().fill(Color.white.opacity(0.15)).frame(height: scrubbing ? 8 : 5)
                    Capsule().fill(Theme.accent).frame(width: max(x, 0), height: scrubbing ? 8 : 5)
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
            .foregroundStyle(Theme.muted)
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
                        .foregroundStyle(i == active ? Theme.accentBright : Theme.muted)
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
