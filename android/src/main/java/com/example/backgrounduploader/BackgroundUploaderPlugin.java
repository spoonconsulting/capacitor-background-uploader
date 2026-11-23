package com.example.backgrounduploader;

import android.content.SharedPreferences;
import android.os.Build;
import android.webkit.MimeTypeMap;

import androidx.lifecycle.Observer;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.OutOfQuotaPolicy;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@CapacitorPlugin(name = "BackgroundUploader")
public class BackgroundUploaderPlugin extends Plugin {
    private static final String PREFS = "background_uploader";
    private static final String KEY_ACKED = "acked";

    // Blocking WorkManager queries run here, never on the main thread.
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    // Finished states already sent to JS this process (key: "id:state").
    private final Set<String> emittedFinal = ConcurrentHashMap.newKeySet();
    private final Map<String, Integer> lastProgress = new ConcurrentHashMap<>();

    private final Observer<List<WorkInfo>> observer = this::onWorkInfos;

    @Override
    public void load() {
        // load() runs on the main thread, which observeForever requires.
        WorkManager.getInstance(getContext())
                .getWorkInfosByTagLiveData(UploadWorker.TAG_UPLOAD)
                .observeForever(observer);
    }

    @Override
    protected void handleOnDestroy() {
        WorkManager.getInstance(getContext())
                .getWorkInfosByTagLiveData(UploadWorker.TAG_UPLOAD)
                .removeObserver(observer);
        io.shutdown();
        super.handleOnDestroy();
    }

    // MARK: - Methods

