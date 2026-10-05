import Foundation
import MurmurCore

/// The radio (Bluetooth on a phone, an in-memory network in tests). Everything happens on the main
/// queue, where the mesh node also lives.
protocol LinkTransport: AnyObject {
    var onLinkUp: ((MeshLink) -> Void)? { get set }
    var onReceived: ((String, [UInt8]) -> Void)? { get set }
    var onLinkDown: ((String) -> Void)? { get set }
    var onStatus: ((BleStatus) -> Void)? { get set }
    func start()
    func stop()
}

/// Where the identity keys live (the Keychain on a phone).
protocol IdentityStore: AnyObject {
    /// 64 bytes: Ed25519 seed || X25519 private key.
    func load() -> [UInt8]?
    func save(_ keys: [UInt8]) -> Bool
    func delete()
}

/// Local notifications.
protocol Notifier: AnyObject {
    func requestPermission()
    func notify(id: String, title: String, body: String, conversationId: String?)
    func cancel(conversationId: String)
}

final class SilentNotifier: Notifier {
    func requestPermission() {}
    func notify(id: String, title: String, body: String, conversationId: String?) {}
    func cancel(conversationId: String) {}
}

final class MemoryIdentityStore: IdentityStore {
    private var keys: [UInt8]?
    func load() -> [UInt8]? { keys }
    func save(_ keys: [UInt8]) -> Bool {
        self.keys = keys
        return true
    }
    func delete() { keys = nil }
}
