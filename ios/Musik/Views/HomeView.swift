import SwiftUI
import UIKit

struct HomeView: View {
    @EnvironmentObject var app: AppState
    @EnvironmentObject var player: PlayerController

    @State private var mixes: [Mix] = []
    @State private var favorites = FavoritesResponse()
    @State private var recs = RecommendResponse()
    @State private var newTips: [DiscoverTip] = []
    @State private var oldTips: [DiscoverTip] = []
    @State private var artists: [ArtistItem] = []
    @State private var albums: [AlbumItem] = []
    @State private var tracks: [Track] = []
    @State private var maturity: String?
    @State private var loaded = false
    @State private var refreshingMixes = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 28) {
                header
                if !loaded {
                    ProgressView().frame(maxWidth: .infinity).padding(.top, 40)
                }
                shelves
            }
            .padding(.bottom, 24)
        }
        .refreshable {
            await app.refreshArtistPhotos(force: true)
            await load()
        }
        .task { if !loaded { await load() } }
        .onChange(of: app.homeRevision) { _ in Task { await loadFavorites() } }
        // the app was opened without a connection: fill the screen once the server is back
        .onChange(of: app.offlineLaunch) { offline in if !offline { Task { await load() } } }
        .navigationBarHidden(true)
        // No navigation bar here, so give the status bar its own backdrop:
        // otherwise shelf titles scroll right under the clock.
        .overlay(alignment: .top) {
            Color.clear
                .frame(height: 0)
                .background(.ultraThinMaterial, ignoresSafeAreaEdges: .top)
        }
    }

    // MARK: Header

    private var header: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 4) {
                    Text("musik").font(.display(40))
                    Text(Labels.maturity(player.maturity ?? maturity))
                        .font(.footnote)
                        .foregroundStyle(Theme.teal)
                }
                Spacer()
                NavigationLink(value: Route.search) {
                    Image(systemName: "magnifyingglass")
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundStyle(Color.white)
                        .frame(width: 44, height: 44)
                        .background(Color.white.opacity(0.12), in: Circle())
                        .overlay(Circle().stroke(Color.white.opacity(0.14)))
                }
                .accessibilityLabel("Поиск")
                .padding(.top, 4)
            }
            RadioCard(refreshingMixes: refreshingMixes, refreshMixes: { Task { await refreshMixes() } })
        }
        .padding(.horizontal, 16)
        .padding(.top, 12)
    }

    // MARK: Shelves

    // Split into groups to stay well under the ViewBuilder child limit.
    @ViewBuilder
    private var shelves: some View {
        mixShelves
        favoriteShelves
        discoveryShelves
        catalogShelves
    }

    @ViewBuilder
    private var mixShelves: some View {
        let main = mixes.filter { !$0.isWeekday }
        let week = mixes.filter(\.isWeekday)
        if !main.isEmpty {
            Shelf(title: "Миксы", subtitle: "Для тебя", trailing: AnyView(refreshMixesButton)) {
                ForEach(main) { mix in MixCard(mix: mix) { playMix(mix) } }
            }
        }
        if !week.isEmpty {
            Shelf(title: "Дни недели", trailing: main.isEmpty ? AnyView(refreshMixesButton) : nil) {
                ForEach(week) { mix in MixCard(mix: mix) { playMix(mix) } }
            }
        }
        if loaded && main.isEmpty && week.isEmpty && !app.offlineLaunch {
            Shelf(title: "Миксы", subtitle: "Ещё не собраны", trailing: AnyView(refreshMixesButton)) {
                EmptyView()
            }
        }
    }

    /// Rebuilds the mixes only (the radio is not touched), so it sits with them.
    private var refreshMixesButton: some View {
        Button {
            Task { await refreshMixes() }
        } label: {
            HStack(spacing: 6) {
                if refreshingMixes {
                    ProgressView().controlSize(.small)
                } else {
                    Image(systemName: "arrow.clockwise")
                }
                Text(refreshingMixes ? "Собираю…" : "Обновить")
            }
            .font(.footnote.weight(.semibold))
            .padding(.horizontal, 12)
            .padding(.vertical, 7)
            .background(Theme.surfaceHigh, in: Capsule())
            .overlay(Capsule().stroke(Color.white.opacity(0.08)))
        }
        .buttonStyle(.plain)
        .disabled(refreshingMixes)
        .accessibilityLabel("Обновить миксы")
    }

    @ViewBuilder
    private var favoriteShelves: some View {
        let favTracks = (favorites.tracks ?? []).map(\.asTrack)
        if !favTracks.isEmpty {
            Shelf(title: "Любимые песни") {
                ForEach(favTracks) { t in
                    TrackCard(track: t) { player.playMix("favorites", startTrackId: t.id) }
                        .contextMenu { TrackMenu(track: t) }
                }
            }
        }
        if let favArtists = favorites.artists, !favArtists.isEmpty {
            Shelf(title: "Любимые артисты") { ForEach(favArtists) { ArtistCard(artist: $0) } }
        }
        if let favAlbums = favorites.albums, !favAlbums.isEmpty {
            Shelf(title: "Любимые альбомы") { ForEach(favAlbums) { AlbumCard(album: $0) } }
        }
    }

    @ViewBuilder
    private var discoveryShelves: some View {
        if let recTracks = recs.tracks, !recTracks.isEmpty {
            Shelf(title: "Похожее на любимое", subtitle: "по звучанию (CLAP)") {
                ForEach(recTracks) { t in
                    TrackCard(track: t) {
                        player.play(["track_ids": recTracks.map(\.id), "start_track_id": t.id,
                                     "name": "Похожее на любимое"])
                    }
                    .contextMenu { TrackMenu(track: t) }
                }
            }
        } else if loaded, let hint = recs.hint {
            EmptyHint(text: hint)
        }

        if !newTips.isEmpty {
            Shelf(title: "Открытия", subtitle: "новое в библиотеке") {
                ForEach(newTips) { TipCard(tip: $0) }
            }
        }
        if !oldTips.isEmpty {
            Shelf(title: "Давно не звучало") {
                ForEach(oldTips) { TipCard(tip: $0) }
            }
        }
    }

    @ViewBuilder
    private var catalogShelves: some View {
        if !artists.isEmpty {
            Shelf(title: "Артисты") { ForEach(artists) { ArtistCard(artist: $0) } }
        }
        if !albums.isEmpty {
            Shelf(title: "Альбомы") { ForEach(albums) { AlbumCard(album: $0) } }
        }
        if !tracks.isEmpty {
            Shelf(title: "Треки") {
                ForEach(tracks) { t in
                    TrackCard(track: t) { player.play(["track_id": t.id, "name": t.displayTitle]) }
                        .contextMenu { TrackMenu(track: t) }
                }
            }
        }
        if loaded && mixes.isEmpty && tracks.isEmpty {
            EmptyHint(text: "Библиотека пуста или сервер ещё индексирует треки.")
        }
    }

    private func playMix(_ mix: Mix) {
        guard mix.ready != false else {
            app.show(mix.kind == "later" || mix.kind == "favorites" ? mix.metaLabel : "Микс не собран — нажми «Обновить» у миксов")
            return
        }
        player.playMix(mix.kind)
    }

    // MARK: Data

    private func load() async {
        let api = app.api
        async let m = try? api.mixes()
        async let r = try? api.recommendFavorites()
        async let n = try? api.discover(resurfaced: false)
        async let o = try? api.discover(resurfaced: true)
        async let ar = try? api.artists(limit: 28)
        async let al = try? api.albums(limit: 28)
        async let tr = try? api.library(limit: 36)
        async let p = try? api.profile()
        async let f = try? api.favorites()

        mixes = await m ?? mixes
        recs = await r ?? recs
        newTips = await n ?? newTips
        oldTips = await o ?? oldTips
        artists = await ar ?? artists
        albums = await al ?? albums
        tracks = await tr ?? tracks
        maturity = (await p)?.maturity ?? maturity
        if let fav = await f { applyFavorites(fav) }
        loaded = true
    }

    private func loadFavorites() async {
        let api = app.api
        async let f = try? api.favorites()
        async let m = try? api.mixes()
        async let r = try? api.recommendFavorites()
        if let fav = await f { applyFavorites(fav) }
        mixes = await m ?? mixes
        recs = await r ?? recs
    }

    private func applyFavorites(_ fav: FavoritesResponse) {
        favorites = fav
        app.favoriteTracks = Set((fav.tracks ?? []).map(\.trackId))
        app.favoriteArtists = Set((fav.artists ?? []).map(\.artist))
        app.favoriteAlbums = Set((fav.albums ?? []).map(\.id))
    }

    private func refreshMixes() async {
        refreshingMixes = true
        defer { refreshingMixes = false }
        do {
            guard let id = try await app.api.enqueueJob("mix_pack") else {
                app.show("Обновление поставлено в очередь")
                return
            }
            app.show("Собираю миксы…")
            for _ in 0..<300 {
                try await Task.sleep(nanoseconds: 2_000_000_000)
                let job = try await app.api.job(id)
                switch job.status {
                case "done":
                    app.show("Миксы обновлены")
                    await load()
                    return
                case "failed", "error", "cancelled":
                    app.show(job.error.nonEmpty ?? "Не удалось обновить миксы")
                    return
                default:
                    continue
                }
            }
        } catch {
            app.show(error)
        }
    }
}

