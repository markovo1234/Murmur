import MurmurCore
import SwiftUI

struct PersonRow: Identifiable, Equatable {
    let id: String
    let name: String
    let emoji: String
    let colorIndex: Int
    let status: PeerStatus
    let hops: Int?
    let lastSeen: Int64
    let favorite: Bool
}

extension AppModel {
    /// Everyone heard from, live status first: in range, via the mesh, then met before.
    var people: [PersonRow] {
        var rows: [String: PersonRow] = [:]
        for p in peers.values where !p.blocked {
            rows[p.id] = PersonRow(id: p.id, name: p.name, emoji: p.emoji, colorIndex: p.colorIndex, status: status(of: p.id),
                                   hops: hops(to: p.id), lastSeen: p.lastSeen, favorite: p.favorite)
        }
        return rows.values.sorted { a, b in
            if a.status != b.status { return a.status.rawValue < b.status.rawValue }
            if a.status == .viaMesh, a.hops != b.hops { return (a.hops ?? 99) < (b.hops ?? 99) }
            if a.status == .offline { return a.lastSeen > b.lastSeen }
            return a.name.localizedCaseInsensitiveCompare(b.name) == .orderedAscending
        }
    }
}

struct PeopleView: View {
    @Environment(AppModel.self) private var model
    @Environment(Router.self) private var router
    @State private var selected: String?
    @State private var query = ""

    var body: some View {
        NavigationStack {
            List {
                let all = model.people.filter { query.isEmpty || $0.name.localizedCaseInsensitiveContains(query) || $0.id.hasSuffix(query.lowercased().replacingOccurrences(of: "#", with: "")) }
                if all.isEmpty {
                    ContentUnavailableView("Nobody yet", systemImage: "person.2.wave.2",
                                           description: Text("People appear here once their phones (iPhone or Android) have been in Bluetooth range."))
                        .listRowBackground(Color.clear)
                }
                section("Favorites", all.filter(\.favorite))
                section("In range", all.filter { !$0.favorite && $0.status == .nearby })
                section("Via the mesh", all.filter { !$0.favorite && $0.status == .viaMesh })
                section("Met before", all.filter { !$0.favorite && $0.status == .offline })
            }
            .scrollContentBackground(.hidden)
            .background(Palette.background)
            .navigationTitle("People")
            .searchable(text: $query, prompt: "Name or #tag")
            .sheet(item: Binding(get: { selected.map(IdBox.init) }, set: { selected = $0?.id })) { box in
                PeerSheet(peerHex: box.id) { cid in
                    selected = nil
                    router.open(cid)
                }
            }
        }
    }

    @ViewBuilder private func section(_ title: String, _ rows: [PersonRow]) -> some View {
        if !rows.isEmpty {
            Section("\(title) · \(rows.count)") {
                ForEach(rows) { row in
                    Button {
                        selected = row.id
                    } label: {
                        HStack(spacing: 12) {
                            AvatarView(emoji: row.emoji, colorIndex: row.colorIndex, online: row.status == .nearby)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(row.name).font(.headline).foregroundStyle(Palette.text)
                                Text(peerStatusText(row.status, hops: row.hops, lastSeen: row.lastSeen))
                                    .font(.subheadline)
                                    .foregroundStyle(Palette.muted)
                            }
                            Spacer()
                            if row.favorite { Image(systemName: "star.fill").foregroundStyle(Palette.amber).accessibilityLabel("Favorite") }
                        }
                    }
                    .listRowBackground(Palette.surface)
                }
            }
        }
    }
}

struct IdBox: Identifiable {
    let id: String
}

