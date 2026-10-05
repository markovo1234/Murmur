import MurmurCore
import SwiftUI
import UIKit

/// One conversation: #nearby, a channel or a DM.
struct ChatView: View {
    @Environment(AppModel.self) private var model
    @Environment(Router.self) private var router
    let conversationId: String

    @State private var draft = ""
    @State private var showPeer = false
    @State private var showInvite = false
    @State private var notice: String?
    @FocusState private var composerFocused: Bool

    private var messages: [ChatMessage] { model.messages[conversationId] ?? [] }
    private var conversation: Conversation? { model.conversations[conversationId] }
    private var peerHex: String? { ConversationId.peer(conversationId)?.toHex() }
    private var channelName: String? { ConversationId.channelName(conversationId) }

    var body: some View {
        VStack(spacing: 0) {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 6) {
                        ForEach(Array(messages.enumerated()), id: \.element.id) { index, m in
                            MessageRow(message: m, isRoom: peerHex == nil,
                                       showName: index == 0 || messages[index - 1].senderId != m.senderId || messages[index - 1].kind == .system,
                                       conversationId: conversationId)
                                .id(m.id)
                                .transition(.asymmetric(insertion: .move(edge: .bottom).combined(with: .opacity), removal: .opacity))
                        }
                        if let peerHex, model.isTyping(peerHex) {
                            TypingBubble().id("typing")
                        }
                    }
                    .padding(.horizontal, 12)
                    .padding(.vertical, 10)
                    .animation(.spring(duration: 0.3), value: messages.count)
                }
                .defaultScrollAnchor(.bottom)
                .scrollDismissesKeyboard(.interactively)
                .onChange(of: messages.last?.id) { _, last in
                    guard let last else { return }
                    withAnimation(.easeOut(duration: 0.25)) { proxy.scrollTo(last, anchor: .bottom) }
                }
            }
            if let notice {
                Text(notice)
                    .font(.footnote)
                    .foregroundStyle(Palette.danger)
                    .padding(.horizontal)
                    .padding(.top, 6)
                    .transition(.opacity)
            }
            Composer(text: $draft, focused: $composerFocused, placeholder: placeholder, onSend: send, onTyping: {
                model.userTyping(in: conversationId)
            })
        }
        .background(Palette.background)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) { header }
            ToolbarItem(placement: .topBarTrailing) { menu }
        }
        .onAppear { model.setOpenConversation(conversationId) }
        .onDisappear { if model.openConversationId == conversationId { model.setOpenConversation(nil) } }
        .sheet(isPresented: $showPeer) {
            if let peerHex { PeerSheet(peerHex: peerHex) { _ in } }
        }
        .sheet(isPresented: $showInvite) {
            if let channelName {
                PeerPicker(title: "Invite to #\(channelName)") { peerHex in
                    guard let peer = PeerId.fromHex(peerHex) else { return }
                    switch model.sendInvite(peer, to: channelName) {
                    case .sent: flash("Invite sent to \(model.displayName(peerHex))")
                    case .unreachable: flash("\(model.displayName(peerHex)) isn't reachable right now")
                    case .notAMember: break
                    }
                }
            }
        }
    }

    private var placeholder: String {
        if peerHex != nil { return "Message" }
        if conversationId == ConversationId.nearby { return "Message everyone nearby" }
        return "Message #\(channelName ?? "")"
    }

    private func send() {
        let text = draft
        switch model.send(text, in: conversationId) {
        case .sent:
            draft = ""
            notice = nil
        case .meshOff: flash("Bluetooth mesh isn't running")
        case .tooLong: flash("That's too long (1,000 bytes max)")
        case .notMember: flash("Join the channel first")
        case .empty: break
        }
    }

    private func flash(_ text: String) {
        withAnimation { notice = text }
        DispatchQueue.main.asyncAfter(deadline: .now() + 3) { withAnimation { if notice == text { notice = nil } } }
    }

    @ViewBuilder private var header: some View {
        Button {
            if peerHex != nil { showPeer = true }
        } label: {
            HStack(spacing: 8) {
                if let peerHex {
                    let p = model.peers[peerHex]
                    AvatarView(emoji: p?.emoji ?? "🙂", colorIndex: p?.colorIndex ?? 0, size: 30, online: model.status(of: peerHex) == .nearby)
                } else if let conversation {
                    RoomIcon(conversation: conversation, locked: channelName.flatMap { model.channels[$0]?.locked } ?? false, size: 30)
                }
                VStack(alignment: .leading, spacing: 0) {
                    Text(model.conversationTitle(conversationId)).font(.headline).foregroundStyle(Palette.text).lineLimit(1)
                    Text(subtitle).font(.caption).foregroundStyle(Palette.muted).lineLimit(1)
                }
            }
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(.isHeader)
    }

    private var subtitle: String {
        if let peerHex {
            if model.isTyping(peerHex) { return "typing…" }
            return peerStatusText(model.status(of: peerHex), hops: model.hops(to: peerHex), lastSeen: model.peers[peerHex]?.lastSeen ?? 0)
        }
        let inRange = model.meshPeers.values.filter { $0.status != .offline }.count
        if let channelName, model.channels[channelName]?.locked == true { return "🔒 Password channel · \(inRange) reachable" }
        return "\(inRange) \(inRange == 1 ? "person" : "people") reachable"
    }

    @ViewBuilder private var menu: some View {
        Menu {
            if let peerHex, let peer = PeerId.fromHex(peerHex) {
                Button {
                    if !model.wave(peer) { flash("\(model.displayName(peerHex)) isn't reachable right now") }
                } label: { Label("Wave 👋", systemImage: "hand.wave") }
                Menu {
                    ForEach(Disappearing.options, id: \.self) { s in
                        Button {
                            model.setDisappearing(s, for: peer)
                        } label: {
                            if conversation?.disappearSeconds == s { Label(Disappearing.label(s), systemImage: "checkmark") } else { Text(Disappearing.label(s)) }
                        }
                    }
                } label: { Label("Disappearing messages", systemImage: "timer") }
                Button { showPeer = true } label: { Label("Profile & safety number", systemImage: "person.crop.circle") }
            }
            if channelName != nil {
                Button { showInvite = true } label: { Label("Invite someone", systemImage: "person.badge.plus") }
            }
            if let conversation {
                Button {
                    model.setMuted(conversationId, !conversation.muted)
                } label: {
                    Label(conversation.muted ? "Unmute" : "Mute", systemImage: conversation.muted ? "bell" : "bell.slash")
                }
            }
            if let channelName {
                Button(role: .destructive) {
                    model.leaveChannel(channelName)
                    router.chatPath.removeAll()
                } label: { Label("Leave #\(channelName)", systemImage: "rectangle.portrait.and.arrow.right") }
            }
        } label: {
            Image(systemName: "ellipsis.circle")
        }
        .accessibilityLabel("Chat options")
    }
}

