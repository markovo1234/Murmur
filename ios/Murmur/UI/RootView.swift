import MurmurCore
import SwiftUI

/// Which tab is showing and which chats are open (so a notification tap can jump to a chat).
@Observable
final class Router {
    enum Tab: Hashable { case chats, people, settings }

    var tab: Tab = .chats
    var chatPath: [String] = []

    func open(_ conversationId: String) {
        tab = .chats
        chatPath = [conversationId]
    }
}

struct RootView: View {
    @Environment(AppModel.self) private var model
    @Environment(Router.self) private var router

    var body: some View {
        Group {
            if model.settings.onboardingDone {
                MainTabs()
            } else {
                OnboardingView()
            }
        }
        .tint(Palette.accent)
        .animation(.easeInOut(duration: 0.3), value: model.settings.onboardingDone)
    }
}

struct MainTabs: View {
    @Environment(AppModel.self) private var model
    @Environment(Router.self) private var router

    var body: some View {
        @Bindable var router = router
        TabView(selection: $router.tab) {
            ChatsView()
                .tabItem { Label("Chats", systemImage: "bubble.left.and.bubble.right.fill") }
                .badge(model.totalUnread)
                .tag(Router.Tab.chats)
            PeopleView()
                .tabItem { Label("People", systemImage: "person.2.fill") }
                .tag(Router.Tab.people)
            SettingsView()
                .tabItem { Label("Settings", systemImage: "gearshape.fill") }
                .tag(Router.Tab.settings)
        }
    }
}

/// Shown when Bluetooth can't run, saying what to do about it.
struct BluetoothBanner: View {
    let status: BleStatus

    var body: some View {
        if let message {
            HStack(spacing: 10) {
                Image(systemName: message.icon).foregroundStyle(Palette.amber)
                Text(message.text).font(.subheadline).foregroundStyle(Palette.text)
                Spacer(minLength: 0)
                if status.radio == .unauthorized {
                    Button("Settings") {
                        if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                    }
                    .font(.subheadline.bold())
                }
            }
            .padding(12)
            .background(Palette.amber.opacity(0.15), in: RoundedRectangle(cornerRadius: 12))
        }
    }

    private var message: (icon: String, text: String)? {
        switch status.radio {
        case .poweredOff: return ("bolt.horizontal.circle", "Bluetooth is off. Turn it on to reach people nearby.")
        case .unauthorized: return ("hand.raised.fill", "Murmur needs Bluetooth permission to find people nearby.")
        case .unsupported: return ("exclamationmark.triangle.fill", "This device can't do Bluetooth LE.")
        default: return nil
        }
    }
}
