import MurmurCore
import SwiftUI

/// #nearby, channels and direct messages, most recent first.
struct ChatsView: View {
    @Environment(AppModel.self) private var model
    @Environment(Router.self) private var router
    @State private var showJoin = false
    @State private var showNewMessage = false

    var body: some View {
        @Bindable var router = router
        NavigationStack(path: $router.chatPath) {
            List {
                if model.ble.radio != .poweredOn && model.ble.radio != .unknown && model.ble.radio != .resetting {
                    BluetoothBanner(status: model.ble)
                        .listRowSeparator(.hidden)
                        .listRowBackground(Color.clear)
                }
                RangeSummary()
                    .listRowSeparator(.hidden)
                    .listRowBackground(Color.clear)
                ForEach(model.sortedConversations) { conv in
                    NavigationLink(value: conv.id) {
                        ConversationRow(conversation: conv)
                    }
                    .swipeActions(edge: .trailing) {
                        if !conv.isNearby {
                            Button(role: .destructive) {
                                model.deleteConversation(conv.id)
                            } label: {
                                Label(conv.channelName != nil ? "Leave" : "Delete", systemImage: conv.channelName != nil ? "rectangle.portrait.and.arrow.right" : "trash")
                            }
                        }
                        Button {
                            model.setMuted(conv.id, !conv.muted)
                        } label: {
                            Label(conv.muted ? "Unmute" : "Mute", systemImage: conv.muted ? "bell" : "bell.slash")
                        }
                        .tint(Palette.muted)
                    }
                }
                if !unjoinedNearbyChannels.isEmpty {
                    Section("Channels active nearby") {
                        ForEach(unjoinedNearbyChannels) { ch in
                            Button {
                                showJoin = true
                            } label: {
                                HStack {
                                    Image(systemName: ch.locked ? "lock.fill" : "number").foregroundStyle(Palette.violet)
                                    Text("#\(ch.name)").foregroundStyle(Palette.text)
                                    Spacer()
                                    Text("Join").font(.subheadline.bold()).foregroundStyle(Palette.accent)
                                }
                            }
                        }
                    }
                }
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
            .background(Palette.background)
            .navigationTitle("Murmur")
            .navigationDestination(for: String.self) { id in
                ChatView(conversationId: id)
            }
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Button {
                            showNewMessage = true
                        } label: {
                            Label("New message", systemImage: "square.and.pencil")
                        }
                        Button {
                            showJoin = true
                        } label: {
                            Label("Join or create a channel", systemImage: "number")
                        }
                    } label: {
                        Image(systemName: "plus.circle.fill").font(.title2)
                    }
                    .accessibilityLabel("New chat")
                }
            }
            .sheet(isPresented: $showJoin) {
                JoinChannelSheet { cid in router.open(cid) }
            }
            .sheet(isPresented: $showNewMessage) {
                PeerPicker(title: "New message") { peerHex in
                    router.open(model.dmConversation(with: peerHex))
                }
            }
        }
    }

    private var unjoinedNearbyChannels: [NearbyChannel] {
        model.nearbyChannels.values
            .filter { model.channels[$0.name] == nil }
            .sorted { $0.lastSeen > $1.lastSeen }
    }
}

/// "3 people in range · 5 via the mesh".
struct RangeSummary: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let nearby = model.meshPeers.values.filter { $0.status == .nearby }.count
        let mesh = model.meshPeers.values.filter { $0.status == .viaMesh }.count
        HStack(spacing: 8) {
            Image(systemName: nearby > 0 ? "dot.radiowaves.left.and.right" : "magnifyingglass")
                .foregroundStyle(Palette.accent)
                .symbolEffect(.pulse, options: .repeating, isActive: nearby == 0 && model.meshRunning)
            Text(summary(nearby, mesh))
                .font(.subheadline)
                .foregroundStyle(Palette.muted)
                .contentTransition(.numericText())
        }
        .animation(.default, value: nearby + mesh * 1000)
    }

    private func summary(_ nearby: Int, _ mesh: Int) -> String {
        if !model.meshRunning { return "Mesh is off" }
        if nearby == 0 && mesh == 0 { return "Looking for people nearby…" }
        var parts = ["\(nearby) in range"]
        if mesh > 0 { parts.append("\(mesh) via the mesh") }
        return parts.joined(separator: " · ")
    }
}

struct ConversationRow: View {
    @Environment(AppModel.self) private var model
    let conversation: Conversation

    var body: some View {
        HStack(spacing: 12) {
            icon
            VStack(alignment: .leading, spacing: 3) {
                HStack {
                    Text(title).font(.headline).foregroundStyle(Palette.text).lineLimit(1)
                    if conversation.muted { Image(systemName: "bell.slash.fill").font(.caption).foregroundStyle(Palette.muted) }
                    if conversation.disappearSeconds > 0 { Image(systemName: "timer").font(.caption).foregroundStyle(Palette.muted) }
                    Spacer()
                    Text(TimeText.short(conversation.lastActivity)).font(.caption).foregroundStyle(Palette.muted)
                }
                HStack {
                    Text(previewText)
                        .font(.subheadline)
                        .foregroundStyle(isTyping ? Palette.accent : Palette.muted)
                        .lineLimit(2)
                    Spacer()
                    if conversation.unread > 0 {
                        Text("\(conversation.unread)")
                            .font(.caption.bold())
                            .padding(.horizontal, 7)
                            .padding(.vertical, 2)
                            .background(conversation.muted ? Palette.muted : Palette.accent, in: Capsule())
                            .foregroundStyle(Palette.onAccent)
                    }
                }
            }
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .combine)
    }

    private var title: String {
        if let peer = conversation.peerId { return model.displayName(peer.toHex()) }
        return conversation.title
    }

    private var isTyping: Bool { conversation.peerId.map { model.isTyping($0.toHex()) } ?? false }

    private var previewText: String {
        if isTyping { return "typing…" }
        return conversation.preview.isEmpty ? (conversation.peerId != nil ? "Say hi 👋" : "No messages yet") : conversation.preview
    }

    @ViewBuilder private var icon: some View {
        if let peer = conversation.peerId {
            let p = model.peers[peer.toHex()]
            AvatarView(emoji: p?.emoji ?? "🙂", colorIndex: p?.colorIndex ?? 0, online: model.status(of: peer.toHex()) == .nearby)
        } else {
            RoomIcon(conversation: conversation, locked: conversation.channelName.flatMap { model.channels[$0]?.locked } ?? false)
        }
    }
}
