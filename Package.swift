// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "CapacitorBackgroundUploader",
    platforms: [.iOS(.v15)],
    products: [
        .library(name: "CapacitorBackgroundUploader", targets: ["BackgroundUploaderPlugin"])
    ],
    dependencies: [
        // Verify the Capacitor SPM package URL and version against the Capacitor 8 docs.
        .package(url: "https://github.com/ionic-team/capacitor-swift-pm.git", from: "8.0.0")
    ],
    targets: [
        .target(
            name: "BackgroundUploaderPlugin",
            dependencies: [
                .product(name: "Capacitor", package: "capacitor-swift-pm"),
            ],
            path: "ios/Plugin"
        )
    ]
)
