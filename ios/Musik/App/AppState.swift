import Foundation
import SwiftUI
import UIKit

/// App-wide state: login gate, toasts and favorite sets shared by every screen.
@MainActor
final class AppState: ObservableObject {
    enum Phase { case checking, login, ready }

    @Published var phase: Phase = .checking
    @Published var toast: String?
    @Published var favoriteTracks: Set<Int> = []
    @Published var favoriteArtists: Set<String> = []
    @Published var favoriteAlbums: Set<String> = []
    /// Bumped when shelves should reload (favorites or mixes changed).
    @Published var homeRevision = 0
    /// A screen to open from outside the tabs (the player sheet): MainView closes the
    /// player and pushes it onto the current tab.
    @Published var pendingRoute: Route?
    /// Started without a connection to the server: only saved tracks are available.
    @Published var offlineLaunch = false
    /// A shared link (musik://…) waiting for the main screen to open it.
    @Published var pendingLink: DeepLink?

    enum DeepLink: Equatable {
        case track(Int)
        case route(Route)
    }

    let settings: Settings
    let api: APIClient
    let player: PlayerController
    let offline: OfflineStore

    private var toastTask: Task<Void, Never>?

    init() {
        let settings = Settings.shared
        let api = APIClient(settings: settings)
        self.settings = settings
        self.api = api
        let offline = OfflineStore.shared
        self.offline = offline
        self.player = PlayerController(api: api, settings: settings, offline: offline)
        ImageLoader.shared.api = api
        offline.api = api
        AppToast.app = self
        player.app = self
        api.onUnauthorized = { [weak self] in self?.handleUnauthorized() }
    }

    // MARK: Share links

    /// musik://track/42, musik://artist?name=…, musik://album?artist=…&album=…
    /// (the server's /open/… page hands its links over in this form).
    func open(_ url: URL) {
        guard url.scheme?.lowercased() == "musik" else { return }
        let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
        func value(_ name: String) -> String? { items.first { $0.name == name }?.value.nonEmpty }
        switch url.host?.lowercased() {
        case "track":
            if let id = Int(url.lastPathComponent) { pendingLink = .track(id) }
        case "artist":
            if let name = value("name") { pendingLink = .route(.artist(name)) }
        case "album":
            if let album = value("album") { pendingLink = .route(.album(artist: value("artist"), album: album)) }
        default:
            break
        }
    }

    func shareURL(track: Track) -> URL? {
        var items: [URLQueryItem] = [.init(name: "t", value: track.displayTitle)]
        if let artist = track.artist.nonEmpty { items.append(.init(name: "a", value: artist)) }
        return shareURL("track/\(track.id)", items)
    }

    func shareURL(artist: String) -> URL? {
        shareURL("artist", [.init(name: "name", value: artist)])
    }

    func shareURL(artist: String?, album: String) -> URL? {
        var items: [URLQueryItem] = [.init(name: "album", value: album)]
        if let artist = artist.nonEmpty { items.append(.init(name: "artist", value: artist)) }
        return shareURL("album", items)
    }

    private func shareURL(_ path: String, _ items: [URLQueryItem]) -> URL? {
        guard var c = URLComponents(string: settings.baseURL + "/open/" + path) else { return nil }
        c.queryItems = items
        return c.url
    }

    // MARK: Boot & auth

    func bootstrap() async {
        guard !settings.baseURL.isEmpty else {
            phase = .login
            return
        }
        do {
            // A short wait: with no signal the answer will not come, and the launch
            // screen must not hang for the full request timeout.
            let me = try await api.authMe(timeout: 6)
            if me.ok || me.authEnabled == false {
                settings.signedInBefore = true
                phase = .ready
                await refreshFavorites()
                Task { await refreshArtistPhotos(force: true) }
                await player.restore()
            } else {
                phase = .login
            }
        } catch let e as APIError where Self.serverUnreachable(e) && wasSignedIn {
            // No connection (or the server / tunnel is down), but we were signed in
            // before: open the app with what is saved on the phone instead of the login
            // screen, and keep trying to reach the server in the background.
            offlineLaunch = true
            phase = .ready
            show("Нет связи с сервером — играют сохранённые треки")
            startReconnectLoop()
        } catch {
            // Server unreachable: still show the login screen with the saved URL.
            phase = .login
            show(error)
        }
    }

    /// The request never got an answer (no network, timeout, bad gateway from the tunnel).
    /// Not a 401: a rejected token still goes to the login screen.
    private static func serverUnreachable(_ e: APIError) -> Bool {
        e.status == 0 || e.status >= 500
    }

    /// This install has been signed in before: a token, a remembered sign-in, or a cookie.
    private var wasSignedIn: Bool {
        if settings.token.nonEmpty != nil || settings.signedInBefore { return true }
        if let url = URL(string: settings.baseURL),
           !(HTTPCookieStorage.shared.cookies(for: url) ?? []).isEmpty { return true }
        return false
    }

    private var reconnectTask: Task<Void, Never>?

    /// After an offline launch: checks every 15 s whether the server answers again.
    private func startReconnectLoop() {
        reconnectTask?.cancel()
        reconnectTask = Task { [weak self] in
            while let self, !Task.isCancelled, self.offlineLaunch {
                try? await Task.sleep(nanoseconds: 15_000_000_000)
                guard !Task.isCancelled else { return }
                await self.reconnect()
            }
        }
    }

