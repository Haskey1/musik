import SwiftUI

/// The sub-screens of the profile. The tab itself is only a short menu; every group of
/// settings lives on its own screen (see ProfileSections.swift).
enum ProfileSection: String, Hashable {
    case taste, playback, storage, hidden, share, server
}

struct ProfileView: View {
    @EnvironmentObject var app: AppState
    @EnvironmentObject var player: PlayerController
    @EnvironmentObject var offline: OfflineStore

    @State private var profile: Profile?

    var body: some View {
        List {
            Section {
                NavigationLink(value: Route.profile(.taste)) { tasteSummary }
            }

            Section {
                row(.playback, "Воспроизведение", "play.circle.fill", .orange,
                    subtitle: "Качество, переходы, вибрация")
                row(.storage, "Загрузки и память", "arrow.down.circle.fill", .teal,
                    subtitle: storageSubtitle)
            }

            Section {
                row(.share, "Share radio", "dot.radiowaves.left.and.right", .pink,
                    subtitle: "Ссылки на эфир для друзей")
                row(.hidden, "Скрыто и реже", "eye.slash.fill", .purple,
                    subtitle: "Треки и артисты, которые ты убрал")
            }

            Section {
                row(.server, "Сервер и аккаунт", "server.rack", .gray,
                    subtitle: serverHost)
            }
        }
        .resetScrollOnReselect(tab: 3)
        .scrollContentBackground(.hidden)
        .background(Theme.backdrop)
        .navigationTitle("Профиль")
        .refreshable { profile = (try? await app.api.profile()) ?? profile }
        .task { profile = (try? await app.api.profile()) ?? profile }
    }

    private var tasteSummary: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Вкус").font(.caption.weight(.semibold)).foregroundStyle(Theme.muted)
            Text(Labels.maturity(profile?.maturity ?? player.maturity)).font(.headline)
            if let p = profile {
                ProgressView(value: min(1, max(0, p.confidence ?? 0))).tint(Theme.accent)
            }
        }
        .padding(.vertical, 4)
    }

    private func row(_ section: ProfileSection, _ title: String, _ icon: String, _ color: Color,
                     subtitle: String?) -> some View {
        NavigationLink(value: Route.profile(section)) {
            HStack(spacing: 12) {
                Image(systemName: icon)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(width: 30, height: 30)
                    .background(color.gradient, in: RoundedRectangle(cornerRadius: 7, style: .continuous))
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                    if let subtitle {
                        Text(subtitle).font(.caption).foregroundStyle(Theme.muted).lineLimit(1)
                    }
                }
            }
            .padding(.vertical, 2)
        }
    }

    private var storageSubtitle: String {
        let n = offline.savedTracks.count
        return n == 0 ? "Ничего не сохранено" : "\(n) · \(formatBytes(offline.totalBytes))"
    }

    private var serverHost: String? {
        let base = app.settings.baseURL
        return URL(string: base)?.host ?? (base.isEmpty ? nil : base)
    }
}
