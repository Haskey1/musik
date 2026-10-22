import UIKit

/// Taps under the finger. Off with Profile → Тактильный отклик; iOS also mutes them when
/// Settings → Sounds & Haptics → System Haptics is off.
@MainActor
enum Haptics {
    private static let medium = UIImpactFeedbackGenerator(style: .medium)
    private static let light = UIImpactFeedbackGenerator(style: .light)

    private static var enabled: Bool { Settings.shared.haptics }

    /// Liking: two beats in time with the heart's pulse.
    static func heartbeat() {
        guard enabled else { return }
        medium.prepare()
        medium.impactOccurred(intensity: 1)
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.36) {
            medium.impactOccurred(intensity: 0.7)
        }
    }

    /// Play / pause, starting the radio: a firmer tap.
    static func press() {
        guard enabled else { return }
        medium.prepare()
        medium.impactOccurred(intensity: 0.8)
    }

    /// Any other toggle (unlike, dislike), skipping.
    static func tap() {
        guard enabled else { return }
        light.prepare()
        light.impactOccurred()
    }
}