    /// Called when the connection comes back after an offline launch.
    func reconnect() async {
        guard offlineLaunch, let me = try? await api.authMe(timeout: 6), me.ok || me.authEnabled == false else { return }
        settings.signedInBefore = true
        offlineLaunch = false
        homeRevision += 1
        await refreshFavorites()
        await refreshArtistPhotos(force: true)
    }

    enum LoginMode: String, CaseIterable, Identifiable {
        case account = "Логин и пароль"
        case token = "Токен"
        var id: String { rawValue }
    }

    func login(baseURL: String, mode: LoginMode, username: String, secret: String) async throws {
        settings.baseURL = baseURL
        let secret = secret.trimmingCharacters(in: .whitespacesAndNewlines)
        let username = username.trimmingCharacters(in: .whitespacesAndNewlines)
        switch mode {
        case .token:
            settings.token = secret
        case .account where username.isEmpty:
            // Single-owner server: one password, cookie session.
            settings.token = nil
            try await api.login(password: secret)
        case .account:
            settings.token = nil
            let device = "musik iOS · \(UIDevice.current.name)"
            do {
                settings.token = try await api.issueToken(username: username, password: secret, deviceName: device)
            } catch let e as APIError where [404, 405, 409].contains(e.status) {
                // Older server, or one without device tokens: sign in like the website does.
                try await api.login(username: username, password: secret)
            }
        }
        let me: AuthMe
        do {
            me = try await api.authMe()
        } catch let e as APIError where e.status != 0 {
            throw APIError(status: e.status, code: e.code, message: "Вход не удался: \(e.message)")
        }
        guard me.ok || me.authEnabled == false else {
            settings.token = nil
            throw APIError(status: 401, code: "auth_required",
                           message: mode == .token ? "Сервер не принял токен" : "Неверное имя или пароль")
        }
        settings.signedInBefore = true
        phase = .ready
        await refreshFavorites()
        await refreshArtistPhotos(force: true)
    }

    func logout() async {
        player.stop(clearSession: true)
        await api.logout()
        settings.token = nil
        settings.signedInBefore = false
        reconnectTask?.cancel()
        HTTPCookieStorage.shared.cookies?.forEach { HTTPCookieStorage.shared.deleteCookie($0) }
        ImageLoader.shared.clear()
        ArtistPhotos.shared.clear()
        favoriteTracks = []
        favoriteArtists = []
        favoriteAlbums = []
        offlineLaunch = false
        phase = .login
    }

    private func handleUnauthorized() {
        guard phase == .ready else { return }
        player.stop(clearSession: false)
        phase = .login
        show("Сессия истекла — войди снова")
    }

    // MARK: Favorites

    func refreshArtistPhotos(force: Bool = false) async {
        await ArtistPhotos.shared.refresh(api, force: force)
    }

    func refreshFavorites() async {
        guard let fav = try? await api.favorites() else { return }
        offline.syncFavorites((fav.tracks ?? []).map(\.asTrack))
        favoriteTracks = Set((fav.tracks ?? []).map(\.trackId))
        favoriteArtists = Set((fav.artists ?? []).map(\.artist))
        favoriteAlbums = Set((fav.albums ?? []).map(\.id))
    }

    func isFavorite(album: AlbumItem) -> Bool { favoriteAlbums.contains(album.id) }

    /// Toggles a track heart; returns the new state.
    @discardableResult
    func toggleFavoriteTrack(_ id: Int, track: Track? = nil) async -> Bool? {
        do {
            let r = try await api.toggleFavorite(["type": "track", "track_id": id])
            if r.favorited { favoriteTracks.insert(id) } else { favoriteTracks.remove(id) }
            offline.favoriteChanged(track ?? Track(id: id), favorited: r.favorited)
            show(r.favorited ? "Любимая песня" : "Песня убрана из любимых")
            homeRevision += 1
            return r.favorited
        } catch {
            show(error)
            return nil
        }
    }

    func toggleFavoriteArtist(_ artist: String) async {
        do {
            let r = try await api.toggleFavorite(["type": "artist", "artist": artist])
            if r.favorited { favoriteArtists.insert(artist) } else { favoriteArtists.remove(artist) }
            show(r.favorited ? "Любимый артист" : "Артист убран")
            homeRevision += 1
        } catch {
            show(error)
        }
    }

    func toggleFavoriteAlbum(artist: String?, album: String) async {
        do {
            let r = try await api.toggleFavorite(["type": "album", "artist": artist ?? "", "album": album])
            let key = albumKey(artist, album)
            if r.favorited { favoriteAlbums.insert(key) } else { favoriteAlbums.remove(key) }
            show(r.favorited ? "Любимый альбом" : "Альбом убран")
            homeRevision += 1
        } catch {
            show(error)
        }
    }

    func addLater(_ trackId: Int) async {
        do {
            try await api.addLater(trackId: trackId)
            show("В «Потом»")
            homeRevision += 1
        } catch {
            show(error)
        }
    }

    // MARK: Toasts

    func show(_ message: String) {
        toastTask?.cancel()
        withAnimation(.spring(response: 0.3, dampingFraction: 0.85)) { toast = message }
        toastTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 2_600_000_000)
            guard !Task.isCancelled else { return }
            withAnimation(.easeOut(duration: 0.3)) { self?.toast = nil }
        }
    }

    func show(_ error: Error) {
        if error is CancellationError { return }
        show(error.localizedDescription)
    }

    /// Runs an async action and turns any error into a toast.
    func perform(_ action: @escaping () async throws -> Void) {
        Task {
            do { try await action() } catch { show(error) }
        }
    }
}
