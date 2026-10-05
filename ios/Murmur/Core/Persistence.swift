import Foundation

/// Everything the app remembers between launches (identity keys live in the Keychain instead).
struct Snapshot: Codable {
    var version = 1
    var settings = AppSettings()
    var peers: [String: KnownPeer] = [:]
    var channels: [String: JoinedChannel] = [:]
    var conversations: [String: Conversation] = [:]
    var messages: [String: [ChatMessage]] = [:]
}

/// A JSON file in Application Support, written atomically and at most twice a second.
final class SnapshotFile {
    private let url: URL?
    private var pending: DispatchWorkItem?
    private let io = DispatchQueue(label: "murmur.snapshot", qos: .utility)

    /// `url` nil = keep nothing (tests).
    init(url: URL?) { self.url = url }

    static func standard() -> SnapshotFile {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return SnapshotFile(url: dir.appendingPathComponent("murmur-state.json"))
    }

    func load() -> Snapshot {
        guard let url, let data = try? Data(contentsOf: url),
              let snapshot = try? JSONDecoder().decode(Snapshot.self, from: data) else { return Snapshot() }
        return snapshot
    }

    func scheduleSave(_ make: @escaping () -> Snapshot) {
        guard url != nil else { return }
        pending?.cancel()
        let work = DispatchWorkItem { [weak self] in self?.write(make()) }
        pending = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5, execute: work)
    }

    func saveNow(_ snapshot: Snapshot) {
        pending?.cancel()
        pending = nil
        write(snapshot)
    }

    private func write(_ snapshot: Snapshot) {
        guard let url, let data = try? JSONEncoder().encode(snapshot) else { return }
        io.async {
            #if os(iOS)
            try? data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            #else
            try? data.write(to: url, options: [.atomic])
            #endif
        }
    }

    func erase() {
        pending?.cancel()
        if let url { try? FileManager.default.removeItem(at: url) }
    }
}
