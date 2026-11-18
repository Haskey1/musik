import AVFoundation
import SwiftUI

struct RecapAvailability: Decodable {
    let available: Bool
    let year: Int
}

private struct RecapPayload: Decodable {
    let year: Int
    let stats: ListeningPeriod
    let artistTrack: ListeningTrack?
}

private struct RecapClip: Decodable { let audio: Data }

struct YearRecapBanner: View {
    let year: Int
    let open: () -> Void

    var body: some View {
        Button(action: open) {
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Твой \(String(year))").font(.display(25))
                    Text("Смотреть итоги").font(.subheadline.weight(.semibold))
                }
                Spacer()
                Image(systemName: "arrow.up.right").font(.title3.bold())
            }
            .padding(19)
            .foregroundStyle(Theme.cream)
            .background {
                ZStack {
                    LinearGradient(colors: [Color(hex: 0x49251C), Color(hex: 0x9C441E), Theme.accent],
                                   startPoint: .bottomLeading, endPoint: .topTrailing)
                    RecapSilk(tint: Theme.accentBright)
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: 24))
        }
        .buttonStyle(.plain)
    }
}

private enum RecapPage: Int, CaseIterable {
    case intro, time, track, tracks, artist, artists, finish

    var tint: Color {
        switch self {
        case .intro, .finish: Theme.accent
        case .time: Color(hex: 0xBA4930)
        case .artist, .artists: Color(hex: 0x9C3271)
        case .tracks: Color(hex: 0x6744B6)
        case .track: Theme.love
        }
    }
}

struct YearRecapView: View {
    @EnvironmentObject private var app: AppState
    @EnvironmentObject private var player: PlayerController
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scene
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var recap: RecapPayload?
    @State private var error = false
    @State private var index = 0
    @State private var direction: CGFloat = 1
    @GestureState private var drag: CGFloat = 0
    @State private var progress = 0.0
    @State private var loadingClip = false
    @State private var muted = false
    @State private var paused = false
    @State private var holding = false
    @State private var restoreMusic = false
    @State private var audio: AVAudioPlayer?
    @State private var clips: [Int: Data] = [:]

    private var pages: [RecapPage] {
        guard let recap else { return [] }
        guard !recap.stats.tracks.isEmpty else { return [.intro, .time, .finish] }
        return RecapPage.allCases.filter { page in
            switch page {
            case .artist: return recap.artistTrack != nil
            case .artists: return recap.stats.artists.count > 1
            case .tracks: return recap.stats.tracks.count > 1
            default: return true
            }
        }
    }
    private var page: RecapPage { pages.indices.contains(index) ? pages[index] : .intro }
    private var backingTrack: ListeningTrack? {
        switch page {
        case .artist, .artists: return recap?.artistTrack ?? recap?.stats.tracks.first
        case .tracks: return recap?.stats.tracks.dropFirst().first
        case .time: return recap?.stats.tracks.last
        default: return recap?.stats.tracks.first
        }
    }

