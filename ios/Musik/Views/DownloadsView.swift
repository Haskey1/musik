import SwiftUI

/// «Сохранённые»: tracks kept on the phone, playable without a connection.
struct DownloadsView: View {
    @EnvironmentObject var app: AppState
    @EnvironmentObject var player: PlayerController
    @EnvironmentObject var offline: OfflineStore

    @State private var confirmClear = false

    var body: some View {
        let items = offline.savedTracks
        List {
            if !player.networkUp || app.offlineLaunch {
                Label("Нет связи — доступны только сохранённые треки", systemImage: "wifi.slash")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(Theme.accentBright)
                    .listRowBackground(Color.clear)
            }

            Section {
                if items.isEmpty {
                    EmptyHint(text: "Пока пусто. Жми ↓ у трека или ставь ♥ — лайкнутые сохраняются сами.")
                        .listRowBackground(Color.clear)
                } else {
                    HStack(spacing: 10) {
                        Button { play(items, at: 0, shuffle: false) } label: {
                            Label("Играть", systemImage: "play.fill")
                        }
                        .buttonStyle(PrimaryButtonStyle())
                        Button { play(items, at: 0, shuffle: true) } label: {
                            Label("Перемешать", systemImage: "shuffle")
                        }
                        .buttonStyle(ChipButtonStyle())
                    }
                    .listRowBackground(Color.clear)

                    ForEach(Array(items.enumerated()), id: \.element.id) { i, s in
                        TrackRow(track: s.track) { play(items, at: i, shuffle: false) }
                            .swipeActions {
                                Button(role: .destructive) { offline.remove(s.id) } label: {
                                    Label("Удалить", systemImage: "trash")
                                }
                            }
                            .listRowBackground(Color.clear)
                    }
                }
            } header: {
                if !items.isEmpty {
                    Text("\(items.count) \(tracksWord(items.count)) · \(formatBytes(offline.totalBytes))")
                }
            }

            if !offline.downloading.isEmpty {
                Section {
                    HStack(spacing: 10) {
                        ProgressView()
                        Text("Загружается: \(offline.downloading.count)").foregroundStyle(Theme.muted)
                    }
                }
            }

            Section {
                Toggle("Сохранять лайкнутые", isOn: $offline.autoSaveFavorites)
                    .onChange(of: offline.autoSaveFavorites) { on in
                        if on { Task { await app.refreshFavorites() } }
                    }
                if !items.isEmpty {
                    Button("Удалить все сохранённые", role: .destructive) { confirmClear = true }
                }
            } footer: {
                Text("Без интернета радио само переключается на сохранённые треки, а когда связь вернётся — обратно на онлайн-радио. Следующие пару треков приложение подгружает заранее.")
            }
        }
        .listStyle(.plain)
        .resetScrollOnReselect(tab: 2)
        .scrollContentBackground(.hidden)
        .background(Theme.backdrop)
        .navigationTitle("Сохранённые")
        .confirmationDialog("Удалить все сохранённые треки с телефона?", isPresented: $confirmClear,
                            titleVisibility: .visible) {
            Button("Удалить", role: .destructive) { offline.removeAll() }
        }
    }

    private func play(_ items: [SavedTrack], at index: Int, shuffle: Bool) {
        player.playOffline(items.map(\.track), startAt: index, shuffle: shuffle)
    }

    private func tracksWord(_ n: Int) -> String {
        let m10 = n % 10, m100 = n % 100
        if m10 == 1 && m100 != 11 { return "трек" }
        if (2...4).contains(m10) && !(12...14).contains(m100) { return "трека" }
        return "треков"
    }
}