struct TipCard: View {
    @EnvironmentObject var player: PlayerController
    let tip: DiscoverTip

    var body: some View {
        Button {
            if let ids = tip.trackIds, !ids.isEmpty {
                player.play(["track_ids": ids, "name": tip.album ?? tip.artist ?? "Открытие"])
            } else {
                player.playCatalog(artist: tip.artist, album: tip.album)
            }
        } label: {
            VStack(alignment: .leading, spacing: 6) {
                ArtworkView(trackId: tip.coverTrackId, size: 256, fallback: tip.album ?? "♪", cornerRadius: 12)
                    .frame(width: 160, height: 160)
                Text(tip.album ?? "—").font(.subheadline.weight(.semibold)).lineLimit(1)
                Text(tip.artist ?? "").font(.caption).foregroundStyle(Theme.muted).lineLimit(1)
                if let why = tip.explanation.nonEmpty {
                    Text(why).font(.caption2).foregroundStyle(Theme.teal).lineLimit(2)
                }
            }
            .frame(width: 160, alignment: .leading)
        }
        .buttonStyle(.plain)
    }
}


/// A roomy radio hero with direct access to favorites and mood contexts.
private struct RadioCard: View {
    @EnvironmentObject var app: AppState
    @EnvironmentObject var player: PlayerController
    let refreshingMixes: Bool
    let refreshMixes: () -> Void
    @State private var shareItem: ShareItem?
    @State private var sharing = false

