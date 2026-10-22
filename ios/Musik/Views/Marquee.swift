import SwiftUI

/// One line of text that holds still when it fits and slowly scrolls to its end and back
/// when it doesn't (a running line). The right edge fades while it is too long.
///
/// The moving text lives in an overlay: however long it is, it never changes the size of
/// the view, so a long title can't stretch the screen it sits on.
struct MarqueeText: View {
    let text: String
    var font: Font = .subheadline

    @State private var textWidth: CGFloat = 0
    @State private var boxWidth: CGFloat = 0
    @State private var offset: CGFloat = 0

    private var overflow: CGFloat { max(0, textWidth - boxWidth) }

    var body: some View {
        // an invisible line gives the view its height and takes the width it is offered
        Text("Ag")
            .font(font)
            .lineLimit(1)
            .hidden()
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(GeometryReader { geo in
                Color.clear
                    .onAppear { boxWidth = geo.size.width }
                    .onChange(of: geo.size.width) { boxWidth = $0 }
            })
            .overlay(alignment: .leading) {
                Text(text)
                    .font(font)
                    .lineLimit(1)
                    .fixedSize()
                    .background(GeometryReader { geo in
                        Color.clear
                            .onAppear { textWidth = geo.size.width }
                            .onChange(of: geo.size.width) { textWidth = $0 }
                    })
                    .offset(x: offset)
            }
            .clipped()
            .mask(
                LinearGradient(stops: [
                    .init(color: .black, location: 0),
                    .init(color: .black, location: overflow > 1 ? 0.88 : 1),
                    .init(color: overflow > 1 ? .clear : .black, location: 1),
                ], startPoint: .leading, endPoint: .trailing)
            )
            .task(id: "\(text)|\(Int(textWidth))|\(Int(boxWidth))") { await run() }
    }

    private func run() async {
        offset = 0
        guard overflow > 1 else { return }
        let distance = overflow + 16
        let seconds = Double(distance) / 30
        while !Task.isCancelled {
            try? await Task.sleep(nanoseconds: 1_500_000_000)
            if Task.isCancelled { return }
            withAnimation(.linear(duration: seconds)) { offset = -distance }
            try? await Task.sleep(nanoseconds: UInt64((seconds + 1.2) * 1_000_000_000))
            if Task.isCancelled { return }
            withAnimation(.easeOut(duration: 0.4)) { offset = 0 }
            try? await Task.sleep(nanoseconds: 500_000_000)
        }
    }
}