    @PluginMethod
    public void enqueue(PluginCall call) {
        JSArray uploads = call.getArray("uploads");
        if (uploads == null || uploads.length() == 0) {
            call.reject("uploads must be a non-empty array");
            return;
        }
        try {
            WorkManager workManager = WorkManager.getInstance(getContext());
            JSONArray ids = new JSONArray();
            for (int i = 0; i < uploads.length(); i++) {
                JSONObject item = uploads.getJSONObject(i);
                Data input = buildInput(item);
                String id = input.getString(UploadWorker.KEY_ID);

                OneTimeWorkRequest.Builder builder = new OneTimeWorkRequest.Builder(UploadWorker.class)
                        .setInputData(input)
                        .setConstraints(new Constraints.Builder()
                                .setRequiredNetworkType(NetworkType.CONNECTED)
                                .build())
                        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                        .addTag(UploadWorker.TAG_UPLOAD)
                        .addTag(UploadWorker.tagFor(id))
                        .keepResultsForAtLeast(7, TimeUnit.DAYS);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // Start promptly; falls back to normal scheduling when the expedited quota is used up.
                    builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST);
                }

                unacknowledge(id);
                // Same id replaces the previous upload (cancels it if still running).
                workManager.enqueueUniqueWork(id, ExistingWorkPolicy.REPLACE, builder.build());
                ids.put(id);
            }
            JSObject result = new JSObject();
            result.put("ids", ids);
            call.resolve(result);
        } catch (JSONException | IllegalArgumentException | IllegalStateException e) {
            call.reject("Invalid upload request: " + e.getMessage());
        }
    }

    @PluginMethod
    public void cancel(PluginCall call) {
        String id = call.getString("id");
        if (id == null || id.isEmpty()) {
            call.reject("id is required");
            return;
        }
        WorkManager.getInstance(getContext()).cancelUniqueWork(id);
        call.resolve();
    }

    @PluginMethod
    public void cancelAll(PluginCall call) {
        WorkManager.getInstance(getContext()).cancelAllWorkByTag(UploadWorker.TAG_UPLOAD);
        call.resolve();
    }

    @PluginMethod
    public void getUploads(PluginCall call) {
        io.execute(() -> {
            try {
                List<WorkInfo> infos = WorkManager.getInstance(getContext())
                        .getWorkInfosByTag(UploadWorker.TAG_UPLOAD).get();
                JSArray uploads = new JSArray();
                for (WorkInfo info : infos) {
                    String id = idFromTags(info.getTags());
                    if (id == null || isAcked(id)) continue;
                    JSObject item = new JSObject();
                    item.put("id", id);
                    item.put("status", statusFor(info.getState()));
                    int progress = info.getProgress().getInt(UploadWorker.KEY_PROGRESS_PERCENT, 0);
                    item.put("progress", info.getState() == WorkInfo.State.SUCCEEDED ? 100 : progress);
                    uploads.put(item);
                }
                JSObject result = new JSObject();
                result.put("uploads", uploads);
                call.resolve(result);
            } catch (InterruptedException | ExecutionException e) {
                call.reject("getUploads failed: " + e.getMessage());
            }
        });
    }

    @PluginMethod
    public void getPendingResults(PluginCall call) {
        io.execute(() -> {
            try {
                List<WorkInfo> infos = WorkManager.getInstance(getContext())
                        .getWorkInfosByTag(UploadWorker.TAG_UPLOAD).get();
                JSArray results = new JSArray();
                for (WorkInfo info : infos) {
                    String id = idFromTags(info.getTags());
                    if (id == null || isAcked(id)) continue;
                    JSObject result = eventFor(info);
                    if (result != null) {
                        results.put(result);
                    }
                }
                JSObject response = new JSObject();
                response.put("results", results);
                call.resolve(response);
            } catch (InterruptedException | ExecutionException e) {
                call.reject("getPendingResults failed: " + e.getMessage());
            }
        });
    }

    @PluginMethod
    public void acknowledge(PluginCall call) {
        JSArray ids = call.getArray("ids");
        if (ids == null) {
            call.reject("ids is required");
            return;
        }
        io.execute(() -> {
            try {
                for (int i = 0; i < ids.length(); i++) {
                    acknowledgeId(ids.getString(i));
                }
                call.resolve();
            } catch (JSONException e) {
                call.reject("acknowledge failed: " + e.getMessage());
            }
        });
    }

    // MARK: - Work observation (progress and finished events)

    private void onWorkInfos(List<WorkInfo> infos) {
        for (WorkInfo info : infos) {
            String id = idFromTags(info.getTags());
            if (id == null) continue;

            if (info.getState() == WorkInfo.State.RUNNING) {
                int progress = info.getProgress().getInt(UploadWorker.KEY_PROGRESS_PERCENT, -1);
                Integer previous = lastProgress.get(id);
                if (progress >= 0 && (previous == null || previous != progress)) {
                    lastProgress.put(id, progress);
                    JSObject event = new JSObject();
                    event.put("id", id);
                    event.put("progress", progress);
                    event.put("bytesSent", info.getProgress().getLong(UploadWorker.KEY_PROGRESS_SENT, 0));
                    event.put("totalBytes", info.getProgress().getLong(UploadWorker.KEY_PROGRESS_TOTAL, 0));
                    notifyListeners("uploadProgress", event);
                }
                continue;
            }

            if (!info.getState().isFinished() || isAcked(id)) continue;
            String key = id + ":" + info.getState();
            if (!emittedFinal.add(key)) continue;

            JSObject wrapper = eventFor(info);
            if (wrapper == null) continue;
            String type = wrapper.getString("type");
            JSObject payload = wrapper.getJSObject(type);
            String eventName = "completed".equals(type) ? "uploadCompleted" : "uploadFailed";
            // retainUntilConsumed keeps the event until a JS listener is attached.
            notifyListeners(eventName, payload, true);
        }
    }

    /** Builds {type, completed|failed} for a finished WorkInfo, or null if not finished. */
    private JSObject eventFor(WorkInfo info) {
        String id = idFromTags(info.getTags());
        if (id == null) return null;
        JSObject payload = new JSObject();
        JSObject wrapper = new JSObject();
        try {
            if (info.getState() == WorkInfo.State.SUCCEEDED) {
                Data out = info.getOutputData();
                String state = out.getString(UploadWorker.KEY_OUT_STATE);
                if ("completed".equals(state)) {
                    payload.put("id", id);
                    payload.put("statusCode", out.getInt(UploadWorker.KEY_OUT_STATUS, 200));
                    payload.put("response", out.getString(UploadWorker.KEY_OUT_RESPONSE) != null
                            ? out.getString(UploadWorker.KEY_OUT_RESPONSE) : "");
                    payload.put("totalBytes", out.getLong(UploadWorker.KEY_OUT_TOTAL, 0));
                    payload.put("durationMs", out.getLong(UploadWorker.KEY_OUT_DURATION, 0));
                    wrapper.put("type", "completed");
                    wrapper.put("completed", payload);
                    return wrapper;
                }
                return failedEvent(id, info.getOutputData(), wrapper, payload);
            }
            if (info.getState() == WorkInfo.State.FAILED) {
                return failedEvent(id, info.getOutputData(), wrapper, payload);
            }
            if (info.getState() == WorkInfo.State.CANCELLED) {
                payload.put("id", id);
                payload.put("error", "cancelled");
                payload.put("permanent", true);
                payload.put("attempts", 0);
                wrapper.put("type", "failed");
                wrapper.put("failed", payload);
                return wrapper;
            }
        } catch (JSONException e) {
            return null;
        }
        return null;
    }

    private static JSObject failedEvent(String id, Data out, JSObject wrapper, JSObject payload) throws JSONException {
        payload.put("id", id);
        payload.put("error", out.getString(UploadWorker.KEY_OUT_ERROR) != null
                ? out.getString(UploadWorker.KEY_OUT_ERROR) : "failed");
        if (out.getKeyValueMap().containsKey(UploadWorker.KEY_OUT_STATUS)) {
            payload.put("statusCode", out.getInt(UploadWorker.KEY_OUT_STATUS, 0));
        }
        payload.put("permanent", out.getBoolean(UploadWorker.KEY_OUT_PERMANENT, true));
        payload.put("attempts", out.getInt(UploadWorker.KEY_OUT_ATTEMPTS, 0));
        wrapper.put("type", "failed");
        wrapper.put("failed", payload);
        return wrapper;
    }

    // MARK: - Input and bookkeeping

    private Data buildInput(JSONObject item) throws JSONException {
        String id = requireString(item, "id");
        String filePath = requireString(item, "filePath");
        String url = requireString(item, "url");
        String mode = item.optString("mode", "put");
        if (!mode.equals("put") && !mode.equals("multipart")) {
            throw new JSONException("mode must be put or multipart");
        }
        String method = item.optString("method",
                mode.equals("multipart") ? "POST" : "PUT").toUpperCase(Locale.ROOT);
        String provider = item.optString("provider", "generic");
        String fileName = item.optString("fileName", new File(filePath.replaceFirst("^file://", "")).getName());
        String mime = item.has("mimeType") ? item.getString("mimeType") : detectMime(fileName);

        JSONObject headers = item.optJSONObject("headers");
        headers = headers != null ? new JSONObject(headers.toString()) : new JSONObject();
        if (provider.equals("azure") && !hasHeader(headers, "x-ms-blob-type")) {
            headers.put("x-ms-blob-type", "BlockBlob");
        }

        return new Data.Builder()
                .putString(UploadWorker.KEY_ID, id)
                .putString(UploadWorker.KEY_FILE_PATH, filePath)
                .putString(UploadWorker.KEY_URL, url)
                .putString(UploadWorker.KEY_METHOD, method)
                .putString(UploadWorker.KEY_MODE, mode)
                .putString(UploadWorker.KEY_HEADERS, headers.toString())
                .putString(UploadWorker.KEY_FIELDS, item.optJSONObject("fields") != null
                        ? item.optJSONObject("fields").toString() : "{}")
                .putString(UploadWorker.KEY_FILE_FIELD, item.optString("fileField", "file"))
                .putString(UploadWorker.KEY_MIME, mime)
                .putString(UploadWorker.KEY_FILE_NAME, fileName)
                .putInt(UploadWorker.KEY_MAX_ATTEMPTS, Math.max(1, item.optInt("maxAttempts", 4)))
                .putString(UploadWorker.KEY_TITLE, item.optString("notificationTitle", "Uploading files"))
                .build();
    }

    private static String requireString(JSONObject item, String key) throws JSONException {
        String value = item.optString(key, "");
        if (value.isEmpty()) {
            throw new JSONException(key + " is required");
        }
        return value;
    }

    private static boolean hasHeader(JSONObject headers, String name) {
        Iterator<String> keys = headers.keys();
        while (keys.hasNext()) {
            if (keys.next().equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    private static String detectMime(String fileName) {
        String ext = MimeTypeMap.getFileExtensionFromUrl(fileName);
        String mime = ext == null || ext.isEmpty() ? null : MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return mime != null ? mime : "application/octet-stream";
    }

    private static String idFromTags(Set<String> tags) {
        for (String tag : tags) {
            if (tag.startsWith("upload:")) return tag.substring("upload:".length());
        }
        return null;
    }

    private static String statusFor(WorkInfo.State state) {
        switch (state) {
            case RUNNING: return "uploading";
            case SUCCEEDED: return "completed";
            case FAILED: return "failed";
            case CANCELLED: return "cancelled";
            default: return "queued";
        }
    }

    private SharedPreferences prefs() {
        return getContext().getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE);
    }

    private boolean isAcked(String id) {
        return prefs().getStringSet(KEY_ACKED, new HashSet<>()).contains(id);
    }

    private synchronized void acknowledgeId(String id) {
        Set<String> acked = new HashSet<>(prefs().getStringSet(KEY_ACKED, new HashSet<>()));
        acked.add(id);
        prefs().edit().putStringSet(KEY_ACKED, acked).apply();
    }

    private synchronized void unacknowledge(String id) {
        Set<String> acked = new HashSet<>(prefs().getStringSet(KEY_ACKED, new HashSet<>()));
        if (acked.remove(id)) {
            prefs().edit().putStringSet(KEY_ACKED, acked).apply();
        }
        emittedFinal.removeIf(key -> key.startsWith(id + ":"));
        lastProgress.remove(id);
    }
}
