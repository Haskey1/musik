import SwiftUI

struct LibraryView: View {
    enum Segment: String, CaseIterable, Identifiable {
        case all = "Все"
        case tracks = "Треки"
        case artists = "Артисты"
        case albums = "Альбомы"
        case favorites = "Любимое"
        var id: String { rawValue }
    }

    @EnvironmentObject var app: AppState
    @EnvironmentObject var player: PlayerController

    @State private var segment: Segment = .all
    @State private var query = ""
    @State private var tracks: [Track] = []
    @State private var artists: [ArtistItem] = []
    @State private var albums: [AlbumItem] = []
    @State private var favorites = FavoritesResponse()
    @State private var loaded = false

    var body: some View {
        List {
            Picker("Раздел", selection: $segment) {
                ForEach(Segment.allCases) { Text($0.rawValue).tag($0) }
            }
            .pickerStyle(.segmented)
            .listRowBackground(Color.clear)
            .listRowSeparator(.hidden)

            if !loaded {
                ProgressView().frame(maxWidth: .infinity).listRowBackground(Color.clear)
            }

            switch segment {
            case .all: allSection
            case .tracks: tracksSection
            case .artists: artistsSection
            case .albums: albumsSection
            case .favorites: favoritesSection
            }
        }
        .listStyle(.plain)
        .resetScrollOnReselect(tab: 1)
        .scrollContentBackground(.hidden)
        .background(Theme.backdrop)
        .navigationTitle("Библиотека")
        .searchable(text: $query, prompt: "Поиск")
        .refreshable { await load() }
        .task { if !loaded { await load() } }
        .onChange(of: app.homeRevision) { _ in Task { await loadFavorites() } }
        .onChange(of: app.offlineLaunch) { offline in if !offline { Task { await load() } } }
    }

    // MARK: Sections

    @ViewBuilder
    private var tracksSection: some View {
        let list = filteredTracks
        ForEach(Array(list.enumerated()), id: \.element.id) { i, t in
            TrackRow(track: t) { playFromLibrary(list, at: i) }
                .listRowBackground(Color.clear)
        }
        if loaded && list.isEmpty {
            EmptyHint(text: query.isEmpty ? "Треков нет" : "Ничего не найдено").listRowBackground(Color.clear)
        }
    }

    @ViewBuilder
    private var artistsSection: some View {
        ForEach(artists.filter { matches($0.artist) }) { artistRow($0) }
    }

    @ViewBuilder
    private var albumsSection: some View {
        ForEach(albums.filter { matches($0.album) || matches($0.artist ?? "") }) { albumRow($0) }
    }

    /// «Все»: one search over artists, albums and tracks. Each block is a short preview
    /// with a button to the full list of that kind (the query stays in the search field).
    @ViewBuilder
    private var allSection: some View {
        let foundArtists = artists.filter { matches($0.artist) }
        let foundAlbums = albums.filter { matches($0.album) || matches($0.artist ?? "") }
        let foundTracks = filteredTracks
        let limit = query.trimmingCharacters(in: .whitespaces).isEmpty ? 5 : 8
        if !foundArtists.isEmpty {
            Section {
                ForEach(foundArtists.prefix(limit)) { artistRow($0) }
                showAll("Все артисты", count: foundArtists.count, shown: limit, segment: .artists)
            } header: { allHeader("Артисты") }
        }
        if !foundAlbums.isEmpty {
            Section {
                ForEach(foundAlbums.prefix(limit)) { albumRow($0) }
                showAll("Все альбомы", count: foundAlbums.count, shown: limit, segment: .albums)
            } header: { allHeader("Альбомы") }
        }
        if !foundTracks.isEmpty {
            Section {
                let shown = Array(foundTracks.prefix(limit * 2))
                ForEach(Array(shown.enumerated()), id: \.element.id) { i, t in
                    TrackRow(track: t) { playFromLibrary(shown, at: i) }
                        .listRowBackground(Color.clear)
                }
                showAll("Все треки", count: foundTracks.count, shown: limit * 2, segment: .tracks)
            } header: { allHeader("Треки") }
        }
        if loaded && foundArtists.isEmpty && foundAlbums.isEmpty && foundTracks.isEmpty {
            EmptyHint(text: query.isEmpty ? "Библиотека пуста" : "Ничего не найдено").listRowBackground(Color.clear)
        }
    }

    private func allHeader(_ title: String) -> some View {
        Text(title).font(.title3.weight(.bold)).foregroundStyle(Color.primary).textCase(nil)
    }