    var body: some View {
        ZStack {
            Theme.background.ignoresSafeArea()
            RadialGradient(colors: [page.tint.opacity(0.66), .clear], center: .topTrailing,
                           startRadius: 0, endRadius: 460).ignoresSafeArea()
            RecapSilk(tint: page.tint).ignoresSafeArea()
            VStack(spacing: 10) {
                header
                if let recap {
                    GeometryReader { geometry in
                        ZStack {
                            content(recap, height: geometry.size.height)
                                .id(page)
                                .transition(reduceMotion ? .opacity : .asymmetric(
                                    insertion: .scale(scale: 0.83).combined(with: .offset(x: 145 * direction)).combined(with: .opacity),
                                    removal: .offset(x: -145 * direction).combined(with: .opacity)))
                        }
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                        .offset(x: drag * 0.8)
                        .contentShape(Rectangle())
                        .simultaneousGesture(DragGesture(minimumDistance: 18)
                            .updating($drag) { value, state, _ in state = value.translation.width }
                            .onEnded { value in
                                if abs(value.translation.width) > 45 {
                                    move(value.translation.width < 0 ? 1 : -1)
                                }
                            })
                        .simultaneousGesture(SpatialTapGesture().onEnded { value in
                            guard page != .intro, page != .finish else { return }
                            if value.location.x < geometry.size.width * 0.24 { move(-1) }
                            if value.location.x > geometry.size.width * 0.76 { move(1) }
                        })
                    }
                    if page != .intro && page != .finish { footer }
                } else if error {
                    Spacer()
                    Text("Итоги сейчас недоступны")
                    Button("Повторить") { Task { await load() } }.buttonStyle(PrimaryButtonStyle())
                    Spacer()
                } else {
                    Spacer()
                    ProgressView()
                    Spacer()
                }
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 12)
        }
        .foregroundStyle(Theme.cream)
        .preferredColorScheme(.dark)
        .task {
            restoreMusic = player.isPlaying
            if restoreMusic { player.pause() }
            await load()
        }
        .task(id: page) { await fetchAudio() }
        .task(id: index) {
            while !Task.isCancelled {
                do { try await Task.sleep(nanoseconds: 100_000_000) } catch { return }
                guard page != .intro && page != .finish, !paused, !holding, scene == .active else { continue }
                if let audio, audio.duration > 0 {
                    progress = audio.isPlaying ? audio.currentTime / audio.duration : 1
                } else if !loadingClip || muted {
                    progress += 0.1 / 16
                }
                if progress >= 1 { move(1); return }
            }
        }
        .onChange(of: scene) { _ in updateAudio() }
        .onDisappear {
            audio?.stop()
            if restoreMusic { player.resume() }
        }
    }

