import Foundation

// Update only the existing Kotlin CAD tiles. Keep every unrelated Dock item and preference intact.
let appURL = URL(fileURLWithPath: CommandLine.arguments[1], isDirectory: true)
let defaults = UserDefaults.standard
var domain = defaults.persistentDomain(forName: "com.apple.dock") ?? [:]
var items = domain["persistent-apps"] as? [[String: Any]] ?? []
var changed = 0
for index in items.indices {
    guard var data = items[index]["tile-data"] as? [String: Any] else { continue }
    let label = data["file-label"] as? String
    let identifier = data["bundle-identifier"] as? String
    guard label == "Kotlin CAD" || identifier == "local.kotlincad.viewer" else { continue }
    data["file-data"] = ["_CFURLString": appURL.absoluteString, "_CFURLStringType": 15]
    data["file-label"] = "Kotlin CAD"
    data["file-type"] = 41 // Application bundle, rather than the old standalone Unix executable.
    data["bundle-identifier"] = "local.kotlincad.viewer"
    data.removeValue(forKey: "book") // Discard the bookmark that still resolves to the legacy executable.
    data.removeValue(forKey: "file-mod-date")
    data.removeValue(forKey: "parent-mod-date")
    items[index]["tile-data"] = data
    changed += 1
}
if changed > 0 {
    domain["persistent-apps"] = items
    defaults.setPersistentDomain(domain, forName: "com.apple.dock")
    defaults.synchronize()
}
print("Aktualisierte CAD-Einträge im Dock: \(changed)")
