import SwiftUI

@main
struct MusikApp: App {
    @StateObject private var app = AppState()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(app)
                .environmentObject(app.player)
                .environmentObject(app.player.clock)
                .environmentObject(app.offline)
                .preferredColorScheme(.dark)
                .tint(Theme.accent)
        }
    }
}

struct RootView: View {
    @EnvironmentObject var app: AppState

    var body: some View {
        ZStack {
            Theme.backdrop
            switch app.phase {
            case .checking:
                VStack(spacing: 16) {
                    Text("musik").font(.display(44))
                    ProgressView()
                }
            case .login:
                LoginView()
            case .ready:
                MainView()
            }
        }
        .overlay(alignment: .top) { ToastView() }
        .task { await app.bootstrap() }
    }
}

struct MainView: View {
    @EnvironmentObject var app: AppState
    @EnvironmentObject var player: PlayerController
    @State private var showPlayer = false
    @State private var selection = 0
    @State private var paths: [NavigationPath] = Array(repeating: NavigationPath(), count: 4)

    var body: some View {
        TabView(selection: $selection) {
            tab(0) { HomeView() }
                .tabItem { Label("Главная", systemImage: "house.fill") }
                .tag(0)
            tab(1) { LibraryView() }
                .tabItem { Label("Библиотека", systemImage: "music.note.list") }
                .tag(1)
            tab(2) { DownloadsView() }
                .tabItem { Label("Сохранённые", systemImage: "arrow.down.circle.fill") }
                .tag(2)
            tab(3) { ProfileView() }
                .tabItem { Label("Профиль", systemImage: "person.crop.circle") }
                .tag(3)
        }
        .sheet(isPresented: $showPlayer) {
            NowPlayingView()
                .environmentObject(app)
                .environmentObject(player)
                .environmentObject(player.clock)
                .environmentObject(app.offline)
                .presentationDragIndicator(.visible)
        }
        .onChange(of: app.pendingRoute) { route in
            guard let route else { return }
            app.pendingRoute = nil
            showPlayer = false
            // the sheet slides away first, then the screen is pushed
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) {
                paths[selection].append(route)
            }
        }
    }

    private func tab<Content: View>(_ index: Int, @ViewBuilder _ content: () -> Content) -> some View {
        NavigationStack(path: $paths[index]) {
            content()
                .withRoutes()
                .miniPlayerSpace()
        }
        // The mini player floats over the tab; every scrolling screen reserves room for it
        // (`miniPlayerSpace`), so the last rows — «Выйти» on the profile — stay reachable.
        .overlay(alignment: .bottom) {
            if player.current != nil {
                MiniPlayer { showPlayer = true }
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(.easeOut(duration: 0.25), value: player.current?.id)
    }
}
