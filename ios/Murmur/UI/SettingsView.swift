import MurmurCore
import SwiftUI

struct SettingsView: View {
    @Environment(AppModel.self) private var model
    @State private var editingProfile = false
    @State private var confirmErase = false

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Button {
                        editingProfile = true
                    } label: {
                        HStack(spacing: 14) {
                            AvatarView(emoji: model.settings.profile?.emoji ?? "🙂", colorIndex: model.settings.profile?.colorIndex ?? 0, size: 56)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(model.myName).font(.title3.bold()).foregroundStyle(Palette.text)
                                Text("#\(model.myId.shortTag) · tap to edit").font(.subheadline).foregroundStyle(Palette.muted)
                            }
                        }
                    }
                }

                Section {
                    Toggle("Read receipts", isOn: binding(\.readReceipts, model.setReadReceipts))
                    Toggle("Hide message text in notifications", isOn: binding(\.hideNotificationContent, model.setHideNotificationContent))
                } header: {
                    Text("Privacy")
                } footer: {
                    Text("Direct messages are end-to-end encrypted. Phones that pass them along can't read them.")
                }

                Section {
                    Toggle("Every #nearby message", isOn: binding(\.nearbyNotifications, model.setNearbyNotifications))
                    Toggle("Favorites nearby", isOn: binding(\.favoriteAlerts, model.setFavoriteAlerts))
                } header: {
                    Text("Notifications")
                } footer: {
                    Text("Direct messages, channels you joined and @mentions always notify.")
                }

                Section {
                    Toggle("Relay for others", isOn: binding(\.relay, model.setRelay))
                    Toggle("Short reach (3 hops)", isOn: Binding(get: { model.settings.publicReach < Murmur.initialTtl }, set: model.setShortReach))
                } header: {
                    Text("Mesh")
                } footer: {
                    Text("Relaying passes other people's messages along, so everyone reaches further. Short reach keeps your #nearby and channel messages closer to you.")
                }

                Section("Status") {
                    LabeledContent("Bluetooth", value: radioText)
                    LabeledContent("Direct links", value: "\(model.ble.links)")
                    LabeledContent("People reachable", value: "\(model.meshPeers.values.filter { $0.status != .offline }.count)")
                    LabeledContent("Sent · received", value: "\(model.stats.sent) · \(model.stats.received)")
                    LabeledContent("Relayed for others", value: "\(model.stats.relayed)")
                    if let error = model.ble.lastError {
                        Text(error).font(.footnote).foregroundStyle(Palette.danger)
                    }
                }

                Section {
                    LabeledContent("Version", value: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "?")
                    LabeledContent("Protocol", value: "v\(Murmur.protocolVersion), same as Android")
                    Label("Voice calls aren't on iPhone yet. If someone calls you from Android, they're told right away.", systemImage: "phone.down")
                        .font(.footnote)
                        .foregroundStyle(Palette.muted)
                    Link(destination: URL(string: "https://github.com/markovo1234/Murmur")!) {
                        Label("Open source on GitHub", systemImage: "chevron.left.forwardslash.chevron.right")
                    }
                } header: {
                    Text("About")
                } footer: {
                    Text("Murmur has no servers and no accounts. Nothing leaves your phone except over Bluetooth.")
                }

                Section {
                    Button("Erase everything", role: .destructive) { confirmErase = true }
                } footer: {
                    Text("Deletes your messages, people, channels and keys from this phone and starts over with a new identity.")
                }
            }
            .navigationTitle("Settings")
            .sheet(isPresented: $editingProfile) { EditProfileSheet() }
            .confirmationDialog("Erase everything?", isPresented: $confirmErase, titleVisibility: .visible) {
                Button("Erase", role: .destructive) { model.eraseEverything() }
            } message: {
                Text("This can't be undone. People will see you as someone new.")
            }
        }
    }

    private var radioText: String {
        switch model.ble.radio {
        case .poweredOn: return model.ble.advertising ? "On · visible" : "On"
        case .poweredOff: return "Off"
        case .unauthorized: return "Not allowed"
        case .unsupported: return "Not supported"
        case .resetting: return "Restarting"
        case .unknown: return model.meshRunning ? "Starting" : "Off"
        }
    }

    private func binding(_ key: KeyPath<AppSettings, Bool>, _ set: @escaping (Bool) -> Void) -> Binding<Bool> {
        Binding(get: { model.settings[keyPath: key] }, set: set)
    }
}

struct EditProfileSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var draft = ProfileDraft()

    var body: some View {
        NavigationStack {
            ScrollView {
                ProfileEditor(draft: $draft).padding(20)
            }
            .background(Palette.background)
            .navigationTitle("Your profile")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        model.updateProfile(draft.profile)
                        dismiss()
                    }
                    .disabled(!draft.isValid)
                }
            }
            .onAppear {
                if let p = model.settings.profile { draft = ProfileDraft(p) }
            }
        }
    }
}