    @ViewBuilder
    private func showAll(_ title: String, count: Int, shown: Int, segment target: Segment) -> some View {
        if count > shown {
            Button { segment = target } label: {
                HStack {
                    Text("\(title) · \(count)")
                    Spacer()
                    Image(systemName: "chevron.right").font(.caption.weight(.semibold))
                }
                .foregroundStyle(Theme.accent)
            }
            .listRowBackground(Color.clear)
        }
    }

    private func artistRow(_ a: ArtistItem) -> some View { ArtistListRow(artist: a) }

    private func albumRow(_ a: AlbumItem) -> some View { AlbumListRow(album: a) }

    @ViewBuilder
    private var favoritesSection: some View {
        let favTracks = (favorites.tracks ?? []).map(\.asTrack).filter { matchesTrack($0) }
        Section("Песни") {
            ForEach(favTracks) { t in
                TrackRow(track: t, unlikable: true) { player.playMix("favorites", startTrackId: t.id) }
            }
            if favTracks.isEmpty { EmptyHint(text: "Жми ♥ на треке") }
        }
        .listRowBackground(Color.clear)
        Section("Артисты") {
            ForEach((favorites.artists ?? []).filter { matches($0.artist) }) { a in
                NavigationLink(a.artist, value: Route.artist(a.artist))
            }
        }
        .listRowBackground(Color.clear)
        Section("Альбомы") {
            ForEach((favorites.albums ?? []).filter { matches($0.album) }) { a in
                NavigationLink(value: Route.album(artist: a.artist, album: a.album)) {
                    VStack(alignment: .leading) {
                        Text(a.album)
                        Text(a.artist ?? "").font(.caption).foregroundStyle(Theme.muted)
                    }
                }
            }
        }
        .listRowBackground(Color.clear)
    }

    // MARK: Helpers

    private var filteredTracks: [Track] { tracks.filter { matchesTrack($0) } }

    private func matches(_ s: String) -> Bool {
        let q = query.trimmingCharacters(in: .whitespaces)
        return q.isEmpty || s.localizedCaseInsensitiveContains(q)
    }

    private func matchesTrack(_ t: Track) -> Bool {
        matches(t.title ?? "") || matches(t.artist ?? "") || matches(t.album ?? "")
    }

    /// Plays the visible list from the tapped row, so playback continues through it.
    private func playFromLibrary(_ list: [Track], at index: Int) {
        let window = list[index..<min(list.count, index + 500)]
        player.play([
            "track_ids": window.map(\.id),
            "name": query.isEmpty ? "Библиотека" : "Поиск: \(query)",
        ])
    }

    /// Artists of a track list: each credited artist, by number of tracks; the cover is
    /// the first of their tracks that has artwork.
    static func groupArtists(_ tracks: [Track]) -> [ArtistItem] {
        var by: [String: ArtistItem] = [:]
        var hasArt: Set<String> = []
        for t in tracks {
            let names = (t.artists ?? []).compactMap { Optional($0).nonEmpty }
            for raw in names.isEmpty ? [t.artist.nonEmpty ?? "Unknown"] : names {
                let name = raw.trimmingCharacters(in: .whitespaces)
                let key = name.lowercased()
                var item = by[key] ?? ArtistItem(artist: name, tracks: 0, coverTrackId: t.id)
                item.tracks = (item.tracks ?? 0) + 1
                if t.artwork != nil, !hasArt.contains(key) {
                    item.coverTrackId = t.id
                    hasArt.insert(key)
                }
                by[key] = item
            }
        }
        return by.values.sorted {
            ($0.tracks ?? 0, $1.artist) > ($1.tracks ?? 0, $0.artist)
        }
    }

    private func load() async {
        let api = app.api
        async let t = try? api.library()
        async let ar = try? api.artists()
        async let al = try? api.albums()
        async let f = try? api.favorites()
        tracks = await t ?? tracks
        // Artists come from the same track list as the Tracks tab (as on the web), so
        // every artist found there is found here too; the server's list only has
        // tracks the radio index already holds.
        let grouped = Self.groupArtists(tracks)
        artists = grouped.isEmpty ? (await ar ?? artists) : grouped
        albums = await al ?? albums
        favorites = await f ?? favorites
        loaded = true
    }

    private func loadFavorites() async {
        if let f = try? await app.api.favorites() { favorites = f }
    }
}