    private var header: some View {
        VStack(spacing: 6) {
            if !pages.isEmpty {
                HStack(spacing: 5) {
                    ForEach(pages.indices, id: \.self) { i in
                        Button { select(i) } label: {
                            GeometryReader { proxy in
                                Capsule().fill(Color.white.opacity(0.24))
                                Capsule().fill(Theme.accentBright)
                                    .frame(width: proxy.size.width * (i < index ? 1 : i == index ? min(progress, 1) : 0))
                            }
                            .frame(height: 4)
                            .padding(.vertical, 9)
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel("Слайд \(i + 1)")
                    }
                }
            }
            HStack {
                Text("musik · " + (recap.map { String($0.year) } ?? "Итоги года")).font(.headline)
                Spacer()
                Button {
                    muted.toggle()
                    updateAudio()
                } label: { Image(systemName: muted ? "speaker.slash.fill" : "speaker.wave.2.fill").frame(width: 44, height: 44) }
                    .accessibilityLabel(muted ? "Включить звук" : "Выключить звук")
                Button { dismiss() } label: { Image(systemName: "xmark").frame(width: 44, height: 44) }
                    .accessibilityLabel("Закрыть итоги")
            }
            .buttonStyle(.plain)
        }
    }

    private var footer: some View {
        HStack(spacing: 10) {
            Button { move(-1) } label: { Image(systemName: "chevron.left").frame(width: 44, height: 44) }
                .accessibilityLabel("Предыдущий слайд")
            if let track = backingTrack {
                Text(track.title).font(.subheadline.weight(.semibold)).lineLimit(1)
            }
            Spacer()
            Button { paused.toggle(); updateAudio() } label: {
                Image(systemName: paused ? "play.fill" : "pause.fill").frame(width: 44, height: 44)
            }
            .accessibilityLabel(paused ? "Продолжить" : "Пауза")
            Button { move(1) } label: { Image(systemName: "chevron.right").frame(width: 44, height: 44) }
                .accessibilityLabel("Следующий слайд")
        }
        .buttonStyle(.plain)
        .background(.white.opacity(0.08), in: RoundedRectangle(cornerRadius: 18))
    }

    @ViewBuilder
    private func content(_ recap: RecapPayload, height: CGFloat) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Spacer(minLength: 0)
            switch page {
            case .intro:
                coverFan(recap.stats.tracks, height: height)
                Text("Твой\n\(String(recap.year))").font(.display(79)).minimumScaleFactor(0.62).lineLimit(2)
                Text("У каждого года есть своё звучание").font(.title3)
                Spacer(minLength: 0)
                Button("Начать →") { move(1) }.buttonStyle(PrimaryButtonStyle())
            case .time:
                coverFan(recap.stats.tracks, height: height)
                Text("Музыка была рядом").font(.display(28))
                Text(Int(recap.stats.listenedSeconds / 60).formatted()).font(.display(76)).minimumScaleFactor(0.5).lineLimit(1)
                Text("минут с музыкой").font(.title2)
                if recap.stats.listenedSeconds >= 3600 {
                    Text("Если включить всё подряд — \(Int(recap.stats.listenedSeconds / 3600)) ч \(Int(recap.stats.listenedSeconds / 60) % 60) мин").font(.headline)
                }
                Text("Прослушиваний за год · \(recap.stats.plays.formatted())").foregroundStyle(Theme.muted)
            case .track:
                if let track = recap.stats.tracks.first {
                    Text("Твой трек года").font(.display(28))
                    ArtworkView(trackId: track.trackId, size: 640, fallback: track.title, cornerRadius: 22)
                        .frame(width: min(290, height * 0.47))
                    Text(track.title).font(.display(28)).lineLimit(2)
                    Text(track.artist).foregroundStyle(Theme.muted)
                    Text("Прослушиваний · \(track.count.formatted())").font(.headline)
                }
            case .artist:
                if let artist = recap.stats.artists.first, let track = recap.artistTrack {
                    Text("Твой артист года").font(.display(28))
                    Text(artist.artist).font(.display(46)).minimumScaleFactor(0.6).lineLimit(2)
                    ArtworkView(trackId: track.trackId, size: 640, fallback: artist.artist, cornerRadius: 28,
                                photo: ArtistPhotos.shared.url(for: artist.artist, width: 640, api: app.api))
                        .frame(width: min(290, height * 0.47))
                    Text("Прослушиваний · \(artist.count.formatted())").font(.headline)
                }
            case .tracks:
                Text("Твой хит-парад").font(.display(38))
                Text("Песни, к которым ты возвращался чаще всего.")
                ForEach(Array(recap.stats.tracks.prefix(5).enumerated()), id: \.offset) { rank, item in
                    HStack(spacing: 12) {
                        Text(String(rank + 1)).font(.display(26)).frame(width: 30)
                        ArtworkView(trackId: item.trackId, fallback: item.title, cornerRadius: 8)
                            .frame(width: min(55, height * 0.09))
                        VStack(alignment: .leading) {
                            Text(item.title).font(.headline).lineLimit(1)
                            Text(item.artist).font(.caption).lineLimit(1)
                        }
                        Spacer()
                        Text("×" + String(item.count)).font(.caption.bold())
                    }
                }
            case .artists:
                Text("Твои люди").font(.display(42))
                Text("Эти голоса звучали у тебя чаще других.")
                ForEach(Array(recap.stats.artists.prefix(3).enumerated()), id: \.offset) { rank, item in
                    HStack(spacing: 14) {
                        Text(String(rank + 1)).font(.display(30))
                        ArtworkView(trackId: recap.stats.tracks.first { $0.artist == item.artist }?.trackId,
                                    fallback: item.artist, photo: ArtistPhotos.shared.url(for: item.artist, width: 320, api: app.api))
                            .frame(width: min(76, height * 0.14)).clipShape(Circle())
                        VStack(alignment: .leading) {
                            Text(item.artist).font(.title2.bold()).lineLimit(2)
                            Text("Прослушиваний · " + String(item.count)).font(.caption)
                        }
                    }
                }
            case .finish:
                Text("Так звучал\nтвой \(String(recap.year))").font(.display(47)).minimumScaleFactor(0.6)
                if let track = recap.stats.tracks.first {
                    coverFan(recap.stats.tracks, height: height * 0.7)
                    Text("\(Int(recap.stats.listenedSeconds / 60).formatted()) минут вместе").font(.title3.bold())
                    Text("Трек года — \(track.title)").font(.headline).lineLimit(2)
                    if let artist = recap.stats.artists.first { Text("Артист года — \(artist.artist)").lineLimit(2) }
                    Button("Слушать плейлист года") {
                        restoreMusic = false
                        audio?.stop()
                        player.play(["track_ids": recap.stats.tracks.map(\.trackId), "name": "Твой \(recap.year)"])
                        dismiss()
                    }
                    .buttonStyle(PrimaryButtonStyle())
                    SwiftUI.ShareLink(item: "Мой \(recap.year) в musik · \(Int(recap.stats.listenedSeconds / 60)) мин музыки · \(track.title) — \(track.artist)") {
                        Label("Поделиться итогами", systemImage: "square.and.arrow.up")
                    }
                } else {
                    Text("В истории пока нет треков за этот год.").foregroundStyle(Theme.muted)
                }
                Button("Смотреть снова") { select(0) }.padding(.top, 4)
            }
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        .onLongPressGesture(minimumDuration: 0.22, maximumDistance: 15, perform: {}, onPressingChanged: { active in
            holding = active
            updateAudio()
        })
    }

