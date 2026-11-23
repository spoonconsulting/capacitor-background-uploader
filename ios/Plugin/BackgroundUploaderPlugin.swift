import Foundation
import Capacitor

@objc(BackgroundUploaderPlugin)
public class BackgroundUploaderPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "BackgroundUploaderPlugin"
    public let jsName = "BackgroundUploader"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "enqueue", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "cancel", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "cancelAll", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getUploads", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getPendingResults", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "acknowledge", returnType: CAPPluginReturnPromise),
    ]

    public override func load() {
        UploadManager.shared.delegate = self
        UploadManager.shared.start()
    }

    /// Call from AppDelegate:
    /// `func application(_:handleEventsForBackgroundURLSession:completionHandler:)`
    /// Returns true when the identifier belongs to this plugin.
    @discardableResult
    public static func handleEventsForBackgroundURLSession(identifier: String, completionHandler: @escaping () -> Void) -> Bool {
        guard identifier == UploadManager.sessionIdentifier else { return false }
        UploadManager.shared.backgroundCompletionHandler = completionHandler
        UploadManager.shared.start()
        return true
    }

    @objc func enqueue(_ call: CAPPluginCall) {
        guard let items = call.options["uploads"] as? [[String: Any]], !items.isEmpty else {
            call.reject("uploads must be a non-empty array")
            return
        }
        do {
            var ids: [String] = []
            for item in items {
                let spec = try UploadSpec(json: item)
                try UploadManager.shared.enqueue(spec)
                ids.append(spec.id)
            }
            call.resolve(["ids": ids])
        } catch {
            call.reject(error.localizedDescription)
        }
    }

    @objc func cancel(_ call: CAPPluginCall) {
        guard let id = call.getString("id"), !id.isEmpty else {
            call.reject("id is required")
            return
        }
        UploadManager.shared.cancel(id: id)
        call.resolve()
    }

    @objc func cancelAll(_ call: CAPPluginCall) {
        UploadManager.shared.cancelAll()
        call.resolve()
    }

    @objc func getUploads(_ call: CAPPluginCall) {
        let uploads: [[String: Any]] = UploadStore.shared.all().map { upload in
            var info: [String: Any] = [
                "id": upload.id,
                "status": upload.status == "cancelled" ? "cancelled" : (upload.attempts > 0 ? "uploading" : "queued"),
                "progress": UploadManager.shared.progressFor(id: upload.id) ?? 0,
                "totalBytes": upload.totalBytes,
            ]
            info["bytesSent"] = Int64(Double(upload.totalBytes) * Double(info["progress"] as? Int ?? 0) / 100)
            return info
        }
        call.resolve(["uploads": uploads])
    }

    @objc func getPendingResults(_ call: CAPPluginCall) {
        let results: [[String: Any]] = UploadStore.shared.pendingResults().map { entry in
            var result: [String: Any] = ["type": entry["type"] ?? "failed"]
            if (entry["type"] as? String) == "completed" {
                result["completed"] = entry["event"]
            } else {
                result["failed"] = entry["event"]
            }
            return result
        }
        call.resolve(["results": results])
    }

    @objc func acknowledge(_ call: CAPPluginCall) {
        let ids = call.getArray("ids", String.self) ?? []
        UploadStore.shared.acknowledge(ids: ids)
        call.resolve()
    }
}

extension BackgroundUploaderPlugin: UploadManagerDelegate {
    func uploadProgress(id: String, bytesSent: Int64, totalBytes: Int64, progress: Int) {
        DispatchQueue.main.async {
            self.notifyListeners("uploadProgress", data: [
                "id": id,
                "progress": progress,
                "bytesSent": Int(bytesSent),
                "totalBytes": Int(totalBytes),
            ])
        }
    }

    func uploadFinished(type: String, id: String, event: [String: Any]) {
        let eventName = type == "completed" ? "uploadCompleted" : "uploadFailed"
        DispatchQueue.main.async {
            // retainUntilConsumed keeps the event until a JS listener is attached.
            self.notifyListeners(eventName, data: event, retainUntilConsumed: true)
        }
    }
}
