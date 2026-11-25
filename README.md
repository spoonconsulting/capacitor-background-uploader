# capacitor-background-uploader

Capacitor 8 plugin for background file uploads to S3, Azure Blob and generic HTTP endpoints.

- **iOS:** `URLSession` background session. Transfers run in the system daemon, survive suspension, and relaunch the app on completion. Files are streamed from disk (`uploadTask(fromFile:)`), never loaded into memory.
- **Android:** WorkManager (scheduling, constraints, persistence) + OkHttp (streamed body, progress). Runs as a foreground data-sync worker with a notification.

## Install

```bash
npm install @spoonconsulting/capacitor-background-uploader   # or publish and install from npm
npx cap sync
```

### iOS: AppDelegate hook (required)

```swift
import CapacitorBackgroundUploader // module name of the Swift package

func application(_ application: UIApplication,
                 handleEventsForBackgroundURLSession identifier: String,
                 completionHandler: @escaping () -> Void) {
    BackgroundUploaderPlugin.handleEventsForBackgroundURLSession(identifier: identifier,
                                                                 completionHandler: completionHandler)
}
```

### Android

Nothing extra. The manifest permissions are merged automatically. Android 13+ shows the notification only after the user grants notifications.

## Usage

```ts
import { BackgroundUploader } from 'capacitor-background-uploader';

// S3 presigned PUT
await BackgroundUploader.enqueue({
  uploads: [{
    id: 'photo-123',
    filePath: nativeFilePath,            // absolute path or file:// URL
    url: presignedPutUrl,
    mode: 'put',
    provider: 's3',
    headers: { 'Content-Type': 'image/jpeg' }, // must match what was signed
  }],
});

// Azure SAS (adds x-ms-blob-type: BlockBlob automatically)
await BackgroundUploader.enqueue({
  uploads: [{ id: 'doc-9', filePath, url: sasUrl, provider: 'azure' }],
});

// S3 POST policy or generic multipart form
await BackgroundUploader.enqueue({
  uploads: [{
    id: 'media-7', filePath, url: postUrl, mode: 'multipart',
    fields: { key: 'uploads/7.jpg', policy: '...', 'x-amz-signature': '...' },
    fileField: 'file',
  }],
});

// Events
await BackgroundUploader.addListener('uploadProgress', (e) => { /* e.progress 0-100 */ });
await BackgroundUploader.addListener('uploadCompleted', (e) => { /* e.statusCode, e.response */ });
await BackgroundUploader.addListener('uploadFailed', (e) => { /* e.error, e.permanent */ });

// On app start: handle results that finished while the WebView was not listening.
const { results } = await BackgroundUploader.getPendingResults();
// ...handle each result (dedupe by id against events you already handled)...
await BackgroundUploader.acknowledge({ ids: results.map((r) => (r.completed ?? r.failed)!.id) });
```

## Behavior

- **Retries:** transient failures (network, timeout, HTTP 408/429/5xx) are retried with backoff up to `maxAttempts` (default 4). HTTP 4xx fails permanently.
- **Restart from zero:** each attempt re-sends the whole file. Resume is not supported (see limits).
- **Progress:** emitted at most every 500 ms per upload, plus 100%.
- **Persistence:** iOS keeps upload records and pending results in UserDefaults. Android keeps state in WorkManager's database. Both survive app kills.
- **Concurrency:** iOS uses `httpMaximumConnectionsPerHost = 2`. Android uses a semaphore of 2.
- **Web:** no background transfer. Methods reject or return empty.

## Known limits and open items

- **No resumable uploads yet.** Large files on unstable networks restart from zero on each retry. The next step is S3 multipart (presigned part URLs) and Azure Block Blob staging, which lets each part retry on its own.
- **Re-enqueue with an active id:** Android replaces the running upload; iOS rejects it. Make both match before release.
- **iOS progress map:** `progressFor` reads a dictionary written on the URLSession delegate queue. Guard it with a lock.
- **Not compiled here:** the Swift files parse, but they have not been type-checked or built against Capacitor. The Java has not been compiled (no Android SDK on the build machine).
- **Placeholders:** `com.example.backgrounduploader`, the podspec homepage/author, and the Capacitor SPM version must be set to your values and verified against Capacitor 8 docs.
- **Verify before release:** the WorkManager execution time limit for foreground workers, `ForegroundInfo` with a service type on your targets, and the `Capacitor` Swift package URL.