/// A person: status, actions and the safety number to compare in person.
struct PeerSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let peerHex: String
    let onMessage: (String) -> Void
    @State private var confirmBlock = false
    @State private var note: String?

    var body: some View {
        let p = model.peers[peerHex]
        let status = model.status(of: peerHex)
        NavigationStack {
            List {
                VStack(spacing: 10) {
                    AvatarView(emoji: p?.emoji ?? "🙂", colorIndex: p?.colorIndex ?? 0, size: 88, online: status == .nearby)
                    Text(model.displayName(peerHex)).font(.title2.bold())
                    Text("#\(String(peerHex.suffix(4)))").font(.subheadline.monospaced()).foregroundStyle(Palette.muted)
                    Text(peerStatusText(status, hops: model.hops(to: peerHex), lastSeen: p?.lastSeen ?? 0))
                        .font(.subheadline)
                        .foregroundStyle(status == .offline ? Palette.muted : Palette.accent)
                }
                .frame(maxWidth: .infinity)
                .listRowBackground(Color.clear)

                Section {
                    Button {
                        dismiss()
                        onMessage(model.dmConversation(with: peerHex))
                    } label: { Label("Message", systemImage: "bubble.left.fill") }
                    if let peer = PeerId.fromHex(peerHex) {
                        Button {
                            note = model.wave(peer) ? "You waved 👋" : "They aren't reachable right now"
                        } label: { Label("Wave", systemImage: "hand.wave.fill") }
                    }
                    Button {
                        model.toggleFavorite(peerHex)
                    } label: {
                        Label(p?.favorite == true ? "Remove from favorites" : "Add to favorites", systemImage: p?.favorite == true ? "star.slash" : "star")
                    }
                }
                if let note {
                    Text(note).font(.footnote).foregroundStyle(Palette.muted)
                }

                Section {
                    if let number = model.safetyNumber(peerHex) {
                        Text(number)
                            .font(.title3.monospaced())
                            .frame(maxWidth: .infinity)
                            .textSelection(.enabled)
                    } else {
                        Text("Available once you've heard from them directly.").foregroundStyle(Palette.muted)
                    }
                } header: {
                    Text("Safety number")
                } footer: {
                    Text("Compare this number with theirs in person. If it matches, nobody is impersonating them. It's the same on Android and iPhone.")
                }

                Section {
                    Button(role: .destructive) {
                        if p?.blocked == true { model.setBlocked(peerHex, false) } else { confirmBlock = true }
                    } label: {
                        Label(p?.blocked == true ? "Unblock" : "Block", systemImage: "hand.raised.fill")
                    }
                }
            }
            .navigationTitle("Profile")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
            .confirmationDialog("Block \(model.displayName(peerHex))?", isPresented: $confirmBlock, titleVisibility: .visible) {
                Button("Block", role: .destructive) { model.setBlocked(peerHex, true) }
            } message: {
                Text("You won't see their messages. Your phone still passes their packets along for others, so the mesh keeps working.")
            }
        }
        .presentationDetents([.medium, .large])
    }
}

/// Pick someone (for a new message or an invite).
struct PeerPicker: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let title: String
    let onPick: (String) -> Void

    var body: some View {
        NavigationStack {
            List {
                let rows = model.people
                if rows.isEmpty {
                    ContentUnavailableView("Nobody yet", systemImage: "person.2.wave.2", description: Text("Wait for someone to come into Bluetooth range."))
                }
                ForEach(rows) { row in
                    Button {
                        dismiss()
                        onPick(row.id)
                    } label: {
                        HStack(spacing: 12) {
                            AvatarView(emoji: row.emoji, colorIndex: row.colorIndex, size: 36, online: row.status == .nearby)
                            VStack(alignment: .leading) {
                                Text(row.name).foregroundStyle(Palette.text)
                                Text(peerStatusText(row.status, hops: row.hops, lastSeen: row.lastSeen)).font(.caption).foregroundStyle(Palette.muted)
                            }
                        }
                    }
                }
            }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
        }
        .presentationDetents([.medium, .large])
    }
}

/// Join or create a channel, optionally with a password; shows channels active nearby.
struct JoinChannelSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let onJoined: (String) -> Void
    @State private var name = ""
    @State private var password = ""
    @State private var usePassword = false
    @State private var working = false
    @State private var error: String?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Channel name, e.g. night-owls", text: $name)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    Toggle("Password", isOn: $usePassword.animation())
                    if usePassword {
                        SecureField("Password", text: $password)
                    }
                } footer: {
                    Text(usePassword
                         ? "Only people who know the same password can read it, on any phone. It travels encrypted; relays can't read it."
                         : "Anyone nearby who joins the same name sees the messages.")
                }
                if let normalized = Channels.normalize(name), normalized != name.trimmingCharacters(in: .whitespaces).lowercased().replacingOccurrences(of: "#", with: "") {
                    Text("Will be #\(normalized)").font(.footnote).foregroundStyle(Palette.muted)
                }
                if let error { Text(error).foregroundStyle(Palette.danger) }

                let active = model.nearbyChannels.values.sorted { $0.lastSeen > $1.lastSeen }
                if !active.isEmpty {
                    Section("Active nearby") {
                        ForEach(active) { ch in
                            Button {
                                name = ch.name
                                usePassword = ch.locked
                            } label: {
                                HStack {
                                    Image(systemName: ch.locked ? "lock.fill" : "number").foregroundStyle(Palette.violet)
                                    Text("#\(ch.name)").foregroundStyle(Palette.text)
                                    Spacer()
                                    if model.channels[ch.name] != nil {
                                        Text("Joined").font(.caption).foregroundStyle(Palette.muted)
                                    } else if ch.locked {
                                        Text("Needs password").font(.caption).foregroundStyle(Palette.muted)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            .navigationTitle("Join a channel")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    if working {
                        ProgressView()
                    } else {
                        Button("Join") { join() }.disabled(Channels.normalize(name) == nil || (usePassword && password.isEmpty))
                    }
                }
            }
        }
    }

    private func join() {
        working = true
        Task { @MainActor in
            let result = await model.joinChannel(name, password: usePassword ? password : nil)
            working = false
            switch result {
            case let .joined(channel):
                dismiss()
                onJoined(ConversationId.channel(channel))
            case .invalid:
                error = "Use letters, numbers, - and _ (up to 24)."
            }
        }
    }
}
