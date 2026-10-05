import MurmurCore
import SwiftUI
import UIKit

/// Murmur's night-sky palette (the Android app's colours), adapting to light and dark mode.
enum Palette {
    static let accent = dynamic(light: 0x0D9488, dark: 0x5EEAD4)
    static let onAccent = dynamic(light: 0xFFFFFF, dark: 0x0A0F1E)
    static let background = dynamic(light: 0xF6F7FB, dark: 0x0A0F1E)
    static let surface = dynamic(light: 0xFFFFFF, dark: 0x111831)
    static let container = dynamic(light: 0xEEF1F8, dark: 0x172042)
    static let outline = dynamic(light: 0xCBD2E6, dark: 0x2A3560)
    static let text = dynamic(light: 0x0B1226, dark: 0xE6EAF6)
    static let muted = dynamic(light: 0x4A5578, dark: 0x9AA4C7)
    static let violet = dynamic(light: 0x7C3AED, dark: 0xA78BFA)
    static let amber = dynamic(light: 0xB45309, dark: 0xFBBF24)
    static let danger = dynamic(light: 0xDC2626, dark: 0xFF6B6B)

    static func dynamic(light: UInt32, dark: UInt32) -> Color {
        Color(UIColor { traits in UIColor(rgb: traits.userInterfaceStyle == .dark ? dark : light) })
    }

    static func avatar(_ index: Int) -> Color {
        let colors = AvatarPalette.colors
        return Color(UIColor(rgb: colors[((index % colors.count) + colors.count) % colors.count]))
    }
}

extension UIColor {
    convenience init(rgb: UInt32, alpha: CGFloat = 1) {
        self.init(red: CGFloat((rgb >> 16) & 0xFF) / 255, green: CGFloat((rgb >> 8) & 0xFF) / 255,
                  blue: CGFloat(rgb & 0xFF) / 255, alpha: alpha)
    }
}

/// An emoji on a coloured disc, with an optional "in range" dot.
struct AvatarView: View {
    let emoji: String
    let colorIndex: Int
    var size: CGFloat = 44
    var online = false

    var body: some View {
        ZStack(alignment: .bottomTrailing) {
            Circle()
                .fill(Palette.avatar(colorIndex).opacity(0.9))
                .overlay(Text(emoji).font(.system(size: size * 0.5)))
                .frame(width: size, height: size)
            if online {
                Circle()
                    .fill(Palette.accent)
                    .frame(width: size * 0.28, height: size * 0.28)
                    .overlay(Circle().stroke(Palette.background, lineWidth: 2))
                    .accessibilityHidden(true)
            }
        }
        .accessibilityHidden(true)
    }
}

/// The round icon for #nearby and channels.
struct RoomIcon: View {
    let conversation: Conversation
    var locked = false
    var size: CGFloat = 44

    var body: some View {
        Circle()
            .fill(conversation.isNearby ? Palette.accent.opacity(0.2) : Palette.violet.opacity(0.2))
            .overlay(
                Image(systemName: conversation.isNearby ? "dot.radiowaves.left.and.right" : (locked ? "lock.fill" : "number"))
                    .font(.system(size: size * 0.4, weight: .semibold))
                    .foregroundStyle(conversation.isNearby ? Palette.accent : Palette.violet)
            )
            .frame(width: size, height: size)
            .accessibilityHidden(true)
    }
}

enum TimeText {
    /// "now", "5 min", "14:02", "Mon", "3 Oct".
    static func short(_ millis: Int64, now: Date = Date()) -> String {
        guard millis > 0 else { return "" }
        let date = Date(timeIntervalSince1970: Double(millis) / 1000)
        let seconds = now.timeIntervalSince(date)
        if seconds < 60 { return "now" }
        if seconds < 3600 { return "\(Int(seconds / 60)) min" }
        let cal = Calendar.current
        if cal.isDateInToday(date) { return date.formatted(date: .omitted, time: .shortened) }
        if seconds < 6 * 86_400 { return date.formatted(.dateTime.weekday(.abbreviated)) }
        return date.formatted(.dateTime.day().month(.abbreviated))
    }

    static func clock(_ millis: Int64) -> String {
        Date(timeIntervalSince1970: Double(millis) / 1000).formatted(date: .omitted, time: .shortened)
    }

    /// "last seen 5 min ago".
    static func lastSeen(_ millis: Int64) -> String {
        guard millis > 0 else { return "not seen yet" }
        let date = Date(timeIntervalSince1970: Double(millis) / 1000)
        let seconds = Date().timeIntervalSince(date)
        if seconds < 60 { return "seen just now" }
        return "last seen \(date.formatted(.relative(presentation: .named)))"
    }
}

/// How a peer is reachable, in words.
func peerStatusText(_ status: PeerStatus, hops: Int?, lastSeen: Int64) -> String {
    switch status {
    case .nearby: return "In Bluetooth range"
    case .viaMesh: return hops.map { "Via the mesh · \($0) hop\($0 == 1 ? "" : "s")" } ?? "Via the mesh"
    case .offline: return TimeText.lastSeen(lastSeen)
    }
}
