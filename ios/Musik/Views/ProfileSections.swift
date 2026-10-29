import SwiftUI
import UIKit

/// One screen per profile section; `Route.profile` lands here.
struct ProfileSectionView: View {
    let section: ProfileSection

    var body: some View {
        switch section {
        case .taste: TasteSection()
        case .playback: PlaybackSection()
        case .storage: StorageSection()
        case .hidden: HiddenSection()
        case .share: ShareSection()
        case .server: ServerSection()
        }
    }
}

private extension View {
    func profileScreen(_ title: String) -> some View {
        self.scrollContentBackground(.hidden)
            .background(Theme.backdrop)
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
    }
}

// MARK: - Taste

private struct TasteSection: View {
    @EnvironmentObject var app: AppState
    @EnvironmentObject var player: PlayerController
    @State private var profile: Profile?

    var body: some View {
        List {
            Section {
                VStack(alignment: .leading, spacing: 8) {
                    Text(Labels.maturity(profile?.maturity ?? player.maturity)).font(.headline)
                    if let p = profile {
                        ProgressView(value: min(1, max(0, p.confidence ?? 0))).tint(Theme.accent)
                        Text("\(p.nPositive ?? 0) положительных сигналов из \(p.readyAt ?? 8) · \(p.nNegative ?? 0) скипов/дизлайков")
                            .font(.caption)
                            .foregroundStyle(Theme.muted)
                        if let explore = p.exploreRatio {
                            Text("Доля исследования: \(Int((explore * 100).rounded()))%")
                                .font(.caption)
                                .foregroundStyle(Theme.muted)
                        }
                    }
                }
                .padding(.vertical, 4)
            }
            if let top = profile?.topArtists, !top.isEmpty {
                Section("Чаще всего слушаешь") {
                    ForEach(top, id: \.artist) { a in
                        NavigationLink(value: Route.artist(a.artist)) {
                            HStack {
                                Text(a.artist)
                                Spacer()
                                Text("\(a.count)").foregroundStyle(Theme.muted).monospacedDigit()
                            }
                        }
                    }
                }
            }
        }
        .profileScreen("Вкус")
        .refreshable { profile = (try? await app.api.profile()) ?? profile }
        .task { profile = (try? await app.api.profile()) ?? profile }
    }
}

// MARK: - Playback

private struct PlaybackSection: View {
    @EnvironmentObject var app: AppState
    @State private var mobileStream = Settings.shared.mobileStream
    @State private var haptics = Settings.shared.haptics
    @State private var crossfadeEnabled = Settings.shared.crossfadeEnabled
    @State private var crossfadeSeconds = Double(Settings.shared.crossfadeSeconds)
    @State private var crossfadeKeepAlbums = Settings.shared.crossfadeKeepAlbums

    var body: some View {
        List {
            Section {
                Toggle("Мобильный поток (AAC/MP3)", isOn: $mobileStream)
                    .onChange(of: mobileStream) { app.settings.mobileStream = $0 }
            } header: {
                Text("Качество")
            } footer: {
                Text("Сервер отдаёт перекодированную версию, если она готова, иначе — оригинал. Выключи, чтобы всегда слушать оригинал (FLAC и т. п.).")
            }

            Section {
                Toggle("Кроссфейд", isOn: $crossfadeEnabled)
                    .onChange(of: crossfadeEnabled) { app.settings.crossfadeEnabled = $0 }
                if crossfadeEnabled {
                    VStack(alignment: .leading, spacing: 4) {
                        HStack {
                            Text("Длительность")
                            Spacer()
                            Text("\(Int(crossfadeSeconds)) с").foregroundStyle(Theme.muted).monospacedDigit()
                        }
                        Slider(value: $crossfadeSeconds, in: 1...12, step: 1)
                            .onChange(of: crossfadeSeconds) { app.settings.crossfadeSeconds = Int($0) }
                    }
                    Toggle("Альбомы без наложения", isOn: $crossfadeKeepAlbums)
                        .onChange(of: crossfadeKeepAlbums) { app.settings.crossfadeKeepAlbums = $0 }
                }
            } header: {
                Text("Переходы между треками")
            } footer: {
                Text("Конец трека плавно перетекает в начало следующего. «Альбомы без наложения» оставляет переходы внутри одного альбома без паузы и без наложения. Вручную переключённый трек сменяется сразу.")
            }

            Section {
                Toggle("Тактильный отклик", isOn: $haptics)
                    .onChange(of: haptics) { app.settings.haptics = $0 }
            } header: {
                Text("Вибрация")
            } footer: {
                Text("Лёгкая вибрация при лайке, снятии лайка и дизлайке. Если её нет совсем, проверь в настройках iPhone: «Звуки, тактильные сигналы» → «Системные тактильные сигналы».")
            }
        }
        .profileScreen("Воспроизведение")
    }
}

