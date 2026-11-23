import Foundation
import UniformTypeIdentifiers

/// Validated upload request, parsed from JS.
struct UploadSpec {
    let id: String
    let filePath: String
    let url: String
    let mode: String // put | multipart
    let method: String
    let provider: String
    let headers: [String: String]
    let fields: [String: String]
    let fileField: String
    let mimeType: String?
    let fileName: String?
    let maxAttempts: Int

    init(json: [String: Any]) throws {
        guard let id = json["id"] as? String, !id.isEmpty else { throw UploadError.invalid("id is required") }
        guard let filePath = json["filePath"] as? String, !filePath.isEmpty else { throw UploadError.invalid("filePath is required for \(id)") }
        guard let url = json["url"] as? String, URL(string: url) != nil else { throw UploadError.invalid("valid url is required for \(id)") }
        let mode = (json["mode"] as? String) ?? "put"
        guard mode == "put" || mode == "multipart" else { throw UploadError.invalid("mode must be put or multipart") }
        let method = (json["method"] as? String)?.uppercased() ?? (mode == "multipart" ? "POST" : "PUT")
        self.id = id
        self.filePath = filePath
        self.url = url
        self.mode = mode
        self.method = method
        self.provider = (json["provider"] as? String) ?? "generic"
        self.headers = (json["headers"] as? [String: String]) ?? [:]
        self.fields = (json["fields"] as? [String: String]) ?? [:]
        self.fileField = (json["fileField"] as? String) ?? "file"
        self.mimeType = json["mimeType"] as? String
        self.fileName = json["fileName"] as? String
        self.maxAttempts = max(1, (json["maxAttempts"] as? Int) ?? 4)
    }
}

enum UploadError: LocalizedError {
    case invalid(String)
    case fileNotFound(String)

    var errorDescription: String? {
        switch self {
        case .invalid(let message): return message
        case .fileNotFound(let path): return "File not found: \(path)"
        }
    }
}

protocol UploadManagerDelegate: AnyObject {
    func uploadProgress(id: String, bytesSent: Int64, totalBytes: Int64, progress: Int)
    func uploadFinished(type: String, id: String, event: [String: Any])
}

/// Owns one background URLSession. Background sessions are executed by the system (nsurlsessiond),
/// so transfers continue while the app is suspended. The OS relaunches the app to deliver results.
final class UploadManager: NSObject {
    static let shared = UploadManager()

    /// Must match the identifier passed to `handleEventsForBackgroundURLSession` in AppDelegate.
    static var sessionIdentifier: String {
        "\(Bundle.main.bundleIdentifier ?? "app").backgroundUploader"
    }

    weak var delegate: UploadManagerDelegate?
    var backgroundCompletionHandler: (() -> Void)?

    private let store = UploadStore.shared
    // Session delegate callbacks run on this serial queue, so these maps need no extra locking.
    private var responseBodies: [Int: Data] = [:]
    private var lastProgressEmit: [String: CFAbsoluteTime] = [:]
    private var progressById: [String: Int] = [:]

    private lazy var session: URLSession = {
        let config = URLSessionConfiguration.background(withIdentifier: UploadManager.sessionIdentifier)
        config.isDiscretionary = false          // start now, do not wait for the system's idle window
        config.sessionSendsLaunchEvents = true  // relaunch the app when uploads finish
        config.waitsForConnectivity = true      // wait for the network instead of failing immediately
        config.httpMaximumConnectionsPerHost = 2
        config.timeoutIntervalForRequest = 120
        config.timeoutIntervalForResource = 6 * 60 * 60
        return URLSession(configuration: config, delegate: self, delegateQueue: nil)
    }()

    /// Creates the session. Must run early so the system can deliver callbacks for tasks started before a relaunch.
    func start() {
        _ = session
    }

    // MARK: - Enqueue

