import Foundation

/// Persisted state for one upload. Survives app kills and relaunches.
struct StoredUpload: Codable {
    let id: String
    /// File that URLSession actually streams (staged copy for put, built multipart body for multipart).
    let uploadFile: String
    /// Files to delete when the upload finishes or is cancelled.
    let workFiles: [String]
    let url: String
    let method: String
    let headers: [String: String]
    let fileName: String
    let maxAttempts: Int
    var attempts: Int
    let totalBytes: Int64
    let startedAt: Double // epoch ms
    var status: String // queued | uploading | cancelled
}

/// Thread-safe persistence in UserDefaults. Small volume (one entry per upload), so JSON blobs are fine.
final class UploadStore {
    static let shared = UploadStore()

    private let defaults = UserDefaults.standard
    private let lock = NSLock()
    private let uploadsKey = "bgu.uploads.v1"
    private let resultsKey = "bgu.results.v1"

    private func readUploads() -> [String: StoredUpload] {
        guard let data = defaults.data(forKey: uploadsKey),
              let map = try? JSONDecoder().decode([String: StoredUpload].self, from: data) else { return [:] }
        return map
    }

    private func writeUploads(_ map: [String: StoredUpload]) {
        if let data = try? JSONEncoder().encode(map) {
            defaults.set(data, forKey: uploadsKey)
        }
    }

    func get(_ id: String) -> StoredUpload? {
        lock.lock(); defer { lock.unlock() }
        return readUploads()[id]
    }

    func all() -> [StoredUpload] {
        lock.lock(); defer { lock.unlock() }
        return Array(readUploads().values)
    }

    func put(_ upload: StoredUpload) {
        lock.lock(); defer { lock.unlock() }
        var map = readUploads()
        map[upload.id] = upload
        writeUploads(map)
    }

    func update(_ id: String, _ mutate: (inout StoredUpload) -> Void) {
        lock.lock(); defer { lock.unlock() }
        var map = readUploads()
        guard var upload = map[id] else { return }
        mutate(&upload)
        map[id] = upload
        writeUploads(map)
    }

    func remove(_ id: String) {
        lock.lock(); defer { lock.unlock() }
        var map = readUploads()
        map.removeValue(forKey: id)
        writeUploads(map)
    }

    // MARK: - Pending results (finished while JS was not listening)

    func appendResult(id: String, type: String, event: [String: Any]) {
        lock.lock(); defer { lock.unlock() }
        var list = readResults()
        list.removeAll { ($0["id"] as? String) == id }
        list.append(["id": id, "type": type, "event": event])
        writeResults(list)
    }

    func pendingResults() -> [[String: Any]] {
        lock.lock(); defer { lock.unlock() }
        return readResults()
    }

    func acknowledge(ids: [String]) {
        lock.lock(); defer { lock.unlock() }
        let set = Set(ids)
        writeResults(readResults().filter { !set.contains($0["id"] as? String ?? "") })
    }

    private func readResults() -> [[String: Any]] {
        guard let data = defaults.data(forKey: resultsKey),
              let list = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else { return [] }
        return list
    }

    private func writeResults(_ list: [[String: Any]]) {
        if let data = try? JSONSerialization.data(withJSONObject: list) {
            defaults.set(data, forKey: resultsKey)
        }
    }
}