// MARK: - Storage

private struct StorageSection: View {
    @EnvironmentObject var app: AppState
    @EnvironmentObject var offline: OfflineStore
    @State private var prefetchNext = Settings.shared.prefetchNext
    @State private var confirmClear = false

    var body: some View {
        List {
            Section {
                Toggle("Сохранять лайкнутые на телефон", isOn: $offline.autoSaveFavorites)
                    .onChange(of: offline.autoSaveFavorites) { on in
                        if on { Task { await app.refreshFavorites() } }
                    }
                Toggle("Подгружать следующие треки", isOn: $prefetchNext)
                    .onChange(of: prefetchNext) { app.settings.prefetchNext = $0 }
            } header: {
                Text("Автоскачивание")
            } footer: {
                Text("Первое: любимые треки сами скачиваются на телефон (и удаляются, когда снимаешь ♥). Второе: два следующих трека загружаются заранее, чтобы музыка не обрывалась при плохой связи. Выключи — приложение качает только то, что ты сохранил сам.")
            }

            Section {
                LabeledContent("Сохранено", value: "\(offline.savedTracks.count)")
                LabeledContent("Занято на телефоне", value: formatBytes(offline.totalBytes))
                Button("Удалить все сохранённые", role: .destructive) { confirmClear = true }
                    .disabled(offline.savedTracks.isEmpty)
            } header: {
                Text("Память")
            } footer: {
                Text("Без интернета радио само переключается на сохранённые треки, а когда связь вернётся — обратно на онлайн-радио.")
            }
        }
        .profileScreen("Загрузки и память")
        .confirmationDialog("Удалить все сохранённые треки с телефона?", isPresented: $confirmClear,
                            titleVisibility: .visible) {
            Button("Удалить", role: .destructive) { offline.removeAll() }
        }
    }
}

// MARK: - Hidden

private struct HiddenSection: View {
    @EnvironmentObject var app: AppState
    @State private var rules: [RadioRule] = []
    @State private var loaded = false

    var body: some View {
        List {
            Section {
                if loaded && rules.isEmpty {
                    Text("Ничего не скрыто").foregroundStyle(Theme.muted)
                }
                ForEach(rules) { rule in
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(ruleTitle(rule)).lineLimit(1)
                            Text(rule.action == "block" ? "скрыто" : "реже")
                                .font(.caption)
                                .foregroundStyle(rule.action == "block" ? Theme.accent : Theme.muted)
                        }
                        Spacer()
                        Button("Вернуть") { app.perform { try await unhide(rule) } }
                            .buttonStyle(.borderless)
                    }
                    .swipeActions {
                        Button { app.perform { try await unhide(rule) } } label: {
                            Label("Вернуть", systemImage: "arrow.uturn.backward")
                        }
                    }
                }
            } footer: {
                Text("Скрыть трек или артиста — в меню трека или «⋯» в плеере. После трёх быстрых скипов подряд артист сам попадает в «реже».")
            }
        }
        .profileScreen("Скрыто и реже")
        .refreshable { await load() }
        .task { await load() }
    }

    private func load() async {
        if let r = try? await app.api.rules() {
            rules = r.filter { ["artist", "track", "song", "album"].contains($0.targetType) }
        }
        loaded = true
    }

    private func ruleTitle(_ rule: RadioRule) -> String {
        switch rule.targetType {
        case "artist": return "Артист: \(rule.targetKey)"
        case "album": return "Альбом: \(rule.targetKey.replacingOccurrences(of: "|", with: " — "))"
        default: return "Трек"
        }
    }

    private func unhide(_ rule: RadioRule) async throws {
        try await app.api.deleteRule(rule.ruleId)
        if rule.targetType == "artist" { ArtistSkips.reset(rule.targetKey) }
        rules.removeAll { $0.id == rule.id }
        app.show("Вернул")
    }
}