    private func coverFan(_ tracks: [ListeningTrack], height: CGFloat) -> some View {
        HStack(spacing: -18) {
            ForEach(Array(tracks.prefix(3).enumerated()), id: \.offset) { i, item in
                ArtworkView(trackId: item.trackId, fallback: item.title, cornerRadius: 12)
                    .frame(width: min(120, height * 0.19))
                    .rotationEffect(.degrees(Double(i - 1) * 9))
                    .shadow(radius: 6)
            }
        }
        .padding(.vertical, 8)
    }

    private func load() async {
        error = false
        do { recap = try await app.api.get("/api/year-recap") }
        catch is CancellationError { return }
        catch { self.error = true }
    }

    private func move(_ delta: Int) { select(index + delta) }

    private func select(_ newIndex: Int) {
        guard pages.indices.contains(newIndex), index != newIndex else { return }
        direction = newIndex > index ? 1 : -1
        withAnimation(.easeInOut(duration: reduceMotion ? 0.18 : 0.42)) {
            index = newIndex
            progress = page == .finish ? 1 : 0
        }
        if page == .intro || page == .finish { updateAudio() }
        else if audio?.isPlaying == false { updateAudio() }
    }

    private func fetchAudio() async {
        loadingClip = false
        let previous = audio
        audio = nil
        previous?.setVolume(0, fadeDuration: 0.25)
        if let previous {
            Task { @MainActor in
                try? await Task.sleep(nanoseconds: 260_000_000)
                previous.stop()
            }
        }
        guard page != .intro, page != .finish, let id = backingTrack?.trackId else { return }
        loadingClip = true
        defer { if !Task.isCancelled { loadingClip = false } }
        do {
            let data: Data
            if let cached = clips[id] { data = cached }
            else {
                let clip: RecapClip = try await app.api.get("/api/year-recap/clips/\(id)")
                data = clip.audio
            }
            try Task.checkCancellation()
            clips[id] = data
            audio = try AVAudioPlayer(data: data)
            audio?.prepareToPlay()
            if let audio { audio.currentTime = progress * audio.duration }
            updateAudio()
        } catch { }
    }

    private func updateAudio() {
        guard let audio else { return }
        if paused || holding || scene != .active || page == .intro || page == .finish {
            audio.pause()
        } else if !audio.isPlaying {
            audio.volume = 0
            audio.play()
            audio.setVolume(muted ? 0 : 1, fadeDuration: 1.25)
        } else {
            audio.setVolume(muted ? 0 : 1, fadeDuration: 0.28)
        }
    }
}

private struct RecapSilk: View {
    let tint: Color
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        TimelineView(.animation(minimumInterval: 1 / 30, paused: reduceMotion)) { time in
            Canvas { context, size in
                let phase = reduceMotion ? 0 : time.date.timeIntervalSinceReferenceDate * 0.45
                let w = size.width, h = size.height
                for i in 0..<32 {
                    let u = CGFloat(i) / 31
                    let wave = CGFloat(sin(phase + Double(u) * 2))
                    var path = Path()
                    path.move(to: CGPoint(x: -0.15*w, y: h*(0.17 + 0.21*u)))
                    path.addCurve(to: CGPoint(x: 1.15*w, y: h*(0.56 + 0.28*u)),
                                  control1: CGPoint(x: w*(0.95 + wave*0.22), y: -h*(0.18 + 0.35*u)),
                                  control2: CGPoint(x: w*(0.22 + wave*0.24), y: h*(1.08 - 0.27*u)))
                    context.stroke(path, with: .linearGradient(Gradient(colors: [tint.opacity(0.18), Color.orange.opacity(0.55), Color.pink.opacity(0.32), tint]), startPoint: .zero, endPoint: CGPoint(x: w,y: h)), lineWidth: i % 8 == 0 ? 2.2 : 0.85)
                }
            }
        }.allowsHitTesting(false).accessibilityHidden(true)
    }
}
