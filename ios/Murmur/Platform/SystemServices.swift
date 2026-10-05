import Foundation
import Security
import UserNotifications

/// The identity keys in the Keychain, readable after the first unlock (so Bluetooth can keep working
/// while the phone is locked) and never synced or backed up to other devices.
final class KeychainIdentityStore: IdentityStore {
    private let service = "app.murmur.identity"
    private let account = "keys"

    private var baseQuery: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: account]
    }

    func load() -> [UInt8]? {
        var query = baseQuery
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &out) == errSecSuccess, let data = out as? Data else { return nil }
        return Array(data)
    }

    func save(_ keys: [UInt8]) -> Bool {
        delete()
        var query = baseQuery
        query[kSecValueData as String] = Data(keys)
        query[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        return SecItemAdd(query as CFDictionary, nil) == errSecSuccess
    }

    func delete() {
        SecItemDelete(baseQuery as CFDictionary)
    }
}

/// Local notifications, grouped per chat. Tapping one opens that chat.
final class LocalNotifier: NSObject, Notifier, UNUserNotificationCenterDelegate {
    /// Called on the main queue with the conversation id of a tapped notification.
    var onOpen: ((String) -> Void)?
    private let center = UNUserNotificationCenter.current()

    override init() {
        super.init()
        center.delegate = self
    }

    func requestPermission() {
        center.requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in }
    }

    func notify(id: String, title: String, body: String, conversationId: String?) {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default
        if let conversationId {
            content.threadIdentifier = conversationId
            content.userInfo = ["conversation": conversationId]
        }
        center.add(UNNotificationRequest(identifier: id, content: content, trigger: nil))
    }

    func cancel(conversationId: String) {
        center.getDeliveredNotifications { [center] delivered in
            let ids = delivered.filter { $0.request.content.threadIdentifier == conversationId }.map(\.request.identifier)
            if !ids.isEmpty { center.removeDeliveredNotifications(withIdentifiers: ids) }
        }
    }

    func setBadge(_ count: Int) {
        center.setBadgeCount(count) { _ in }
    }

    // The app only asks for a notification when that chat isn't on screen, so show it even in the foreground.
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .sound, .list])
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                withCompletionHandler completionHandler: @escaping () -> Void) {
        if let id = response.notification.request.content.userInfo["conversation"] as? String {
            DispatchQueue.main.async { [weak self] in self?.onOpen?(id) }
        }
        completionHandler()
    }
}
