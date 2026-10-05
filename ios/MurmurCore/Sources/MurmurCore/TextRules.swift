import Foundation

/// @mentions in rooms.
public enum Mentions {
    private static func isWordChar(_ c: Character) -> Bool {
        c == "_" || c.unicodeScalars.contains { $0.properties.isAlphabetic || $0.properties.numericType != nil }
    }

    /// True if `text` contains "@nickname" (case-insensitive, not inside a longer word).
    public static func mentions(_ text: String, nickname: String?) -> Bool {
        guard let nickname, !nickname.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return false }
        let needle = "@" + nickname
        var search = text.startIndex
        while let r = text.range(of: needle, options: [.caseInsensitive], range: search..<text.endIndex) {
            let beforeOk = r.lowerBound == text.startIndex || !isWordChar(text[text.index(before: r.lowerBound)])
            let afterOk = r.upperBound == text.endIndex || !isWordChar(text[r.upperBound])
            if beforeOk && afterOk { return true }
            search = text.index(after: r.lowerBound)
        }
        return false
    }

    /// The partial "@name" being typed at the end of `text`, without the '@', or nil.
    public static func partial(_ text: String) -> String? {
        guard let at = text.lastIndex(of: "@") else { return nil }
        if at > text.startIndex {
            let before = text[text.index(before: at)]
            if before.isLetter || before.isNumber || before == "_" { return nil }
        }
        let tail = String(text[text.index(after: at)...])
        guard tail.count <= Murmur.nicknameMaxChars, !tail.contains("\n"), !tail.contains("  ") else { return nil }
        return tail
    }

    /// Replaces the partial mention at the end of `text` with "@nickname ".
    public static func complete(_ text: String, nickname: String) -> String {
        guard let at = text.lastIndex(of: "@") else { return text + "@\(nickname) " }
        return String(text[..<at]) + "@\(nickname) "
    }
}

/// Replies are plain text so every version shows them sensibly: the first line is
/// "> Name: quoted snippet", the rest is the reply.
public enum Replies {
    public static let snippetChars = 80

    public struct Parsed: Equatable {
        public let quoteAuthor: String?
        public let quote: String?
        public let text: String
    }

    public static func compose(author: String, quoted: String, reply: String) -> String {
        let oneLine = quoted.replacingOccurrences(of: "\n", with: " ").trimmingCharacters(in: .whitespaces)
        let snippet = oneLine.count > snippetChars
            ? String(oneLine.prefix(snippetChars - 1)).trimmingTrailingWhitespace() + "…"
            : oneLine
        var body = "> \(author): \(snippet)\n\(reply)"
        var cut = snippet
        while Murmur.utf8Size(body) > Murmur.maxTextBytes && !cut.isEmpty {
            cut = String(cut.dropLast(8))
            body = "> \(author): \(cut.trimmingTrailingWhitespace())…\n\(reply)"
        }
        return Murmur.utf8Size(body) > Murmur.maxTextBytes ? reply : body
    }

    public static func parse(_ body: String) -> Parsed {
        guard body.hasPrefix("> "), let newline = body.firstIndex(of: "\n") else { return Parsed(quoteAuthor: nil, quote: nil, text: body) }
        let quoteLine = String(body[body.index(body.startIndex, offsetBy: 2)..<newline])
        let rest = String(body[body.index(after: newline)...])
        if let colon = quoteLine.range(of: ": ") {
            let distance = quoteLine.distance(from: quoteLine.startIndex, to: colon.lowerBound)
            if distance >= 1 && distance <= 40 {
                return Parsed(quoteAuthor: String(quoteLine[..<colon.lowerBound]), quote: String(quoteLine[colon.upperBound...]), text: rest)
            }
        }
        return Parsed(quoteAuthor: nil, quote: quoteLine, text: rest)
    }
}

/// Disappearing-message timer choices.
public enum Disappearing {
    public static let options: [Int64] = [0, 5 * 60, 60 * 60, 24 * 60 * 60, 7 * 24 * 60 * 60]
    public static let maxSeconds: Int64 = 28 * 24 * 60 * 60

    public static func label(_ seconds: Int64) -> String {
        switch seconds {
        case ...0: return "Off"
        case ..<3600: return "\(seconds / 60) minutes"
        case 3600: return "1 hour"
        case ..<86_400: return "\(seconds / 3600) hours"
        case 86_400: return "1 day"
        case 604_800: return "1 week"
        default: return "\(seconds / 86_400) days"
        }
    }
}

extension String {
    func trimmingTrailingWhitespace() -> String {
        var s = self
        while let last = s.last, last.isWhitespace { s.removeLast() }
        return s
    }
}
