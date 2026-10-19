import Foundation

/// Typed wrappers for the routes the client uses (see docs/API.md).
extension APIClient {
    // Auth & status
    func health() async throws -> Health { try await get("/api/health") }

    func authMe() async throws -> AuthMe {
        try decode(AuthMe.self, from: try await send("GET", "/api/auth/me", redirectOn401: false))
    }

    func login(password: String) async throws {
        _ = try await send("POST", "/api/auth/login", body: ["password": password], redirectOn401: false)
    }

    /// Web-style sign-in (session cookie) for servers that can't issue device tokens.
    func login(username: String, password: String) async throws {
        _ = try await send("POST", "/api/auth/login", body: ["username": username, "password": password],
                           redirectOn401: false)
    }

    /// Multi-user sign-in: username + password give a personal device token
    /// (the same kind the web UI issues under account → device tokens).
    func issueToken(username: String, password: String, deviceName: String) async throws -> String {
        struct Issued: Decodable { var token: String? }
        let data = try await send("POST", "/api/auth/token", body: [
            "username": username, "password": password, "device_name": deviceName,
        ], redirectOn401: false)
        guard let token = try decode(Issued.self, from: data).token, !token.isEmpty else {
            throw APIError(status: 0, code: "no_token", message: "Сервер не выдал токен")
        }
        return token
    }

    /// Quick reachability check with a short timeout (the offline player polls it).
    func ping() async -> Bool {
        guard let target = try? url("/api/health") else { return false }
        var req = URLRequest(url: target, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 6)
        for (k, v) in authHeaders() { req.setValue(v, forHTTPHeaderField: k) }
        guard let result = try? await session.data(for: req) else { return false }
        let status = (result.1 as? HTTPURLResponse)?.statusCode ?? 0
        return status > 0 && status < 500
    }

    func logout() async {
        _ = try? await send("POST", "/api/auth/logout", redirectOn401: false)
    }

    func profile() async throws -> Profile { try await get("/api/profile") }

    // Catalog
    func library(artist: String? = nil, album: String? = nil, limit: Int? = nil) async throws -> [Track] {
        var q: [URLQueryItem] = []
        if let artist { q.append(.init(name: "artist", value: artist)) }
        if let album { q.append(.init(name: "album", value: album)) }
        if let limit { q.append(.init(name: "limit", value: String(limit))) }
        return try await get("/api/library", query: q)
    }

    func artists(limit: Int? = nil) async throws -> [ArtistItem] {
        let q = limit.map { [URLQueryItem(name: "limit", value: String($0))] } ?? []
        return try await get("/api/artists", query: q, as: ArtistsResponse.self).artists ?? []
    }

    func albums(limit: Int? = nil) async throws -> [AlbumItem] {
        let q = limit.map { [URLQueryItem(name: "limit", value: String($0))] } ?? []
        return try await get("/api/albums", query: q, as: AlbumsResponse.self).albums ?? []
    }

    func lyrics(trackId: Int) async throws -> Lyrics { try await get("/api/tracks/\(trackId)/lyrics") }

    // Shelves
    func mixes() async throws -> [Mix] { try await get("/api/mixes", as: MixesResponse.self).mixes }
    func favorites() async throws -> FavoritesResponse { try await get("/api/favorites") }
    func recommendFavorites() async throws -> RecommendResponse { try await get("/api/recommend/favorites") }

    func recommendSeed(trackId: Int, limit: Int = 20) async throws -> [Track] {
        try await get("/api/recommend/seed", query: [
            .init(name: "type", value: "track"),
            .init(name: "track_id", value: String(trackId)),
            .init(name: "limit", value: String(limit)),
        ], as: RecommendResponse.self).tracks ?? []
    }

    func similarArtists(_ artist: String) async throws -> [ArtistItem] {
        try await get("/api/similar/artists", query: [.init(name: "artist", value: artist)],
                      as: ArtistsResponse.self).artists ?? []
    }

    func similarAlbums(artist: String?, album: String) async throws -> [AlbumItem] {
        try await get("/api/similar/albums", query: [
            .init(name: "artist", value: artist ?? ""), .init(name: "album", value: album),
        ], as: AlbumsResponse.self).albums ?? []
    }

    func discover(resurfaced: Bool) async throws -> [DiscoverTip] {
        let path = resurfaced ? "/api/discover/resurfaced" : "/api/discover/albums"
        return try await get(path, as: DiscoverResponse.self).tips ?? []
    }

    // Library actions
    func toggleFavorite(_ body: [String: Any]) async throws -> FavoriteToggleResult {
        try await post("/api/favorites/toggle", body)
    }

    // Radio rules («Скрыть», «Реже»): the server keeps them out of radio, mixes and recommendations.
    func createRule(target: String, action: String, track: Track, sessionId: String?,
                    strength: Double = 1) async throws -> RuleCreated {
        var body: [String: Any] = [
            "target_type": target, "action": action, "scope": "global", "preset": "forever",
            "track_id": track.id, "artist": track.artist ?? "", "album": track.album ?? "",
            "strength": strength,
        ]
        if let sessionId { body["session_id"] = sessionId }
        return try await post("/api/rules", body)
    }

    func rules() async throws -> [RadioRule] {
        try await get("/api/rules", as: RulesResponse.self).rules
    }

    func deleteRule(_ id: String) async throws {
        _ = try await send("DELETE", "/api/rules/\(id)")
    }

    func addLater(trackId: Int) async throws {
        _ = try await send("POST", "/api/later", body: ["track_id": trackId])
    }

    // Jobs
    func enqueueJob(_ kind: String) async throws -> Int? {
        let ref: JobRef = try await post("/api/jobs/\(kind)")
        return ref.id ?? ref.jobId
    }

    func job(_ id: Int) async throws -> JobStatus { try await get("/api/jobs/\(id)") }

    // Share radio
    func shares() async throws -> [ShareLink] { try await get("/api/share/radio", as: SharesResponse.self).shares ?? [] }
    func createShare() async throws -> ShareCreated { try await post("/api/share/radio", ["name": "musik radio"]) }

    func revokeShare(_ token: String) async throws {
        let escaped = token.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? token
        _ = try await send("DELETE", "/api/share/radio/\(escaped)")
    }
}
