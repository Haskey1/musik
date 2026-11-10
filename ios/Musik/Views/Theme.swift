import SwiftUI

/// Warm editorial palette used across every screen. Black stays neutral while
/// terracotta and sage appear as light, never as a brown wash over the UI.
enum Theme {
    static let background = Color(hex: 0x191512)
    static let surface = Color(hex: 0x171310)
    static let surfaceHigh = Color(hex: 0x241C17)
    static let panel = Color(hex: 0x2B201A)
    static let accent = Color(hex: 0xF1772D)
    static let accentBright = Color(hex: 0xFF9A55)
    static let cream = Color(hex: 0xF6E9DC)
    static let teal = Color(hex: 0x678A70)
    static let muted = Color(hex: 0xB7A69A)
    /// Liked heart: a warm scarlet that sits next to the orange accent.
    static let love = Color(hex: 0xF0453A)

    static var backdrop: some View {
        ZStack {
            background
            HazeTexture().opacity(0.14)
            RadialGradient(colors: [accent.opacity(0.13), .clear], center: .topLeading,
                           startRadius: 8, endRadius: 440)
            RadialGradient(colors: [teal.opacity(0.09), .clear], center: .bottomTrailing,
                           startRadius: 8, endRadius: 520)
            LinearGradient(colors: [.clear, Color.black.opacity(0.32)], startPoint: .top, endPoint: .bottom)
        }
        .ignoresSafeArea()
    }

    static func placeholder(for seed: Int) -> [Color] {
        let palettes: [[Color]] = [
            [Color(hex: 0x6B3A1F), Color(hex: 0x2A1810)],
            [Color(hex: 0x2F5C50), Color(hex: 0x14221E)],
            [Color(hex: 0x7A4A2A), Color(hex: 0x3D2215)],
            [Color(hex: 0x3B3050), Color(hex: 0x1A1524)],
        ]
        return palettes[abs(seed) % palettes.count]
    }
}

/// Deterministic translucent wisps give the gradients a soft smoky texture.
struct HazeTexture: View {
    var body: some View {
        Canvas { context, size in
            context.addFilter(.blur(radius: 28))
            for i in 0..<18 {
                let x = CGFloat((i * 73) % 101) / 100 * size.width
                let y = CGFloat((i * 47) % 103) / 102 * size.height
                let rect = CGRect(x: x - size.width * 0.35, y: y,
                                  width: size.width * 0.8, height: size.height * 0.13)
                context.fill(Path(ellipseIn: rect), with: .color(.white.opacity(0.22)))
            }
        }
        .allowsHitTesting(false)
    }
}

struct WarmCardModifier: ViewModifier {
    var radius: CGFloat = 24
    var padding: CGFloat = 16

    func body(content: Content) -> some View {
        content
            .padding(padding)
            .background(Theme.surface.opacity(0.9), in: RoundedRectangle(cornerRadius: radius, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: radius, style: .continuous)
                .stroke(Color.white.opacity(0.075)))
    }
}

extension View {
    func warmCard(radius: CGFloat = 24, padding: CGFloat = 16) -> some View {
        modifier(WarmCardModifier(radius: radius, padding: padding))
    }
}

extension Color {
    init(hex: UInt32) {
        self.init(.sRGB,
                  red: Double((hex >> 16) & 0xFF) / 255,
                  green: Double((hex >> 8) & 0xFF) / 255,
                  blue: Double(hex & 0xFF) / 255,
                  opacity: 1)
    }
}

extension Font {
    static func display(_ size: CGFloat) -> Font { .system(size: size, weight: .heavy, design: .rounded) }
}

struct PrimaryButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.headline)
            .padding(.horizontal, 18)
            .padding(.vertical, 12)
            .background(
                LinearGradient(colors: [Theme.accentBright, Theme.accent], startPoint: .topLeading, endPoint: .bottomTrailing),
                in: Capsule()
            )
            .foregroundStyle(Color.black.opacity(0.88))
            .opacity(configuration.isPressed ? 0.8 : 1)
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
    }
}

struct ChipButtonStyle: ButtonStyle {
    var active = false
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.subheadline.weight(.semibold))
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .background(active ? Theme.accent.opacity(0.22) : Theme.surfaceHigh, in: Capsule())
            .overlay(Capsule().stroke(active ? Theme.accent : Color.white.opacity(0.08)))
            .foregroundStyle(active ? Theme.accentBright : .primary)
            .opacity(configuration.isPressed ? 0.75 : 1)
    }
}