/// One message: a bubble, a system line, an invite card or an SOS alert.
struct MessageRow: View {
    @Environment(AppModel.self) private var model
    @Environment(Router.self) private var router
    let message: ChatMessage
    let isRoom: Bool
    let showName: Bool
    let conversationId: String

    static let quickReactions = ["👍", "❤️", "😂", "😮", "😢", "🙏"]

    var body: some View {
        switch message.kind {
        case .system:
            Text(message.body)
                .font(.footnote)
                .foregroundStyle(Palette.muted)
                .multilineTextAlignment(.center)
                .padding(.vertical, 6)
                .frame(maxWidth: .infinity)
        case .invite:
            inviteCard
        case .sos:
            sosCard
        case .text:
            bubble
        }
    }

    private var bubble: some View {
        let parsed = Replies.parse(message.body)
        return HStack(alignment: .bottom) {
            if message.outgoing { Spacer(minLength: 48) }
            VStack(alignment: message.outgoing ? .trailing : .leading, spacing: 3) {
                if isRoom && !message.outgoing && showName {
                    Text(message.senderName)
                        .font(.caption.bold())
                        .foregroundStyle(Palette.avatar(model.peers[message.senderId]?.colorIndex ?? 0))
                        .padding(.horizontal, 6)
                }
                VStack(alignment: .leading, spacing: 4) {
                    if message.retracted {
                        Label("This message was deleted", systemImage: "nosign").font(.callout.italic()).foregroundStyle(Palette.muted)
                    } else {
                        if let quote = parsed.quote {
                            VStack(alignment: .leading, spacing: 1) {
                                if let author = parsed.quoteAuthor { Text(author).font(.caption.bold()) }
                                Text(quote).font(.caption).lineLimit(2)
                            }
                            .padding(6)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(Color.primary.opacity(0.08), in: RoundedRectangle(cornerRadius: 8))
                        }
                        Text(parsed.text)
                            .font(.body)
                            .textSelection(.enabled)
                    }
                    HStack(spacing: 4) {
                        Spacer(minLength: 0)
                        if message.hops > 1 { Text("\(message.hops) hops").font(.caption2) }
                        Text(TimeText.clock(message.sentAt)).font(.caption2)
                        if message.outgoing, let status = message.delivery { DeliveryMark(status: status) }
                    }
                    .opacity(0.7)
                }
                .foregroundStyle(message.outgoing ? Palette.onAccent : Palette.text)
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(message.outgoing ? Palette.accent : Palette.surface, in: RoundedRectangle(cornerRadius: 18))
                .overlay {
                    if message.mentionsMe { RoundedRectangle(cornerRadius: 18).stroke(Palette.amber, lineWidth: 2) }
                }
                .contextMenu { menu }
                .onTapGesture {
                    if message.delivery == .failed { model.retry(messageId: message.id, in: conversationId) }
                }
                if !message.reactions.isEmpty {
                    ReactionChips(reactions: message.reactions, mine: message.reactions[model.myHex]) { emoji in
                        model.react(emoji, to: message.id, in: conversationId)
                    }
                }
            }
            if !message.outgoing { Spacer(minLength: 48) }
        }
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder private var menu: some View {
        if !message.retracted {
            ControlGroup {
                ForEach(Self.quickReactions, id: \.self) { e in
                    Button(e) { model.react(e, to: message.id, in: conversationId) }
                }
            }
            Button {
                UIPasteboard.general.string = Replies.parse(message.body).text
            } label: { Label("Copy", systemImage: "doc.on.doc") }
            if message.delivery == .failed {
                Button { model.retry(messageId: message.id, in: conversationId) } label: { Label("Send again", systemImage: "arrow.clockwise") }
            }
            if message.outgoing {
                Button(role: .destructive) {
                    model.retract(message.id, in: conversationId)
                } label: { Label("Delete for everyone", systemImage: "trash") }
            }
        }
    }

    private var inviteCard: some View {
        let invite = ChannelInvites.parse(message.body)
        let joined = invite.map { model.channels[$0.channel]?.keyHex == $0.key.map(Hex.encode) && model.channels[$0.channel] != nil } ?? false
        return HStack {
            if message.outgoing { Spacer(minLength: 48) }
            VStack(alignment: .leading, spacing: 8) {
                Label(message.outgoing ? "You sent an invite" : "\(message.senderName) invited you", systemImage: "envelope.open.fill")
                    .font(.subheadline.bold())
                HStack {
                    Image(systemName: invite?.locked == true ? "lock.fill" : "number")
                    Text("#\(invite?.channel ?? "?")").font(.title3.bold())
                }
                if invite?.locked == true {
                    Text("Password channel. The invite includes the key, so you can read it right away.").font(.caption).foregroundStyle(Palette.muted)
                }
                if !message.outgoing {
                    Button(joined ? "Open" : "Join #\(invite?.channel ?? "")") {
                        if let cid = joined ? invite.map({ ConversationId.channel($0.channel) }) : model.acceptInvite(message.id, in: conversationId) {
                            router.open(cid)
                        }
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(Palette.violet)
                }
            }
            .padding(12)
            .background(Palette.violet.opacity(0.12), in: RoundedRectangle(cornerRadius: 16))
            .overlay(RoundedRectangle(cornerRadius: 16).stroke(Palette.violet.opacity(0.4)))
            if !message.outgoing { Spacer(minLength: 48) }
        }
    }

    private var sosCard: some View {
        VStack(alignment: .leading, spacing: 6) {
            Label("SOS from \(message.senderName)", systemImage: "sos.circle.fill").font(.headline)
            if !message.body.isEmpty { Text(message.body) }
            Text("\(message.hops) hop\(message.hops == 1 ? "" : "s") away · \(TimeText.clock(message.sentAt))").font(.caption)
        }
        .foregroundStyle(.white)
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Palette.danger, in: RoundedRectangle(cornerRadius: 16))
    }
}

struct DeliveryMark: View {
    let status: DeliveryStatus