    private let moods = ["Фокус", "Эпик", "Ночь", "Джаз", "Спокойно"]
    private var radioOn: Bool { player.current != nil && !player.fixed && !player.offlineMode }

    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            Text("НАСТРОЙСЯ НА СВОЁ")
                .font(.caption.weight(.bold)).tracking(2)
                .foregroundStyle(Theme.accentBright)
            HStack(alignment: .center, spacing: 12) {
                Text("Твой день.\nТвоя музыка.")
                    .font(.display(34)).fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
                record
            }
            Text("Любимые треки и новые открытия — в одном потоке. Просто нажми play.")
                .font(.subheadline).foregroundStyle(Theme.muted)
                .fixedSize(horizontal: false, vertical: true)

            VStack(spacing: 10) {
                Button {
                    Haptics.press()
                    if radioOn { player.togglePlay() } else { player.startRadio() }
                } label: {
                    Label(radioOn && player.isPlaying ? "Пауза радио" : "Включить моё радио",
                          systemImage: radioOn && player.isPlaying ? "pause.fill" : "play.fill")
                        .frame(maxWidth: .infinity, minHeight: 30)
                }
                .buttonStyle(PrimaryButtonStyle())
                Button { player.playMix("favorites") } label: {
                    Label("Вспомнить любимое", systemImage: "heart.fill")
                        .font(.subheadline.weight(.semibold))
                        .frame(maxWidth: .infinity, minHeight: 48)
                        .background(Color.black.opacity(0.1), in: Capsule())
                        .overlay(Capsule().stroke(Theme.accent.opacity(0.35)))
                }
                .buttonStyle(.plain)
            }
            .disabled(player.busy)

