import SwiftUI
import UIKit

/// Player background in the colour of the current cover, like Yandex Music: the cover's
/// main hue, toned to a mid brightness so white text stays readable; grey for covers
/// with no real colour. Changes with a soft cross-fade when the track changes.
struct CoverBackdrop: View {
    let trackId: Int?
    @State private var color: UIColor?

    var body: some View {
        ZStack {
            Theme.backdrop
            if let color {
                LinearGradient(
                    colors: [Color(color), Color(color.withBrightness(factor: 0.55))],
                    startPoint: .top, endPoint: .bottom
                )
                .transition(.opacity)
            }
        }
        .ignoresSafeArea()
        .animation(.easeInOut(duration: 0.6), value: color)
        .task(id: trackId) {
            guard let trackId else { color = nil; return }
            let found = await CoverColor.color(for: trackId)
            withAnimation(.easeInOut(duration: 0.6)) { color = found }
        }
    }
}

enum CoverColor {
    private static let cache = NSCache<NSNumber, UIColor>()

    static func color(for trackId: Int) async -> UIColor? {
        if let hit = cache.object(forKey: trackId as NSNumber) { return hit }
        var image: UIImage?
        if await OfflineStore.shared.isSaved(trackId) {
            image = await OfflineStore.savedArtwork(trackId, size: 96)
        }
        if image == nil, let url = ImageLoader.shared.api?.artworkURL(trackId: trackId, width: 96) {
            image = await ImageLoader.shared.image(url)
        }
        guard let image else { return nil }
        let color = await Task.detached(priority: .utility) { dominant(in: image) }.value
        if let color { cache.setObject(color, forKey: trackId as NSNumber) }
        return color
    }

    /// The most common hue among colourful pixels (12 hue bins, weighted by saturation),
    /// averaged and toned; a near-colourless cover gives a grey of its own lightness.
    static func dominant(in image: UIImage) -> UIColor? {
        let side = 24
        guard let cg = image.cgImage else { return nil }
        var pixels = [UInt8](repeating: 0, count: side * side * 4)
        guard let ctx = CGContext(data: &pixels, width: side, height: side, bitsPerComponent: 8,
                                  bytesPerRow: side * 4, space: CGColorSpaceCreateDeviceRGB(),
                                  bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return nil }
        ctx.interpolationQuality = .medium
        ctx.draw(cg, in: CGRect(x: 0, y: 0, width: side, height: side))

        var bins = Array(repeating: (w: 0.0, r: 0.0, g: 0.0, b: 0.0), count: 12)
        var colourful = 0.0, lightSum = 0.0
        let count = Double(side * side)
        for i in stride(from: 0, to: pixels.count, by: 4) {
            let r = Double(pixels[i]) / 255, g = Double(pixels[i + 1]) / 255, b = Double(pixels[i + 2]) / 255
            let mx = max(r, g, b), mn = min(r, g, b)
            let light = (mx + mn) / 2
            lightSum += light
            let sat = mx == 0 ? 0 : (mx - mn) / mx
            guard sat > 0.2, mx > 0.15, mx < 0.98 || sat > 0.4 else { continue }
            var hue: Double
            let d = mx - mn
            if mx == r { hue = (g - b) / d } else if mx == g { hue = 2 + (b - r) / d } else { hue = 4 + (r - g) / d }
            hue = (hue / 6).truncatingRemainder(dividingBy: 1)
            if hue < 0 { hue += 1 }
            let bin = min(Int(hue * 12), 11)
            let w = sat * (0.5 + mx / 2)
            bins[bin].w += w; bins[bin].r += r * w; bins[bin].g += g * w; bins[bin].b += b * w
            colourful += 1
        }

        if colourful / count < 0.08 {
            // black-and-white or nearly: a grey, lighter for light covers
            let light = lightSum / count
            return UIColor(white: 0.30 + 0.25 * light, alpha: 1)
        }
        guard let top = bins.max(by: { $0.w < $1.w }), top.w > 0 else { return nil }
        let base = UIColor(red: top.r / top.w, green: top.g / top.w, blue: top.b / top.w, alpha: 1)
        var h: CGFloat = 0, s: CGFloat = 0, v: CGFloat = 0, a: CGFloat = 0
        base.getHue(&h, saturation: &s, brightness: &v, alpha: &a)
        // mid tones: bright enough to read as the cover's colour, dark enough for white text
        return UIColor(hue: h, saturation: min(max(s, 0.35), 0.7), brightness: min(max(v * 0.75, 0.38), 0.58), alpha: 1)
    }
}

extension UIColor {
    func withBrightness(factor: CGFloat) -> UIColor {
        var h: CGFloat = 0, s: CGFloat = 0, v: CGFloat = 0, a: CGFloat = 0
        guard getHue(&h, saturation: &s, brightness: &v, alpha: &a) else { return self }
        return UIColor(hue: h, saturation: s, brightness: v * factor, alpha: a)
    }
}