    var body: some View {
        switch status {
        case .pending: Image(systemName: "clock").font(.caption2).accessibilityLabel("Waiting for them to come in range")
        case .sending: Image(systemName: "arrow.up.circle").font(.caption2).accessibilityLabel("Sending")
        case .sent: Image(systemName: "checkmark").font(.caption2).accessibilityLabel("Sent")
        case .delivered: Image(systemName: "checkmark.circle").font(.caption2).accessibilityLabel("Delivered")
        case .read: Image(systemName: "checkmark.circle.fill").font(.caption2).accessibilityLabel("Read")
        case .failed: Image(systemName: "exclamationmark.circle.fill").font(.caption2).accessibilityLabel("Not delivered, tap to send again")
        }
    }
}

struct ReactionChips: View {
    let reactions: [String: String]
    let mine: String?
    let onTap: (String) -> Void

    var body: some View {
        let counts = Dictionary(grouping: reactions.values, by: { $0 }).mapValues(\.count).sorted { $0.value > $1.value || ($0.value == $1.value && $0.key < $1.key) }
        HStack(spacing: 4) {
            ForEach(counts, id: \.key) { emoji, count in
                Button {
                    onTap(emoji)
                } label: {
                    Text(count > 1 ? "\(emoji) \(count)" : emoji)
                        .font(.caption)
                        .padding(.horizontal, 7)
                        .padding(.vertical, 3)
                        .background(mine == emoji ? Palette.accent.opacity(0.3) : Palette.container, in: Capsule())
                }
                .buttonStyle(.plain)
            }
        }
    }
}

struct TypingBubble: View {
    @State private var phase = 0.0

