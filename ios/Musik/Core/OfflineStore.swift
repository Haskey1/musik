import Foundation
import UIKit

/// A track kept on the phone for listening without a connection.
struct SavedTrack: Codable, Identifiable, Hashable {
    var id: Int
    var artist: String?
    var title: String?
    var album: String?
    var duration: Double?
    /// File name inside the offline folder ("123.m4a").
    var file: String
    var bytes: Int64
    var savedAt: Date
    /// Saved automatically because the track is liked; removed again when the like goes.
    var auto: Bool

    var track: Track {
        Track(id: id, artist: artist, title: title, album: album, duration: duration)
    }
}

/// Audio files on the device, in two places:
///
/// * **Saved** (Application Support/Offline): tracks the user saved or liked. Kept until
///   removed, listed on the «Сохранённые» tab, played when there is no connection.
/// * **Prefetch** (Caches/Prefetch): the next couple of tracks of the current radio or
///   playlist, downloaded ahead so a dropped connection does not stop playback. A small
///   rolling cache; iOS may also clear it when space runs low.
///
/// Files are fetched with the same stream URL as playback (mobile quality when that
/// setting is on), so a saved track sounds exactly like a streamed one.
@MainActor
final class OfflineStore: ObservableObject {
    static let shared = OfflineStore()

    @Published private(set) var saved: [Int: SavedTrack] = [:]
    @Published private(set) var downloading: Set<Int> = []
    @Published var autoSaveFavorites: Bool {
        didSet { UserDefaults.standard.set(autoSaveFavorites, forKey: "autoSaveFavorites") }
    }

    weak var api: APIClient?

    private let savedDir: URL
    private let prefetchDir: URL
    private var prefetched: [Int: URL] = [:]
    private var prefetching: Set<Int> = []
    private var pending: [(track: Track, auto: Bool)] = []
    private var worker: Task<Void, Never>?
    private let prefetchLimit = 8

