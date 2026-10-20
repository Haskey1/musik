import Foundation
import SwiftUI

/// Artist photos the server found online (`musik artist-photos`): normalized artist
/// name → image path. Artists without a photo keep showing a track cover.
@MainActor
final class ArtistPhotos: ObservableObject {
    static let shared = ArtistPhotos()

    @Published private(set) var paths: [String: String] = [:]
    private var loadedAt: Date?

    private static let storeKey = "artistPhotos.paths"

    private init() {
        // Last known list, so photos show up at once on launch (and offline, from the
        // image cache) before the fresh list arrives.
        if let saved = UserDefaults.standard.dictionary(forKey: Self.storeKey) as? [String: String] {
            paths = saved
        }
    }

    /// Same rule as the server and the web UI: spaces collapsed, lower case.
    nonisolated static func key(_ name: String) -> String {
        name.split(whereSeparator: \.isWhitespace).joined(separator: " ").lowercased()
    }

    func url(for artist: String?, width: Int, api: APIClient?) -> URL? {
        guard let artist, let path = paths[Self.key(artist)], let api else { return nil }
        return try? api.url(path, query: [URLQueryItem(name: "w", value: String(width))])
    }

    /// Fetches the list; at most once in ten minutes unless forced.
    func refresh(_ api: APIClient, force: Bool = false) async {
        if !force, let loadedAt, Date().timeIntervalSince(loadedAt) < 600 { return }
        guard let fresh = try? await api.artistPhotos() else { return }
        loadedAt = Date()
        guard fresh != paths else { return }
        paths = fresh
        UserDefaults.standard.set(fresh, forKey: Self.storeKey)
    }

    func clear() {
        paths = [:]
        loadedAt = nil
        UserDefaults.standard.removeObject(forKey: Self.storeKey)
    }
}

extension APIClient {
    /// `GET /api/artist-photos` → name key → path. Read with JSONSerialization: the
    /// shared decoder's snake_case conversion would rewrite names with underscores.
    func artistPhotos() async throws -> [String: String] {
        let data = try await send("GET", "/api/artist-photos")
        let object = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        return object?["photos"] as? [String: String] ?? [:]
    }
}

/// An artist's picture: the photo when the server has one, else a track cover.
/// Observes the photo list, so it switches over when the list arrives.
struct ArtistAvatar: View {
    @ObservedObject private var photos = ArtistPhotos.shared
    let artist: String
    let coverTrackId: Int?
    var size: Int = 256
    var cornerRadius: CGFloat = 60

    var body: some View {
        // The URL is worked out here, in the view that observes the list, and handed
        // down as a value: ArtworkView is redrawn (and reloads) only when it changes.
        ArtworkView(trackId: coverTrackId, size: size, fallback: artist, cornerRadius: cornerRadius,
                    photo: photos.url(for: artist, width: size, api: ImageLoader.shared.api))
    }
}