    func enqueue(_ spec: UploadSpec) throws {
        let source = try Self.localURL(spec.filePath)
        guard FileManager.default.fileExists(atPath: source.path) else {
            throw UploadError.fileNotFound(spec.filePath)
        }
        // Active ids are rejected: callers must cancel first. This avoids racing a cancel against a new task.
        if store.get(spec.id) != nil {
            throw UploadError.invalid("upload \(spec.id) is already queued; cancel it first")
        }

        let dir = try Self.workDirectory()
        let fileName = spec.fileName ?? source.lastPathComponent
        let mime = spec.mimeType ?? Self.mimeType(for: fileName)
        let attributes = try FileManager.default.attributesOfItem(atPath: source.path)
        let totalBytes = (attributes[.size] as? NSNumber)?.int64Value ?? 0

        var headers = spec.headers
        if spec.provider == "azure" && !headers.keys.contains(where: { $0.lowercased() == "x-ms-blob-type" }) {
            headers["x-ms-blob-type"] = "BlockBlob"
        }

        let uploadFile: URL
        var workFiles: [URL] = []
        if spec.mode == "multipart" {
            let body = dir.appendingPathComponent("\(spec.id).body")
            let contentType = try MultipartBuilder.build(
                fields: spec.fields, fileField: spec.fileField, fileName: fileName,
                mimeType: mime, source: source, destination: body)
            headers["Content-Type"] = contentType
            uploadFile = body
            workFiles = [body]
        } else {
            // Stage a copy: the source may live in a cache the OS can purge while the upload is pending.
            let staged = dir.appendingPathComponent("\(spec.id).src")
            try? FileManager.default.removeItem(at: staged)
            try FileManager.default.copyItem(at: source, to: staged)
            if !headers.keys.contains(where: { $0.lowercased() == "content-type" }) {
                headers["Content-Type"] = mime
            }
            uploadFile = staged
            workFiles = [staged]
        }

        let record = StoredUpload(
            id: spec.id,
            uploadFile: uploadFile.path,
            workFiles: workFiles.map { $0.path },
            url: spec.url,
            method: spec.method,
            headers: headers,
            fileName: fileName,
            maxAttempts: spec.maxAttempts,
            attempts: 0,
            totalBytes: totalBytes,
            startedAt: Date().timeIntervalSince1970 * 1000,
            status: "queued"
        )
        store.put(record)
        start(record)
    }

    private func start(_ record: StoredUpload) {
        // The record is gone if the upload was already finished or cancelled and removed.
        guard let current = store.get(record.id) else { return }
        if current.status == "cancelled" {
            finish(record.id, type: "failed", event: ["error": "cancelled", "permanent": true, "attempts": current.attempts])
            return
        }
        guard let url = URL(string: record.url) else {
            finish(record.id, type: "failed", event: ["error": "invalid url", "permanent": true, "attempts": record.attempts])
            return
        }
        var request = URLRequest(url: url)
        request.httpMethod = record.method
        for (key, value) in record.headers {
            request.setValue(value, forHTTPHeaderField: key)
        }
        let task = session.uploadTask(with: request, fromFile: URL(fileURLWithPath: record.uploadFile))
        task.taskDescription = record.id
        store.update(record.id) { $0.status = "uploading"; $0.attempts += 1 }
        task.resume()
    }

    // MARK: - Cancel

    func cancel(id: String) {
        guard store.get(id) != nil else { return }
        store.update(id) { $0.status = "cancelled" }
        session.getAllTasks { tasks in
            let matching = tasks.filter { $0.taskDescription == id }
            if matching.isEmpty {
                // Nothing in flight (e.g. waiting for a retry). The pending retry sees "cancelled" and stops.
                self.finish(id, type: "failed", event: ["error": "cancelled", "permanent": true])
            } else {
                matching.forEach { $0.cancel() } // didCompleteWithError finishes the upload
            }
        }
    }

    func cancelAll() {
        for upload in store.all() {
            cancel(id: upload.id)
        }
    }

    // MARK: - Finish helpers

    private func finish(_ id: String, type: String, event: [String: Any]) {
        var payload = event
        payload["id"] = id
        if let record = store.get(id) {
            for path in record.workFiles {
                try? FileManager.default.removeItem(atPath: path)
            }
            store.remove(id)
        }
        progressById.removeValue(forKey: id)
        store.appendResult(id: id, type: type, event: payload)
        delegate?.uploadFinished(type: type, id: id, event: payload)
    }

    static func isTransientStatus(_ code: Int) -> Bool {
        code == 408 || code == 429 || code >= 500
    }

