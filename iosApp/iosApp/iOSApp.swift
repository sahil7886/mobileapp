import SwiftUI
import ComposeApp

@main
struct iOSApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) var appDelegate
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            ContentView()
                .onOpenURL(perform: handleURL)
                .onChange(of: scenePhase) { phase in
                    switch phase {
                    case .active:
                        IOSDelegate.shared.sceneDidBecomeActive()
                    case .inactive:
                        IOSDelegate.shared.sceneWillResignActive()
                    case .background:
                        IOSDelegate.shared.sceneDidEnterBackground()
                    @unknown default:
                        break
                    }
                }
        }
    }
    
    func handleURL(_ url: URL) {
        IOSDelegate.shared.handleOpenUrl(url: url)
    }
}
