import SwiftUI
import UIKit
import Vision
import ImageIO

/// Chooses a real artist photo for the home radio hero only when Vision can
/// find a person in it. Logos and cover-like graphics fall back to the current
/// track artwork, which is blurred into an ambient background instead.
struct RadioArtistBackdrop: View {
    @ObservedObject private var photos = ArtistPhotos.shared

    let artist: String?
    let fallbackTrackId: Int?

    @State private var artistImage: UIImage?
    @State private var fallbackImage: UIImage?
    @State private var suitable = false
    @State private var placement: HeroPlacement = .center

    private var artistURL: URL? {
        photos.fullURL(for: artist, api: ImageLoader.shared.api)
    }

    var body: some View {
        ZStack {
            LinearGradient(
                colors: [Theme.accent.opacity(0.92), Theme.surfaceHigh, Theme.teal.opacity(0.72)],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )

            if suitable, let artistImage {
                Image(uiImage: artistImage)
                    .resizable()
                    .scaledToFill()
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: placement.alignment)
                    .clipped()
                    .transition(.opacity)
            } else if let fallbackImage {
                Image(uiImage: fallbackImage)
                    .resizable()
                    .scaledToFill()
                    .blur(radius: 24)
                    .scaleEffect(1.18)
                    .opacity(0.72)
                    .transition(.opacity)
            }

            // The left side is always protected for copy and controls. The second
            // fade melts any source image into the warm app surface.
            LinearGradient(
                stops: [
                    .init(color: Theme.surface.opacity(0.96), location: 0),
                    .init(color: Theme.surface.opacity(0.76), location: 0.38),
                    .init(color: Theme.surface.opacity(0.12), location: 0.72),
                    .init(color: .clear, location: 1),
                ],
                startPoint: .leading,
                endPoint: .trailing
            )
            LinearGradient(
                colors: [.clear, Theme.surface.opacity(0.2), Theme.surface.opacity(0.68)],
                startPoint: .top,
                endPoint: .bottom
            )
        }
        .task(id: LoadKey(artistURL: artistURL, fallbackTrackId: fallbackTrackId)) {
            await load()
        }
    }

    private struct LoadKey: Hashable {
        let artistURL: URL?
        let fallbackTrackId: Int?
    }

    @MainActor
    private func load() async {
        artistImage = nil
        fallbackImage = nil
        suitable = false
        placement = .center

        if let artistURL, let image = await ImageLoader.shared.image(artistURL) {
            let analysis = await Task.detached(priority: .utility) {
                HeroImageAnalysis.inspect(image)
            }.value
            if analysis.suitable {
                artistImage = image
                placement = analysis.placement
                withAnimation(.easeOut(duration: 0.35)) { suitable = true }
                return
            }
        }

        guard let fallbackTrackId,
              let url = ImageLoader.shared.api?.artworkURL(trackId: fallbackTrackId, width: 640),
              let image = await ImageLoader.shared.image(url) else { return }
        withAnimation(.easeOut(duration: 0.35)) { fallbackImage = image }
    }
}

private enum HeroPlacement {
    case leading, center, trailing

    var alignment: Alignment {
        switch self {
        case .leading: return .topLeading
        case .center: return .top
        case .trailing: return .topTrailing
        }
    }
}

private struct HeroImageAnalysis {
    let suitable: Bool
    let placement: HeroPlacement

    static func inspect(_ image: UIImage) -> HeroImageAnalysis {
        guard image.size.width >= 500, image.size.height >= 500,
              let cgImage = image.cgImage else {
            return HeroImageAnalysis(suitable: false, placement: .center)
        }

        let faces = VNDetectFaceRectanglesRequest()
        let people = VNDetectHumanRectanglesRequest()
        people.upperBodyOnly = false
        let handler = VNImageRequestHandler(cgImage: cgImage, orientation: image.cgOrientation)
        try? handler.perform([faces, people])

        let boxes = (faces.results ?? []).map(\.boundingBox)
            + (people.results ?? []).map(\.boundingBox)
        guard !boxes.isEmpty else {
            return HeroImageAnalysis(suitable: false, placement: .center)
        }

        let union = boxes.dropFirst().reduce(boxes[0]) { $0.union($1) }
        let center = union.midX
        let placement: HeroPlacement = center < 0.42 ? .leading : (center > 0.58 ? .trailing : .center)
        return HeroImageAnalysis(suitable: true, placement: placement)
    }
}

private extension UIImage {
    var cgOrientation: CGImagePropertyOrientation {
        switch imageOrientation {
        case .up: return .up
        case .upMirrored: return .upMirrored
        case .down: return .down
        case .downMirrored: return .downMirrored
        case .left: return .left
        case .leftMirrored: return .leftMirrored
        case .right: return .right
        case .rightMirrored: return .rightMirrored
        @unknown default: return .up
        }
    }
}
