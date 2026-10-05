// swift-tools-version:5.9
import PackageDescription

// The Murmur wire protocol in Swift: packets, crypto, fragmentation and the mesh. Byte-for-byte
// compatible with the Kotlin :core module (see Tests/MurmurCoreTests/Resources/vectors.json).
let package = Package(
    name: "MurmurCore",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [
        .library(name: "MurmurCore", targets: ["MurmurCore"]),
    ],
    targets: [
        .target(name: "MurmurCore"),
        .testTarget(
            name: "MurmurCoreTests",
            dependencies: ["MurmurCore"],
            resources: [.copy("Resources/vectors.json")]
        ),
    ]
)