    var body: some View {
        HStack {
            HStack(spacing: 4) {
                ForEach(0..<3) { i in
                    Circle()
                        .fill(Palette.muted)
                        .frame(width: 7, height: 7)
                        .offset(y: sin(phase + Double(i) * 0.8) * 3)
                }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 12)
            .background(Palette.surface, in: Capsule())
            Spacer()
        }
        .onAppear {
            withAnimation(.linear(duration: 1).repeatForever(autoreverses: false)) { phase = .pi * 2 }
        }
        .accessibilityLabel("typing")
    }
}

struct Composer: View {
    @Binding var text: String
    var focused: FocusState<Bool>.Binding
    let placeholder: String
    let onSend: () -> Void
    let onTyping: () -> Void

    var body: some View {
        let bytes = Murmur.utf8Size(text)
        HStack(alignment: .bottom, spacing: 8) {
            TextField(placeholder, text: $text, axis: .vertical)
                .lineLimit(1...6)
                .focused(focused)
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                .background(Palette.surface, in: RoundedRectangle(cornerRadius: 20))
                .overlay(RoundedRectangle(cornerRadius: 20).stroke(Palette.outline))
                .onChange(of: text) { old, new in if new.count > old.count { onTyping() } }
            Button(action: onSend) {
                Image(systemName: "arrow.up.circle.fill")
                    .font(.system(size: 34))
                    .foregroundStyle(canSend ? Palette.accent : Palette.muted)
            }
            .disabled(!canSend)
            .accessibilityLabel("Send")
        }
        .overlay(alignment: .topTrailing) {
            if bytes > Murmur.maxTextBytes - 100 {
                Text("\(Murmur.maxTextBytes - bytes)")
                    .font(.caption2)
                    .foregroundStyle(bytes > Murmur.maxTextBytes ? Palette.danger : Palette.muted)
                    .offset(x: -50, y: -14)
            }
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 8)
        .background(.bar)
    }

    private var canSend: Bool {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        return !t.isEmpty && Murmur.utf8Size(t) <= Murmur.maxTextBytes
    }
}
