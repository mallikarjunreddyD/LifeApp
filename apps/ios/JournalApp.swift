import SwiftUI
import JournalUI

@main
struct JournalApp: App {
    var body: some Scene { WindowGroup { JournalView().ignoresSafeArea() } }
}
struct JournalView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }
    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