    private init() {
        let fm = FileManager.default
        let support = fm.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let caches = fm.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        savedDir = support.appendingPathComponent("Offline", isDirectory: true)
        prefetchDir = caches.appendingPathComponent("Prefetch", isDirectory: true)
        try? fm.createDirectory(at: savedDir, withIntermediateDirectories: true)
        try? fm.createDirectory(at: prefetchDir, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var dir = savedDir
        try? dir.setResourceValues(values)

        autoSaveFavorites = UserDefaults.standard.object(forKey: "autoSaveFavorites") as? Bool ?? true
        loadIndex()
        for url in (try? fm.contentsOfDirectory(at: prefetchDir, includingPropertiesForKeys: nil)) ?? [] {
            if let id = Int(url.deletingPathExtension().lastPathComponent) { prefetched[id] = url }
        }
    }

    // MARK: Queries

    func isSaved(_ id: Int) -> Bool { saved[id] != nil }

    /// Saved tracks, newest first.
    var savedTracks: [SavedTrack] { saved.values.sorted { $0.savedAt > $1.savedAt } }

    var totalBytes: Int64 { saved.values.reduce(0) { $0 + $1.bytes } }

    /// A local file for this track, saved or prefetched; nil when it has to be streamed.
    func playableURL(_ id: Int) -> URL? {
        if let s = saved[id] {
            let url = savedDir.appendingPathComponent(s.file)
            if FileManager.default.fileExists(atPath: url.path) { return url }
        }
        if let url = prefetched[id], FileManager.default.fileExists(atPath: url.path) { return url }
        return nil
    }

    func artwork(_ id: Int) -> UIImage? {
        let url = savedDir.appendingPathComponent("\(id).jpg")
        guard FileManager.default.fileExists(atPath: url.path) else { return nil }
        return UIImage(contentsOfFile: url.path)
    }

    // MARK: Saving

    func save(_ track: Track, auto: Bool = false) {
        if var s = saved[track.id] {
            if !auto && s.auto {  // an explicit save keeps the file even after an unlike
                s.auto = false
                saved[track.id] = s
                writeIndex()
            }
            return
        }
        guard !downloading.contains(track.id), !pending.contains(where: { $0.track.id == track.id }) else { return }
        pending.append((track, auto))
        runQueue()
    }

    func remove(_ id: Int) {
        guard let s = saved.removeValue(forKey: id) else { return }
        try? FileManager.default.removeItem(at: savedDir.appendingPathComponent(s.file))
        try? FileManager.default.removeItem(at: savedDir.appendingPathComponent("\(id).jpg"))
        writeIndex()
    }

    func removeAll() {
        for id in Array(saved.keys) { remove(id) }
    }

    /// Liked tracks follow the like: saved when liked, removed when unliked
    /// (only if they were saved because of the like, not by hand).
    func favoriteChanged(_ track: Track, favorited: Bool) {
        guard autoSaveFavorites else { return }
        if favorited {
            save(track, auto: true)
        } else if saved[track.id]?.auto == true {
            remove(track.id)
        }
    }

    /// Brings saved likes in line with the server's list of favorites.
    func syncFavorites(_ tracks: [Track]) {
        guard autoSaveFavorites else { return }
        let ids = Set(tracks.map(\.id))
        for t in tracks where saved[t.id] == nil { save(t, auto: true) }
        for s in saved.values where s.auto && !ids.contains(s.id) { remove(s.id) }
    }

    private func runQueue() {
        guard worker == nil else { return }
        worker = Task { [weak self] in
            while let self, !self.pending.isEmpty {
                let job = self.pending.removeFirst()
                self.downloading.insert(job.track.id)
                await self.download(job.track, auto: job.auto)
                self.downloading.remove(job.track.id)
            }
            self?.worker = nil
        }
    }

    private func download(_ track: Track, auto: Bool) async {
        // Already fetched ahead for playback: keep that file instead of downloading again.
        if let cached = prefetched[track.id], FileManager.default.fileExists(atPath: cached.path) {
            let dest = savedDir.appendingPathComponent(cached.lastPathComponent)
            try? FileManager.default.removeItem(at: dest)
            if (try? FileManager.default.moveItem(at: cached, to: dest)) != nil {
                prefetched[track.id] = nil
                await finishSave(track, file: dest, auto: auto)
                return
            }
        }
        guard let file = await fetchAudio(track, into: savedDir) else {
            if !auto { AppToast.show("Не удалось сохранить «\(track.displayTitle)»") }
            return
        }
        await finishSave(track, file: file, auto: auto)
    }

    private func finishSave(_ track: Track, file: URL, auto: Bool) async {
        let bytes = (try? FileManager.default.attributesOfItem(atPath: file.path)[.size] as? Int64) ?? 0
        saved[track.id] = SavedTrack(id: track.id, artist: track.artist, title: track.title, album: track.album,
                                     duration: track.duration, file: file.lastPathComponent,
                                     bytes: bytes, savedAt: Date(), auto: auto)
        writeIndex()
        if !auto { AppToast.show("Сохранено: «\(track.displayTitle)»") }
        await fetchArtwork(track.id)
    }

    private func fetchArtwork(_ id: Int) async {
        guard let api, let url = api.artworkURL(trackId: id, width: 640),
              let data = try? await api.data(from: url) else { return }
        try? data.write(to: savedDir.appendingPathComponent("\(id).jpg"))
        objectWillChange.send()
    }

    // MARK: Prefetch

    /// Downloads upcoming tracks so the next one plays even if the connection drops.
    func prefetch(_ tracks: [Track]) {
        for track in tracks where playableURL(track.id) == nil && !prefetching.contains(track.id) {
            prefetching.insert(track.id)
            Task { [weak self] in
                guard let self else { return }
                if let file = await self.fetchAudio(track, into: self.prefetchDir) {
                    self.prefetched[track.id] = file
                    self.trimPrefetch(keeping: Set(tracks.map(\.id)))
                }
                self.prefetching.remove(track.id)
            }
        }
    }

    private func trimPrefetch(keeping: Set<Int>) {
        guard prefetched.count > prefetchLimit else { return }
        let fm = FileManager.default
        let byAge = prefetched.sorted { a, b in
            let da = (try? fm.attributesOfItem(atPath: a.value.path)[.modificationDate] as? Date) ?? .distantPast
            let db = (try? fm.attributesOfItem(atPath: b.value.path)[.modificationDate] as? Date) ?? .distantPast
            return da < db
        }
        for (id, url) in byAge where prefetched.count > prefetchLimit && !keeping.contains(id) {
            try? fm.removeItem(at: url)
            prefetched[id] = nil
        }
    }

    // MARK: Network

    /// Downloads the track's stream into `dir` as "<id>.<ext>"; the extension comes from
    /// the response type, because AVPlayer recognises local files by it.
    private func fetchAudio(_ track: Track, into dir: URL) async -> URL? {
        guard let api, let url = api.streamURL(for: track) else { return nil }
        var req = URLRequest(url: url)
        req.timeoutInterval = 60
        for (k, v) in api.authHeaders() { req.setValue(v, forHTTPHeaderField: k) }
        do {
            let (tmp, resp) = try await api.session.download(for: req)
            guard let http = resp as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
                try? FileManager.default.removeItem(at: tmp)
                return nil
            }
            let ext = Self.fileExtension(mime: http.mimeType, url: url)
            let dest = dir.appendingPathComponent("\(track.id).\(ext)")
            try? FileManager.default.removeItem(at: dest)
            try FileManager.default.moveItem(at: tmp, to: dest)
            return dest
        } catch {
            return nil
        }
    }

    nonisolated static func fileExtension(mime: String?, url: URL) -> String {
        switch (mime ?? "").lowercased() {
        case "audio/mpeg", "audio/mp3": return "mp3"
        case "audio/mp4", "audio/m4a", "audio/x-m4a", "audio/aac", "audio/aacp": return "m4a"
        case "audio/flac", "audio/x-flac": return "flac"
        case "audio/ogg", "application/ogg", "audio/opus": return "ogg"
        case "audio/wav", "audio/x-wav", "audio/wave": return "wav"
        default:
            let ext = url.pathExtension.lowercased()
            return ext.isEmpty ? "m4a" : ext
        }
    }

    // MARK: Index

    private var indexURL: URL { savedDir.appendingPathComponent("index.json") }

    private func loadIndex() {
        guard let data = try? Data(contentsOf: indexURL),
              let list = try? JSONDecoder().decode([SavedTrack].self, from: data) else { return }
        let fm = FileManager.default
        saved = Dictionary(uniqueKeysWithValues: list
            .filter { fm.fileExists(atPath: savedDir.appendingPathComponent($0.file).path) }
            .map { ($0.id, $0) })
    }

    private func writeIndex() {
        guard let data = try? JSONEncoder().encode(Array(saved.values)) else { return }
        try? data.write(to: indexURL, options: .atomic)
    }
}

/// Lets non-view code (the store, the player) raise a toast without holding AppState.
@MainActor
enum AppToast {
    static weak var app: AppState?
    static func show(_ text: String) { app?.show(text) }
}

func formatBytes(_ bytes: Int64) -> String {
    ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
}
