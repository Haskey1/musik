import SwiftUI
import UIKit

extension Notification.Name {
    /// The tab you are already on was tapped (object: tab index).
    static let tabReselected = Notification.Name("musik.tabReselected")
    /// Already at the root of that tab: scroll its list back to the top (object: tab index).
    static let tabScrollToTop = Notification.Name("musik.tabScrollToTop")
}

/// SwiftUI's TabView doesn't say when the selected tab is tapped again, so a press
/// recogniser on the UITabBar watches for it: the tab under the finger was already selected
/// when the finger went down.
struct TabReselectObserver: UIViewRepresentable {
    func makeUIView(context: Context) -> UIView { Probe(frame: .zero) }
    func updateUIView(_ uiView: UIView, context: Context) {}

    final class Probe: UIView, UIGestureRecognizerDelegate {
        private weak var bar: UITabBar?
        private var selectedOnDown: Int?
        private var tappedOnDown: Int?

        override func didMoveToWindow() {
            super.didMoveToWindow()
            if window != nil { attach(attempt: 0) }
        }

        private func attach(attempt: Int) {
            guard bar == nil, let window else { return }
            if let found = Self.tabBar(in: window) {
                let press = UILongPressGestureRecognizer(target: self, action: #selector(pressed(_:)))
                press.minimumPressDuration = 0
                press.allowableMovement = 16
                press.cancelsTouchesInView = false
                press.delegate = self
                found.addGestureRecognizer(press)
                bar = found
            } else if attempt < 10 {
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { [weak self] in
                    self?.attach(attempt: attempt + 1)
                }
            }
        }

        private static func tabBar(in view: UIView) -> UITabBar? {
            if let bar = view as? UITabBar { return bar }
            for sub in view.subviews { if let bar = tabBar(in: sub) { return bar } }
            return nil
        }

        func gestureRecognizer(_ g: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool { true }

        private func index(at point: CGPoint, in bar: UITabBar) -> Int? {
            guard let n = bar.items?.count, n > 0, bar.bounds.width > 0 else { return nil }
            return min(n - 1, max(0, Int(point.x / (bar.bounds.width / CGFloat(n)))))
        }

        @objc private func pressed(_ g: UILongPressGestureRecognizer) {
            guard let bar = g.view as? UITabBar else { return }
            let here = index(at: g.location(in: bar), in: bar)
            switch g.state {
            case .began:
                tappedOnDown = here
                selectedOnDown = bar.selectedItem.flatMap { bar.items?.firstIndex(of: $0) }
            case .ended:
                if let down = selectedOnDown, down == tappedOnDown, down == here {
                    NotificationCenter.default.post(name: .tabReselected, object: down)
                }
                selectedOnDown = nil
                tappedOnDown = nil
            default:
                selectedOnDown = nil
                tappedOnDown = nil
            }
        }
    }
}

/// A list that jumps back to its top when its tab is tapped again at the root.
/// It rebuilds the list view only; the screen's own state (search text, segment) stays.
private struct ResetScrollOnReselect: ViewModifier {
    let tab: Int
    @State private var token = 0

    func body(content: Content) -> some View {
        content
            .id(token)
            .onReceive(NotificationCenter.default.publisher(for: .tabScrollToTop)) { note in
                if note.object as? Int == tab { token += 1 }
            }
    }
}

extension View {
    func resetScrollOnReselect(tab: Int) -> some View { modifier(ResetScrollOnReselect(tab: tab)) }
}
