import SwiftUI
import UIKit
import Vision
import ImageIO
import CoreImage

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
        GeometryReader { geometry in
        ZStack {
            LinearGradient(
                colors: [Color(hex: 0xA94218), Color(hex: 0xD96B2B), Color(hex: 0x486653)],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )

            Circle()
                .fill(Theme.cream.opacity(0.09))
                .frame(width: 300, height: 300)
                .offset(x: 170, y: -140)

            HazeTexture()
                .opacity(0.3)
                .blendMode(.screen)
            Ellipse()
                .fill(Theme.cream.opacity(0.22))
                .frame(width: geometry.size.width * 0.85, height: geometry.size.height * 0.55)
                .blur(radius: 45)
                .offset(x: geometry.size.width * 0.18, y: geometry.size.height * 0.16)

            if suitable, let artistImage {
                ZStack {
                    portrait(artistImage, width: geometry.size.width * 0.76,
                             height: geometry.size.height)
                        .blur(radius: 12)
                        .opacity(0.45)
                    portrait(artistImage, width: geometry.size.width * 0.76,
                             height: geometry.size.height)
                }
                    .mask {
                        LinearGradient(stops: [
                            .init(color: .clear, location: 0),
                            .init(color: .white, location: 0.22),
                            .init(color: .white, location: 0.9),
                            .init(color: .clear, location: 1),
                        ], startPoint: .leading, endPoint: .trailing)
                        .mask {
                            LinearGradient(stops: [
                                .init(color: .clear, location: 0),
                                .init(color: .white, location: 0.15),
                                .init(color: .white, location: 0.88),
                                .init(color: .clear, location: 1),
                            ], startPoint: .top, endPoint: .bottom)
                        }
                    }
                    .frame(width: geometry.size.width, height: geometry.size.height,
                           alignment: .bottomTrailing)
                    .transition(.opacity)
            } else if let fallbackImage {
                Image(uiImage: fallbackImage)
                    .resizable()
                    .scaledToFill()
                    .blur(radius: 24)
                    .scaleEffect(1.18)
                    .opacity(0.36)
                    .transition(.opacity)
            }

            LinearGradient(
                stops: [
                    .init(color: Color.black.opacity(0.46), location: 0),
                    .init(color: Color.black.opacity(0.22), location: 0.44),
                    .init(color: Color.black.opacity(0.02), location: 0.72),
                    .init(color: .clear, location: 1),
                ],
                startPoint: .leading,
                endPoint: .trailing
            )
            LinearGradient(
                colors: [.clear, Color.black.opacity(0.02), Color.black.opacity(0.42)],
                startPoint: .top,
                endPoint: .bottom
            )
        }
        .frame(width: geometry.size.width, height: geometry.size.height)
        .clipped()
        }
        .task(id: LoadKey(artistURL: artistURL, fallbackTrackId: fallbackTrackId)) {
            await load()
        }
    }

    private func portrait(_ image: UIImage, width: CGFloat, height: CGFloat) -> some View {
        Image(uiImage: image)
            .resizable()
            .scaledToFit()
            .mask {
                LinearGradient(stops: [
                    .init(color: .clear, location: 0),
                    .init(color: .white, location: 0.12),
                    .init(color: .white, location: 0.94),
                    .init(color: .clear, location: 1),
                ], startPoint: .top, endPoint: .bottom)
            }
            .frame(width: width, height: height, alignment: .bottomTrailing)
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
            if analysis.suitable, let cutout = analysis.cutout {
                artistImage = cutout
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
    let cutout: UIImage?

    static func inspect(_ image: UIImage) -> HeroImageAnalysis {
        guard image.size.width >= 500, image.size.height >= 500,
              let cgImage = image.cgImage else {
            return HeroImageAnalysis(suitable: false, placement: .center, cutout: nil)
        }

        let faces = VNDetectFaceRectanglesRequest()
        let people = VNDetectHumanRectanglesRequest()
        people.upperBodyOnly = false
        let segmentation = VNGeneratePersonSegmentationRequest()
        segmentation.qualityLevel = .balanced
        segmentation.outputPixelFormat = kCVPixelFormatType_OneComponent8
        let handler = VNImageRequestHandler(cgImage: cgImage, orientation: image.cgOrientation)
        try? handler.perform([faces, people, segmentation])

        let boxes = (faces.results ?? []).map(\.boundingBox)
            + (people.results ?? []).map(\.boundingBox)
        guard !boxes.isEmpty else {
            return HeroImageAnalysis(suitable: false, placement: .center, cutout: nil)
        }

        let union = boxes.dropFirst().reduce(boxes[0]) { $0.union($1) }
        let center = union.midX
        let placement: HeroPlacement = center < 0.42 ? .leading : (center > 0.58 ? .trailing : .center)
        guard let mask = segmentation.results?.first?.pixelBuffer,
              let cutout = personCutout(image, mask: mask) else {
            return HeroImageAnalysis(suitable: false, placement: placement, cutout: nil)
        }
        return HeroImageAnalysis(suitable: true, placement: placement, cutout: cutout)
    }

    private static func personCutout(_ image: UIImage, mask: CVPixelBuffer) -> UIImage? {
        guard let input = CIImage(image: image, options: [.applyOrientationProperty: true]) else { return nil }
        let rawMask = CIImage(cvPixelBuffer: mask)
        let scale = CGAffineTransform(scaleX: input.extent.width / rawMask.extent.width,
                                      y: input.extent.height / rawMask.extent.height)
        let fittedMask = rawMask.transformed(by: scale)
            .clampedToExtent()
            .applyingFilter("CIGaussianBlur", parameters: [kCIInputRadiusKey: 1.5])
            .cropped(to: input.extent)
        let transparent = CIImage(color: CIColor(red: 0, green: 0, blue: 0, alpha: 0))
            .cropped(to: input.extent)
        let output = input.applyingFilter("CIBlendWithMask", parameters: [
            kCIInputBackgroundImageKey: transparent,
            kCIInputMaskImageKey: fittedMask,
        ])
        let context = CIContext(options: [.useSoftwareRenderer: false])
        guard let rendered = context.createCGImage(output, from: input.extent) else { return nil }
        return UIImage(cgImage: rendered)
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
