import SwiftUI

/// Search from the home screen: one field over tracks, artists and albums at once.
/// Every word of the query has to be found (so «tuatara ссс» works in any order).
struct SearchView: View {
    @EnvironmentObject var app: AppState
    @EnvironmentObject var player: PlayerController

    @State private var query = ""
    @State private var tracks: [Track] = []
    @State private var artists: [ArtistItem] = []
    @State private var albums: [AlbumItem] = []
    @State private var loaded = false
    @State private var expanded: Set<String> = []
    @State private var focusedOnce = false
    @FocusState private var focused: Bool

    private static let previewCount = 5

    var body: some View {
        let words = Self.words(query)
        let foundArtists = words.isEmpty ? [] : artists.filter { Self.hit(words, [$0.artist]) }
        let foundAlbums = words.isEmpty ? [] : albums.filter { Self.hit(words, [$0.album, $0.artist ?? ""]) }
        let foundTracks = words.isEmpty ? [] : tracks.filter {
            Self.hit(words, [$0.title ?? "", $0.artist ?? "", $0.album ?? "", ($0.artists ?? []).joined(separator: " ")])
        }

        List {
            field
                .listRowBackground(Color.clear)
                .listRowSeparator(.hidden)

            if !loaded {
                ProgressView().frame(maxWidth: .infinity).listRowBackground(Color.clear)
            } else if words.isEmpty {
                EmptyHint(text: "Ищи по трекам, артистам и альбомам сразу")
                    .listRowBackground(Color.clear)
            } else if foundArtists.isEmpty && foundAlbums.isEmpty && foundTracks.isEmpty {
                EmptyHint(text: "Ничего не найдено").listRowBackground(Color.clear)
            }

            if !foundArtists.isEmpty {
                Section {
                    ForEach(limited(foundArtists, "artists")) { ArtistListRow(artist: $0) }
                    more(foundArtists.count, key: "artists")
                } header: { header("Артисты") }
            }
            if !foundAlbums.isEmpty {
                Section {
                    ForEach(limited(foundAlbums, "albums")) { AlbumListRow(album: $0) }
                    more(foundAlbums.count, key: "albums")
                } header: { header("Альбомы") }
            }
            if !foundTracks.isEmpty {
                Section {
                    let shown = limited(foundTracks, "tracks")
                    ForEach(Array(shown.enumerated()), id: \.element.id) { i, t in
                        TrackRow(track: t) { play(shown, at: i) }
                            .listRowBackground(Color.clear)
                    }
                    more(foundTracks.count, key: "tracks")
                } header: { header("Треки") }
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .scrollDismissesKeyboard(.interactively)
        .background(Theme.backdrop)
        .navigationTitle("Поиск")
        .navigationBarTitleDisplayMode(.inline)
        .task { await load() }
        .onAppear {
            guard !focusedOnce else { return }
            focusedOnce = true
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.45) { focused = true }
        }
        .onChange(of: query) { _ in expanded = [] }
    }

    // MARK: Parts

    private var field: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass").foregroundStyle(Theme.muted)
            TextField("Треки, артисты, альбомы", text: $query)
                .focused($focused)
                .submitLabel(.search)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
            if !query.isEmpty {
                Button { query = "" } label: {
                    Image(systemName: "xmark.circle.fill").foregroundStyle(Theme.muted)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Очистить")
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 11)
        .background(Theme.surfaceHigh, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
    }

    private func header(_ title: String) -> some View {
        Text(title).font(.title3.weight(.bold)).foregroundStyle(Color.primary).textCase(nil)
    }

    private func limited<T>(_ list: [T], _ key: String) -> [T] {
        expanded.contains(key) ? list : Array(list.prefix(key == "tracks" ? Self.previewCount * 2 : Self.previewCount))
    }

    @ViewBuilder
    private func more(_ count: Int, key: String) -> some View {
        let limit = key == "tracks" ? Self.previewCount * 2 : Self.previewCount
        if count > limit {
            Button {
                withAnimation { _ = expanded.insert(key) }
            } label: {
                HStack {
                    Text(expanded.contains(key) ? "Показаны все · \(count)" : "Показать все · \(count)")
                    Spacer()
                    if !expanded.contains(key) { Image(systemName: "chevron.down").font(.caption.weight(.semibold)) }
                }
                .foregroundStyle(Theme.accent)
            }
            .disabled(expanded.contains(key))
            .listRowBackground(Color.clear)
        }
    }

    // MARK: Data

    private func play(_ list: [Track], at index: Int) {
        let window = list[index..<min(list.count, index + 500)]
        player.play(["track_ids": window.map(\.id), "name": "Поиск: \(query)"])
    }

    private func load() async {
        guard !loaded else { return }
        let api = app.api
        async let t = try? api.library()
        async let al = try? api.albums()
        tracks = await t ?? []
        artists = LibraryView.groupArtists(tracks)
        albums = await al ?? []
        loaded = true
    }

    // MARK: Matching

    private static func fold(_ s: String) -> String {
        s.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: nil)
    }

    private static func words(_ q: String) -> [String] {
        fold(q).split(whereSeparator: { $0.isWhitespace }).map(String.init)
    }

    private static func hit(_ words: [String], _ fields: [String]) -> Bool {
        let hay = fold(fields.joined(separator: " "))
        return words.allSatisfy { hay.contains($0) }
    }
}
