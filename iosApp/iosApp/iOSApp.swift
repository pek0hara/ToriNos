import SwiftUI
import ComposeApp

@main
struct iOSApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
                .ignoresSafeArea(.container, edges: .vertical)
                // torinos://join?… の招待（docs/buzz-invite-link-design.md 5.2）。起動していなくても届く。
                .onOpenURL { url in
                    MainViewControllerKt.handleOpenUrl(url: url.absoluteString)
                }
        }
    }
}