    private static func isTransientError(_ error: Error) -> Bool {
        guard let code = (error as? URLError)?.code else { return false }
        switch code {
        case .timedOut, .networkConnectionLost, .notConnectedToInternet,
             .cannotConnectToHost, .dnsLookupFailed, .cannotFindHost:
            return true
        default:
            return false
        }
    }

    private func retryDelay(attempt: Int) -> TimeInterval {
        min(60, 5 * pow(2, Double(max(0, attempt - 1))))
    }

    // MARK: - Helpers

    static func localURL(_ path: String) throws -> URL {
        if path.hasPrefix("file://"), let url = URL(string: path) {
            return url
        }
        return URL(fileURLWithPath: path)
    }

    static func workDirectory() throws -> URL {
        let base = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
        let dir = base.appendingPathComponent("BackgroundUploader", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    static func mimeType(for fileName: String) -> String {
        let ext = (fileName as NSString).pathExtension
        return UTType(filenameExtension: ext)?.preferredMIMEType ?? "application/octet-stream"
    }

    /// Progress last seen for an active upload (0-100), or nil if unknown.
    func progressFor(id: String) -> Int? {
        progressById[id]
    }
}

// MARK: - URLSessionDataDelegate (upload tasks deliver response bodies here)

extension UploadManager: URLSessionDataDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask, didSendBodyData bytesSent: Int64,
                    totalBytesSent: Int64, totalBytesExpectedToSend: Int64) {
        guard let id = task.taskDescription else { return }
        let total = totalBytesExpectedToSend > 0 ? totalBytesExpectedToSend : (store.get(id)?.totalBytes ?? 0)
        guard total > 0 else { return }
        let progress = min(100, Int(Double(totalBytesSent) / Double(total) * 100))
        progressById[id] = progress

        // Throttle to one event every 500 ms per upload, and always emit 100%.
        let now = CFAbsoluteTimeGetCurrent()
        if progress < 100, let last = lastProgressEmit[id], now - last < 0.5 { return }
        lastProgressEmit[id] = now
        delegate?.uploadProgress(id: id, bytesSent: totalBytesSent, totalBytes: total, progress: progress)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        // Keep the first 64 KB of the response body; enough for typical JSON replies.
        let existing = responseBodies[dataTask.taskIdentifier] ?? Data()
        if existing.count < 65_536 {
            responseBodies[dataTask.taskIdentifier] = existing + data.prefix(65_536 - existing.count)
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        let body = responseBodies.removeValue(forKey: task.taskIdentifier)
        guard let id = task.taskDescription, let record = store.get(id) else { return }
        lastProgressEmit.removeValue(forKey: id)

        let statusCode = (task.response as? HTTPURLResponse)?.statusCode
        let durationMs = Date().timeIntervalSince1970 * 1000 - record.startedAt

        if record.status == "cancelled" {
            finish(id, type: "failed", event: ["error": "cancelled", "permanent": true, "attempts": record.attempts])
            return
        }

        let retryable: Bool
        if let error = error {
            retryable = Self.isTransientError(error)
            if !retryable || record.attempts >= record.maxAttempts {
                finish(id, type: "failed", event: ["error": error.localizedDescription, "permanent": !retryable,
                                                   "attempts": record.attempts])
                return
            }
        } else if let code = statusCode, (200..<300).contains(code) {
            let response = body.flatMap { String(data: $0, encoding: .utf8) } ?? ""
            finish(id, type: "completed", event: ["statusCode": code, "response": response,
                                                  "totalBytes": record.totalBytes, "durationMs": Int(durationMs)])
            return
        } else {
            let code = statusCode ?? 0
            retryable = Self.isTransientStatus(code)
            if !retryable || record.attempts >= record.maxAttempts {
                finish(id, type: "failed", event: ["error": "HTTP \(code)", "statusCode": code,
                                                   "permanent": !retryable, "attempts": record.attempts])
                return
            }
        }

        // Transient failure: retry the same file after a backoff.
        let delay = retryDelay(attempt: record.attempts)
        DispatchQueue.global().asyncAfter(deadline: .now() + delay) { [weak self] in
            guard let self = self, let latest = self.store.get(id) else { return }
            self.start(latest)
        }
    }

    func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        DispatchQueue.main.async {
            self.backgroundCompletionHandler?()
            self.backgroundCompletionHandler = nil
        }
    }
}
