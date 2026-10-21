import SwiftUI
import UIKit

/// Artist page header in the Yandex Music manner: the artist's photo across the whole
/// width, under the status bar, melting into the page colour taken from the photo.
/// Pulling the page down stretches the photo; scrolling up slides it away a little
/// slower than the page, and the name fades out as it reaches the navigation bar.
struct ArtistHero: View {
    let artist: String
    let photo: URL
    let subtitle: String
    let isFavorite: Bool
    let onPlay: () -> Void
    let onRadio: (() -> Void)?
    let onFavorite: () -> Void
    /// Page colour picked from the photo; the page under the header is painted with it.
    @Binding var tint: Color
    /// True once the name has scrolled under the navigation bar.
    @Binding var collapsed: Bool

    static let height: CGFloat = 470
    static let coordinateSpace = "artistScroll"

    @State private var image: UIImage?
    @State private var restY: CGFloat?

    var body: some View {
        VStack(spacing: 18) {
            GeometryReader { geo in
                let y = geo.frame(in: .named(Self.coordinateSpace)).minY
                let rest = restY ?? y
                let shift = y - rest              // > 0 pulled down, < 0 scrolled up
                let pull = max(0, shift)
                // the photo also covers what sits above the content (status / nav bar)
                let top = max(0, rest)
                ZStack(alignment: .bottom) {
                    picture
                        .frame(width: geo.size.width, height: Self.height + top + pull)
                        .clipped()
                        .overlay(fade)
                        // stick to the top while pulled; drift at half speed when scrolled
                        .offset(y: -top - pull + (shift < 0 ? -shift * 0.45 : 0))

                    VStack(spacing: 6) {
                        Text(artist)
                            .font(.system(size: 40, weight: .heavy, design: .rounded))
                            .multilineTextAlignment(.center)
                            .lineLimit(2)
                            .minimumScaleFactor(0.5)
                            .shadow(color: .black.opacity(0.35), radius: 8, y: 2)
                        Text(subtitle)
                            .font(.subheadline.weight(.medium))
                            .foregroundStyle(.white.opacity(0.8))
                    }
                    .foregroundStyle(.white)
                    .padding(.horizontal, 20)
                    .padding(.bottom, 4)
                    .opacity(nameOpacity(shift))
                }
                .onAppear { if restY == nil { restY = y } }
                .onChange(of: y) { value in
                    if restY == nil { restY = value }
                    let isCollapsed = value - (restY ?? value) < -(Self.height - 140)
                    if isCollapsed != collapsed { collapsed = isCollapsed }
                }
            }
            .frame(height: Self.height)

            HStack(spacing: 10) {
                Button(action: onPlay) { Label("Играть", systemImage: "play.fill") }
                    .buttonStyle(PrimaryButtonStyle())
                if let onRadio {
                    Button(action: onRadio) { Label("Радио", systemImage: "dot.radiowaves.left.and.right") }
                        .buttonStyle(GlassChipStyle())
                }
                Button(action: onFavorite) {
                    Image(systemName: isFavorite ? "heart.fill" : "heart")
                }
                .buttonStyle(GlassChipStyle(active: isFavorite))
            }
            .padding(.horizontal, 16)
        }
        .task(id: photo) { await load() }
    }

    @ViewBuilder
    private var picture: some View {
        ZStack {
            tint
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                    .transition(.opacity)
            }
        }
    }

    /// Dark at the very top for the status bar and the back button, clear over the
    /// face, then into the page colour.
    private var fade: some View {
        LinearGradient(stops: [
            .init(color: .black.opacity(0.45), location: 0),
            .init(color: .clear, location: 0.2),
            .init(color: .clear, location: 0.45),
            .init(color: tint.opacity(0.55), location: 0.72),
            .init(color: tint, location: 1),
        ], startPoint: .top, endPoint: .bottom)
    }

    private func nameOpacity(_ shift: CGFloat) -> Double {
        guard shift < 0 else { return 1 }
        return Double(max(0, 1 + shift / (Self.height * 0.55)))
    }

    private func load() async {
        if let hit = ImageLoader.shared.cached(photo) {
            image = hit
        } else if let loaded = await ImageLoader.shared.image(photo) {
            withAnimation(.easeOut(duration: 0.35)) { image = loaded }
        }
        guard let image else { return }
        let picked = await Task.detached(priority: .utility) { CoverColor.dominant(in: image) }.value
        if let picked {
            withAnimation(.easeInOut(duration: 0.5)) { tint = Color(picked.withBrightness(factor: 0.72)) }
        }
    }
}
