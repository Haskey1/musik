import SwiftUI

/// Equalizer screen: six draggable points on a curve, an on/off switch and presets.
struct EqualizerView: View {
    @ObservedObject private var eq = Equalizer.shared
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                HStack {
                    Text("Эквалайзер").font(.largeTitle.weight(.bold))
                    Spacer()
                    Button { dismiss() } label: {
                        Image(systemName: "xmark").font(.title3.weight(.semibold))
                    }
                    .tint(.primary)
                }
                .padding(.horizontal, 20)
                .padding(.top, 20)

                EQCurve(eq: eq)
                    .opacity(eq.enabled ? 1 : 0.45)

                VStack(spacing: 0) {
                    Toggle("Эквалайзер", isOn: $eq.enabled)
                        .tint(Theme.accent)
                        .padding(.vertical, 14)
                    Divider().opacity(0.3)
                    row("Своя настройка", selected: eq.presetId == Equalizer.custom) {}
                        .disabled(true)
                    ForEach(Equalizer.presets) { preset in
                        row(preset.name, selected: eq.presetId == preset.id) { eq.apply(preset) }
                    }
                }
                .padding(.horizontal, 20)
            }
            .padding(.bottom, 32)
        }
        .background(Theme.backdrop)
        .presentationDragIndicator(.visible)
    }

    private func row(_ title: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack {
                Text(title).foregroundStyle(.primary)
                Spacer()
                if selected {
                    Image(systemName: "checkmark").font(.body.weight(.semibold)).foregroundStyle(Theme.accent)
                }
            }
            .padding(.vertical, 14)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

private struct EQCurve: View {
    @ObservedObject var eq: Equalizer
    @State private var dragging: Int?

    private let height: CGFloat = 220

    var body: some View {
        VStack(spacing: 10) {
            HStack(spacing: 0) {
                ForEach(Equalizer.bands.indices, id: \.self) { i in
                    Text(dbLabel(eq.gains[i]))
                        .font(.caption.monospacedDigit().weight(.semibold))
                        .foregroundStyle(eq.gains[i] == 0 ? Theme.accentBright : Theme.accent)
                        .frame(maxWidth: .infinity)
                }
            }
            .padding(.horizontal, 12)

            GeometryReader { geo in
                let pts = points(in: geo.size)
                ZStack {
                    Path { p in
                        p.move(to: CGPoint(x: 0, y: geo.size.height / 2))
                        p.addLine(to: CGPoint(x: geo.size.width, y: geo.size.height / 2))
                    }
                    .stroke(Color.white.opacity(0.12), lineWidth: 1)

                    smooth(through: [CGPoint(x: 0, y: pts[0].y)] + pts + [CGPoint(x: geo.size.width, y: pts[pts.count - 1].y)])
                        .stroke(Theme.accent, style: StrokeStyle(lineWidth: 2.5, lineCap: .round, lineJoin: .round))

                    ForEach(pts.indices, id: \.self) { i in
                        Circle()
                            .fill(Theme.background)
                            .overlay(Circle().stroke(Theme.accent, lineWidth: 2.5))
                            .frame(width: dragging == i ? 30 : 24, height: dragging == i ? 30 : 24)
                            .position(pts[i])
                    }
                }
                .contentShape(Rectangle())
                .gesture(
                    DragGesture(minimumDistance: 0)
                        .onChanged { g in
                            let band = dragging ?? nearestBand(to: g.startLocation.x, width: geo.size.width)
                            dragging = band
                            let half = geo.size.height / 2
                            let value = Double((half - g.location.y) / half) * Equalizer.range.upperBound
                            eq.setGain(value, band: band)
                        }
                        .onEnded { _ in dragging = nil }
                )
            }
            .frame(height: height)

            HStack(spacing: 0) {
                ForEach(Equalizer.bands, id: \.self) { f in
                    Text(hzLabel(f))
                        .font(.caption)
                        .foregroundStyle(Theme.muted)
                        .frame(maxWidth: .infinity)
                }
            }
            .padding(.horizontal, 12)
        }
    }

    private func x(for band: Int, width: CGFloat) -> CGFloat {
        let inset: CGFloat = 12
        let cell = (width - 2 * inset) / CGFloat(Equalizer.bands.count)
        return inset + cell * (CGFloat(band) + 0.5)
    }

    private func points(in size: CGSize) -> [CGPoint] {
        let half = size.height / 2
        return Equalizer.bands.indices.map { i in
            let y = half - CGFloat(eq.gains[i] / Equalizer.range.upperBound) * (half - 16)
            return CGPoint(x: x(for: i, width: size.width), y: y)
        }
    }

    private func nearestBand(to px: CGFloat, width: CGFloat) -> Int {
        Equalizer.bands.indices.min { abs(x(for: $0, width: width) - px) < abs(x(for: $1, width: width) - px) } ?? 0
    }

    /// Catmull-Rom spline through the points, as smooth as the one in the example.
    private func smooth(through p: [CGPoint]) -> Path {
        Path { path in
            guard p.count > 1 else { return }
            path.move(to: p[0])
            for i in 0..<(p.count - 1) {
                let p0 = i > 0 ? p[i - 1] : p[i]
                let p1 = p[i], p2 = p[i + 1]
                let p3 = i + 2 < p.count ? p[i + 2] : p2
                let c1 = CGPoint(x: p1.x + (p2.x - p0.x) / 6, y: p1.y + (p2.y - p0.y) / 6)
                let c2 = CGPoint(x: p2.x - (p3.x - p1.x) / 6, y: p2.y - (p3.y - p1.y) / 6)
                path.addCurve(to: p2, control1: c1, control2: c2)
            }
        }
    }

    private func dbLabel(_ v: Double) -> String {
        let r = (v * 2).rounded() / 2
        let s = r == r.rounded() ? String(Int(r)) : String(format: "%.1f", r)
        return "\(r > 0 ? "+" : "")\(s) dB"
    }

    private func hzLabel(_ f: Double) -> String {
        f >= 1000 ? (f.truncatingRemainder(dividingBy: 1000) == 0 ? "\(Int(f / 1000)) kHz" : String(format: "%.1f kHz", f / 1000))
                  : "\(Int(f)) Hz"
    }
}
