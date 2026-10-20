import SwiftUI
import UIKit

/// Loads artwork with auth headers (AsyncImage cannot send a Bearer token).
/// Decoded images stay in memory; bytes are also kept by the URLSession disk cache,
/// which honours the server's long `Cache-Control`.
final class ImageLoader: @unchecked Sendable {
    static let shared = ImageLoader()

    private let cache = NSCache<NSURL, UIImage>()
    private var missing = Set<URL>()
    private let lock = NSLock()
    weak var api: APIClient?

    init() {
        cache.countLimit = 400
    }

    func cached(_ url: URL) -> UIImage? { cache.object(forKey: url as NSURL) }

    func image(_ url: URL) async -> UIImage? {
        if let img = cached(url) { return img }
        let known404 = lock.withLock { missing.contains(url) }
        guard !known404, let api else { return nil }
        do {
            let data = try await api.data(from: url)
            // Decode here, off the main thread; otherwise the JPEG is decoded lazily on the
            // first draw, which is what makes a scrolling list hitch.
            guard let raw = UIImage(data: data) else { return nil }
            let img = raw.preparingForDisplay() ?? raw
            cache.setObject(img, forKey: url as NSURL)
            return img
        } catch let e as APIError where e.isNotFound {
            _ = lock.withLock { missing.insert(url) }
            return nil
        } catch {
            return nil
        }
    }

    func clear() {
        cache.removeAllObjects()
        lock.withLock { missing.removeAll() }
    }
}

/// Square cover for a track id. Sizes follow the server buckets: 96 / 256 / 640.
struct ArtworkView: View {
    let trackId: Int?
    var size: Int = 256
    var fallback: String = "♪"
    var cornerRadius: CGFloat = 10

    @State private var image: UIImage?

    var body: some View {
        // A square frame first, the picture laid over it and clipped: a wide or tall
        // cover (some files carry a whole collage) fills the square instead of
        // stretching the row and spilling over the text next to it.
        Color.clear
            .aspectRatio(1, contentMode: .fit)
            .overlay { content }
            .clipShape(RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
            .contentShape(RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
            .task(id: trackId) { await load() }
    }

    @ViewBuilder
    private var content: some View {
        ZStack {
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
                    .frame(minWidth: 0, maxWidth: .infinity, minHeight: 0, maxHeight: .infinity)
                    .clipped()
                    .transition(.opacity)
            } else {
                LinearGradient(colors: Theme.placeholder(for: trackId ?? 0),
                               startPoint: .topLeading, endPoint: .bottomTrailing)
                Text(fallback.prefix(1).uppercased())
                    .font(.system(size: CGFloat(size) / 4, weight: .bold, design: .rounded))
                    .foregroundStyle(.white.opacity(0.55))
                    .minimumScaleFactor(0.2)
                    .padding(6)
            }
        }
    }

    private func load() async {
        image = nil
        guard let trackId else { return }
        if OfflineStore.shared.isSaved(trackId),
           let local = await OfflineStore.savedArtwork(trackId, size: size) {
            image = local
            return
        }
        guard let url = ImageLoader.shared.api?.artworkURL(trackId: trackId, width: size) else { return }
        if let hit = ImageLoader.shared.cached(url) {
            image = hit
            return
        }
        let loaded = await ImageLoader.shared.image(url)
        withAnimation(.easeOut(duration: 0.25)) { image = loaded }
    }
}