            // Wrap on narrow screens and with larger accessibility text sizes.
            MoodLayout {
                ForEach(moods, id: \.self) { mood in
                    Button(mood) { player.startRadio(mood: mood) }
                        .buttonStyle(ChipButtonStyle())
                        .disabled(player.busy)
                        .accessibilityLabel("Включить радио: \(mood)")
                }
            }
            if radioOn, let track = player.current {
                MarqueeText(text: "\(track.displayArtist) · \(track.displayTitle)", font: .caption)
                    .foregroundStyle(Theme.muted)
            }
            HStack(spacing: 20) {
                Button {
                    sharing = true
                    app.perform {
                        defer { sharing = false }
                        if let raw = try await app.api.createShare().url, let url = URL(string: raw) {
                            shareItem = ShareItem(url: url)
                        }
                    }
                } label: {
                    Label(sharing ? "Создаю…" : "Поделиться", systemImage: "square.and.arrow.up")
                }
                .disabled(sharing)
                Button(action: refreshMixes) {
                    Label(refreshingMixes ? "Собираю…" : "Обновить миксы", systemImage: "arrow.clockwise")
                }
                .disabled(refreshingMixes)
            }
            .font(.caption.weight(.semibold)).foregroundStyle(Theme.muted)
            .buttonStyle(.plain)
        }
        .padding(22)
        .background(
            LinearGradient(colors: [Theme.surfaceHigh, Theme.surface, Theme.accent.opacity(0.12)],
                           startPoint: .topLeading, endPoint: .bottomTrailing),
            in: RoundedRectangle(cornerRadius: 28, style: .continuous)
        )
        .overlay(RoundedRectangle(cornerRadius: 28).stroke(Theme.accent.opacity(0.12)))
        .sheet(item: $shareItem) { item in
            ActivityView(items: [item.url]).presentationDetents([.medium, .large])
        }
    }

    private var record: some View {
        ZStack {
            Circle().fill(Theme.accent)
            ForEach(0..<3) { i in
                Circle().stroke(Color.black.opacity(0.12), lineWidth: 2)
                    .padding(CGFloat(8 + i * 9))
            }
            Circle().fill(Theme.background).padding(31)
            if radioOn && player.isPlaying {
                SoundBars().foregroundStyle(Theme.accentBright)
            } else {
                Image(systemName: "music.note").font(.title2.bold()).foregroundStyle(Theme.accentBright)
            }
        }
        .frame(width: 104, height: 104)
        .rotationEffect(.degrees(-15))
        .accessibilityHidden(true)
    }
}

/// Small wrapping rows for mood buttons and artist chips (iOS 16).
struct MoodLayout: Layout {
    var spacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        arrange(subviews, width: proposal.width ?? 320).size
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let result = arrange(subviews, width: bounds.width)
        for (index, point) in result.points.enumerated() {
            subviews[index].place(at: CGPoint(x: bounds.minX + point.x, y: bounds.minY + point.y),
                                  anchor: .topLeading,
                                  proposal: ProposedViewSize(width: bounds.width, height: nil))
        }
    }

    private func arrange(_ subviews: Subviews, width: CGFloat) -> (size: CGSize, points: [CGPoint]) {
        var points: [CGPoint] = []
        var x: CGFloat = 0
        var y: CGFloat = 0
        var rowHeight: CGFloat = 0
        for view in subviews {
            let size = view.sizeThatFits(ProposedViewSize(width: width, height: nil))
            if x > 0 && x + size.width > width {
                x = 0
                y += rowHeight + spacing
                rowHeight = 0
            }
            points.append(CGPoint(x: x, y: y))
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
        }
        return (CGSize(width: width, height: y + rowHeight), points)
    }
}

/// Three bars bouncing while the radio plays.
private struct SoundBars: View {
    @State private var up = false

    var body: some View {
        HStack(alignment: .bottom, spacing: 4) {
            ForEach(0..<3, id: \.self) { i in
                Capsule()
                    .frame(width: 5, height: up ? [22, 14, 26][i] : [10, 24, 12][i])
                    .animation(.easeInOut(duration: [0.45, 0.35, 0.5][i]).repeatForever(autoreverses: true), value: up)
            }
        }
        .frame(height: 28)
        .onAppear { up = true }
    }
}

private struct PressScaleStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
            .animation(.easeOut(duration: 0.15), value: configuration.isPressed)
    }
}
