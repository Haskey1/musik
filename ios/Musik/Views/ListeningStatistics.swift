import SwiftUI

/// Personal listening history, including music started outside radio.
struct ListeningStatisticsSection: View {
    @EnvironmentObject var app: AppState
    @State private var statistics: ListeningStatistics?
    @State private var loading = false
    @State private var error: String?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                if let statistics {
                    period(statistics.weekly, title: "За неделю",
                           subtitle: "Время, дослушивания и то, что звучало чаще за последние 7 дней.")
                    period(statistics.yearly, title: "\(String(statistics.year)) в музыке",
                           subtitle: "Твои прослушивания с начала года.")
                } else if loading {
                    ProgressView().frame(maxWidth: .infinity).padding(.top, 40)
                }
                if let error {
                    VStack(alignment: .leading, spacing: 12) {
                        Text(error).foregroundStyle(Theme.muted)
                        Button("Попробовать снова") { Task { await load() } }
                            .buttonStyle(ChipButtonStyle())
                    }
                }
            }
            .padding(16)
        }
        .background(Theme.backdrop)
        .navigationTitle("Статистика")
        .navigationBarTitleDisplayMode(.inline)
        .task { await load() }
        .refreshable { await load() }
    }

    private func period(_ data: ListeningPeriod, title: String, subtitle: String) -> some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(title).font(.title2.bold())
            Text(subtitle).font(.subheadline).foregroundStyle(Theme.muted)
            if data.plays == 0 {
                Label("Включи музыку — здесь появится твоя статистика", systemImage: "music.note")
                    .foregroundStyle(Theme.muted).padding(.vertical, 12)
            } else {
                statistic(duration(data.listenedSeconds), label: "время")
                statistic("\(Int((data.finishRate * 100).rounded()))%", label: "дослушано")
                statistic("\(data.plays)", label: "запусков")
                if !data.tracks.isEmpty {
                    Divider().padding(.vertical, 4)
                    Text("Треки").font(.headline)
                    ForEach(data.tracks) { track in
                        Button {
                            app.player.play(["track_id": track.trackId, "name": track.title])
                        } label: {
                            VStack(alignment: .leading, spacing: 10) {
                                Text([track.artist, track.title].filter { !$0.isEmpty }.joined(separator: " — "))
                                    .font(.subheadline).multilineTextAlignment(.leading)
                                Text("\(track.count)").font(.caption).foregroundStyle(Theme.muted)
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(14)
                            .background(Theme.background.opacity(0.45), in: RoundedRectangle(cornerRadius: 16))
                            .overlay(RoundedRectangle(cornerRadius: 16).stroke(Color.white.opacity(0.08)))
                        }
                        .buttonStyle(.plain)
                    }
                }
                if !data.artists.isEmpty {
                    Divider().padding(.vertical, 4)
                    Text("Артисты").font(.headline)
                    MoodLayout {
                        ForEach(data.artists, id: \.artist) { artist in
                            NavigationLink(value: Route.artist(artist.artist)) {
                                Text("\(artist.artist) · \(artist.count)")
                                    .font(.caption).padding(.horizontal, 12).padding(.vertical, 8)
                                    .background(Theme.background.opacity(0.45), in: Capsule())
                                    .overlay(Capsule().stroke(Color.white.opacity(0.12)))
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
            }
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.surface, in: RoundedRectangle(cornerRadius: 24))
    }

    private func statistic(_ value: String, label: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(value).font(.display(28)).monospacedDigit()
            Text(label).font(.caption).foregroundStyle(Theme.muted)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .background(Theme.background.opacity(0.45), in: RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(Color.white.opacity(0.08)))
    }

    private func duration(_ seconds: Double) -> String {
        let minutes = max(0, Int(seconds / 60))
        if minutes >= 60 { return "\(minutes / 60) ч \(minutes % 60) мин" }
        return minutes == 0 && seconds > 0 ? "Меньше минуты" : "\(minutes) мин"
    }

    private func load() async {
        guard !loading else { return }
        loading = true
        defer { loading = false }
        do {
            statistics = try await app.api.listeningStatistics()
            error = nil
        } catch let failure as APIError where failure.isNotFound {
            error = "Для статистики нужно обновить сервер musik до версии с поддержкой истории прослушиваний."
        } catch {
            self.error = "Не удалось загрузить статистику. \(error.localizedDescription)"
        }
    }
}
