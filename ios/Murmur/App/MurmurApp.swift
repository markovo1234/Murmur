import MurmurCore
import SwiftUI

@main
struct MurmurApp: App {
    @State private var model: AppModel
    @State private var router = Router()
    @Environment(\.scenePhase) private var scenePhase
    private let notifier: LocalNotifier

    init() {
        let notifier = LocalNotifier()
        let clock = SystemClock()
        self.notifier = notifier
        _model = State(initialValue: AppModel(
            clock: clock,
            file: SnapshotFile.standard(),
            identityStore: KeychainIdentityStore(),
            notifier: notifier,
            makeTransport: { peerId in BleTransport(queue: .main, clock: clock, myPeerId: peerId, log: { print("BLE", $0) }) },
            log: { print($0) }
        ))
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(model)
                .environment(router)
                .onAppear {
                    notifier.onOpen = { [router] cid in router.open(cid) }
                    model.startMesh()
                }
                .onChange(of: scenePhase) { _, phase in
                    model.setActive(phase == .active)
                    if phase != .active { notifier.setBadge(model.totalUnread) }
                }
                .onChange(of: model.totalUnread) { _, count in notifier.setBadge(count) }
        }
    }
}
