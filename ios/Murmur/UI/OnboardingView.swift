import MurmurCore
import SwiftUI

/// First launch: what Murmur is, then a name, emoji and colour.
struct OnboardingView: View {
    @Environment(AppModel.self) private var model
    @State private var step = 0
    @State private var draft = ProfileDraft.random()

    var body: some View {
        ZStack {
            Palette.background.ignoresSafeArea()
            if step == 0 {
                welcome.transition(.asymmetric(insertion: .opacity, removal: .move(edge: .leading).combined(with: .opacity)))
            } else {
                profile.transition(.move(edge: .trailing).combined(with: .opacity))
            }
        }
        .animation(.spring(duration: 0.4), value: step)
    }

    private var welcome: some View {
        VStack(spacing: 24) {
            Spacer()
            Image(systemName: "dot.radiowaves.left.and.right")
                .font(.system(size: 64, weight: .semibold))
                .foregroundStyle(Palette.accent)
                .symbolEffect(.variableColor.iterative, options: .repeating)
            Text("Murmur")
                .font(.system(size: 44, weight: .bold, design: .rounded))
                .foregroundStyle(Palette.text)
            Text("Chat with people nearby.\nNo internet. No accounts. No servers.")
                .font(.title3)
                .multilineTextAlignment(.center)
                .foregroundStyle(Palette.muted)
            VStack(alignment: .leading, spacing: 14) {
                Feature(icon: "antenna.radiowaves.left.and.right", text: "Phones talk over Bluetooth and pass messages along, so it reaches further than one phone can.")
                Feature(icon: "lock.fill", text: "Direct messages are end-to-end encrypted.")
                Feature(icon: "iphone.and.arrow.forward", text: "Works with Murmur on Android, too.")
            }
            .padding(.top, 8)
            .padding(.horizontal, 28)
            Spacer()
            Button {
                step = 1
            } label: {
                Text("Get started").font(.headline).frame(maxWidth: .infinity).padding(.vertical, 6)
            }
            .buttonStyle(.borderedProminent)
            .tint(Palette.accent)
            .foregroundStyle(Palette.onAccent)
            .padding(.horizontal, 24)
            .padding(.bottom, 24)
        }
    }

    private var profile: some View {
        VStack(spacing: 0) {
            ScrollView {
                VStack(spacing: 20) {
                    Text("Who are you nearby?")
                        .font(.title.bold())
                        .foregroundStyle(Palette.text)
                        .padding(.top, 32)
                    Text("People in range see this name and avatar. You can change it any time.")
                        .multilineTextAlignment(.center)
                        .foregroundStyle(Palette.muted)
                        .padding(.horizontal)
                    ProfileEditor(draft: $draft)
                }
                .padding(.horizontal, 20)
            }
            Button {
                model.completeOnboarding(draft.profile)
            } label: {
                Text("Start murmuring").font(.headline).frame(maxWidth: .infinity).padding(.vertical, 6)
            }
            .buttonStyle(.borderedProminent)
            .tint(Palette.accent)
            .foregroundStyle(Palette.onAccent)
            .disabled(!draft.isValid)
            .padding(24)
        }
    }

    private struct Feature: View {
        let icon: String
        let text: String

        var body: some View {
            HStack(alignment: .top, spacing: 12) {
                Image(systemName: icon).foregroundStyle(Palette.accent).frame(width: 24)
                Text(text).foregroundStyle(Palette.text)
            }
        }
    }
}

struct ProfileDraft: Equatable {
    var nickname = ""
    var emoji = AvatarPalette.emojis[0]
    var colorIndex = 0

    static func random() -> ProfileDraft {
        ProfileDraft(emoji: AvatarPalette.emojis.randomElement()!, colorIndex: Int.random(in: 0..<AvatarPalette.colors.count))
    }

    init(nickname: String = "", emoji: String = AvatarPalette.emojis[0], colorIndex: Int = 0) {
        self.nickname = nickname
        self.emoji = emoji
        self.colorIndex = colorIndex
    }

    init(_ profile: Profile) {
        self.init(nickname: profile.nickname, emoji: profile.emoji, colorIndex: profile.colorIndex)
    }

    var trimmed: String { nickname.trimmingCharacters(in: .whitespacesAndNewlines) }
    var profile: Profile { Profile(nickname: trimmed, emoji: emoji, colorIndex: colorIndex) }
    var isValid: Bool { profile.isValid }
}

/// Name, emoji and colour (onboarding and Settings).
struct ProfileEditor: View {
    @Binding var draft: ProfileDraft
    @FocusState private var nameFocused: Bool

    var body: some View {
        VStack(spacing: 20) {
            AvatarView(emoji: draft.emoji, colorIndex: draft.colorIndex, size: 96)
                .animation(.spring(duration: 0.3), value: draft)
            VStack(alignment: .leading, spacing: 6) {
                TextField("Your name", text: $draft.nickname)
                    .textInputAutocapitalization(.words)
                    .autocorrectionDisabled()
                    .focused($nameFocused)
                    .padding(12)
                    .background(Palette.container, in: RoundedRectangle(cornerRadius: 12))
                    .onChange(of: draft.nickname) { _, new in
                        if Murmur.nicknameLength(new) > Murmur.nicknameMaxChars {
                            draft.nickname = String(new.unicodeScalars.prefix(Murmur.nicknameMaxChars).map(Character.init))
                        }
                    }
                Text("\(Murmur.nicknameLength(draft.trimmed))/\(Murmur.nicknameMaxChars)")
                    .font(.caption)
                    .foregroundStyle(Palette.muted)
                    .frame(maxWidth: .infinity, alignment: .trailing)
            }
            LazyVGrid(columns: Array(repeating: GridItem(.flexible()), count: 6), spacing: 10) {
                ForEach(AvatarPalette.emojis, id: \.self) { e in
                    Button {
                        draft.emoji = e
                    } label: {
                        Text(e)
                            .font(.system(size: 28))
                            .frame(width: 46, height: 46)
                            .background(draft.emoji == e ? Palette.accent.opacity(0.25) : Color.clear, in: Circle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Avatar \(e)")
                    .accessibilityAddTraits(draft.emoji == e ? .isSelected : [])
                }
            }
            HStack(spacing: 10) {
                ForEach(0..<AvatarPalette.colors.count, id: \.self) { i in
                    Button {
                        draft.colorIndex = i
                    } label: {
                        Circle()
                            .fill(Palette.avatar(i))
                            .frame(width: 28, height: 28)
                            .overlay(Circle().stroke(Palette.text, lineWidth: draft.colorIndex == i ? 3 : 0))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(AvatarPalette.names[i])
                    .accessibilityAddTraits(draft.colorIndex == i ? .isSelected : [])
                }
            }
        }
    }
}