// MARK: - Share radio

private struct ShareSection: View {
    @EnvironmentObject var app: AppState
    @State private var shares: [ShareLink] = []
    @State private var shareItem: ShareItem?

    var body: some View {
        List {
            Section {
                ForEach(shares) { share in
                    VStack(alignment: .leading, spacing: 6) {
                        Text(share.name ?? "musik radio").font(.subheadline.weight(.semibold))
                        Text(share.url).font(.caption.monospaced()).foregroundStyle(Theme.muted).lineLimit(1)
                        Text("Слушали: \(share.listenCount ?? 0)").font(.caption2).foregroundStyle(Theme.muted)
                    }
                    .swipeActions {
                        Button(role: .destructive) {
                            app.perform { try await revoke(share) }
                        } label: { Label("Отозвать", systemImage: "trash") }
                    }
                    .contextMenu {
                        Button {
                            UIPasteboard.general.string = share.url
                            app.show("Скопировано")
                        } label: { Label("Копировать", systemImage: "doc.on.doc") }
                        if let url = URL(string: share.url) {
                            Button { shareItem = ShareItem(url: url) } label: {
                                Label("Поделиться", systemImage: "square.and.arrow.up")
                            }
                        }
                        Button(role: .destructive) {
                            app.perform { try await revoke(share) }
                        } label: { Label("Отозвать", systemImage: "trash") }
                    }
                }
                Button {
                    app.perform { try await createShare() }
                } label: {
                    Label("Новая ссылка на эфир", systemImage: "plus.circle")
                }
            } footer: {
                Text("Непрерывный MP3-эфир для VLC или браузера. Слушатели не влияют на твой вкус. Долгое нажатие — копировать, свайп — отозвать.")
            }
        }
        .profileScreen("Share radio")
        .refreshable { shares = (try? await app.api.shares()) ?? shares }
        .task { shares = (try? await app.api.shares()) ?? shares }
        .sheet(item: $shareItem) { item in
            ActivityView(items: [item.url]).presentationDetents([.medium, .large])
        }
    }

    private func createShare() async throws {
        let created = try await app.api.createShare()
        shares = (try? await app.api.shares()) ?? shares
        if let raw = created.url {
            UIPasteboard.general.string = raw
            app.show("Ссылка скопирована")
            if let url = URL(string: raw) { shareItem = ShareItem(url: url) }
        }
    }

    private func revoke(_ share: ShareLink) async throws {
        try await app.api.revokeShare(share.token)
        shares.removeAll { $0.token == share.token }
        app.show("Ссылка отозвана")
    }
}

// MARK: - Server & account

private struct ServerSection: View {
    @EnvironmentObject var app: AppState
    @State private var health: Health?
    @State private var confirmLogout = false

    var body: some View {
        List {
            Section("Сервер") {
                LabeledContent("Адрес", value: app.settings.baseURL)
                if let h = health {
                    LabeledContent("Версия", value: [h.version, h.apiVersion].compactMap { $0 }.joined(separator: " · "))
                    if let n = h.tracks { LabeledContent("Треков", value: "\(n)") }
                }
                LabeledContent("Приложение", value: appVersion)
            }
            Section {
                Button("Выйти", role: .destructive) { confirmLogout = true }
            }
        }
        .profileScreen("Сервер и аккаунт")
        .refreshable { health = (try? await app.api.health()) ?? health }
        .task { health = (try? await app.api.health()) ?? health }
        .confirmationDialog("Выйти из аккаунта?", isPresented: $confirmLogout, titleVisibility: .visible) {
            Button("Выйти", role: .destructive) { Task { await app.logout() } }
        }
    }

    private var appVersion: String {
        let info = Bundle.main.infoDictionary
        let v = info?["CFBundleShortVersionString"] as? String ?? "?"
        let b = info?["CFBundleVersion"] as? String ?? "?"
        return "\(v) (\(b))"
    }
}
