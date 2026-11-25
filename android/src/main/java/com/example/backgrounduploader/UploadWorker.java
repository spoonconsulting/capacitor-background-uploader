package com.example.backgrounduploader;

import android.app.Notification;
import android.content.Context;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationChannelCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.work.Data;
import androidx.work.ForegroundInfo;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.ForwardingSink;
import okio.Okio;

/**
 * Performs one upload attempt. WorkManager owns scheduling, constraints and persistence.
 * Retry policy: transient failures return Result.retry() until maxAttempts; permanent ones fail.
 * A system stop is always treated as retryable, never as a user cancel.
 */
public class UploadWorker extends Worker {
    static final String TAG_UPLOAD = "background-uploader";
    static final String CHANNEL_ID = "background_uploads";

    // Input keys
    static final String KEY_ID = "id";
    static final String KEY_FILE_PATH = "filePath";
    static final String KEY_URL = "url";
    static final String KEY_METHOD = "method";
    static final String KEY_MODE = "mode";
    static final String KEY_HEADERS = "headers";
    static final String KEY_FIELDS = "fields";
    static final String KEY_FILE_FIELD = "fileField";
    static final String KEY_MIME = "mimeType";
    static final String KEY_FILE_NAME = "fileName";
    static final String KEY_MAX_ATTEMPTS = "maxAttempts";
    static final String KEY_TITLE = "notificationTitle";

    // Progress keys (WorkInfo.getProgress)
    static final String KEY_PROGRESS_PERCENT = "progress";
    static final String KEY_PROGRESS_SENT = "bytesSent";
    static final String KEY_PROGRESS_TOTAL = "totalBytes";

    // Output keys (WorkInfo.getOutputData)
    static final String KEY_OUT_STATE = "state"; // completed | failed
    static final String KEY_OUT_STATUS = "statusCode";
    static final String KEY_OUT_RESPONSE = "response";
    static final String KEY_OUT_ERROR = "error";
    static final String KEY_OUT_PERMANENT = "permanent";
    static final String KEY_OUT_ATTEMPTS = "attempts";
    static final String KEY_OUT_TOTAL = "totalBytes";
    static final String KEY_OUT_DURATION = "durationMs";

    private static final long PROGRESS_INTERVAL_MS = 500;
    private static final int MAX_RESPONSE_CHARS = 2048;

    // Shared client. No overall call timeout: large files on slow links must be allowed to finish.
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build();

    // Limit simultaneous transfers so a batch does not saturate the link.
    private static final Semaphore SLOTS = new Semaphore(2, true);

    private volatile Call currentCall;
    private volatile long lastProgressAt = 0;

    public UploadWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    static String tagFor(String id) {
        return "upload:" + id;
    }

    @NonNull
    @Override
    public Result doWork() {
        Data in = getInputData();
        String id = in.getString(KEY_ID);
        if (id == null) {
            return Result.failure(outputFailed("unknown", "id missing", null, true, 0));
        }
        int attempt = getRunAttemptCount() + 1;
        int maxAttempts = in.getInt(KEY_MAX_ATTEMPTS, 4);

        File file = resolveFile(in.getString(KEY_FILE_PATH));
        if (file == null || !file.exists()) {
            return Result.failure(outputFailed(id, "File not found", null, true, attempt));
        }

        setForegroundAsync(foregroundInfo(id, in.getString(KEY_TITLE)));

        try {
            SLOTS.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.retry();
        }

        long started = System.currentTimeMillis();
        try {
            RequestBody body = buildBody(id, file, in);
            Request.Builder requestBuilder = new Request.Builder()
                    .url(in.getString(KEY_URL))
                    .method(in.getString(KEY_METHOD), body);
            applyHeaders(requestBuilder, in.getString(KEY_HEADERS));

            Call call = CLIENT.newCall(requestBuilder.build());
            currentCall = call;
            try (Response response = call.execute()) {
                int code = response.code();
                String text = readPreview(response.body());
                if (response.isSuccessful()) {
                    return Result.success(new Data.Builder()
                            .putString(KEY_ID, id)
                            .putString(KEY_OUT_STATE, "completed")
                            .putInt(KEY_OUT_STATUS, code)
                            .putString(KEY_OUT_RESPONSE, text)
                            .putLong(KEY_OUT_TOTAL, file.length())
                            .putLong(KEY_OUT_DURATION, System.currentTimeMillis() - started)
                            .putInt(KEY_OUT_ATTEMPTS, attempt)
                            .build());
                }
                boolean transientError = isTransientStatus(code);
                if (transientError && attempt < maxAttempts) {
                    return Result.retry();
                }
                return Result.failure(outputFailed(id, "HTTP " + code, code, !transientError, attempt));
            }
        } catch (IOException e) {
            // Includes the stop-triggered cancel from onStopped(). Stops are retryable, never user cancels.
            if (isStopped() || attempt < maxAttempts) {
                return Result.retry();
            }
            return Result.failure(outputFailed(id, String.valueOf(e.getMessage()), null, false, attempt));
        } catch (JSONException | RuntimeException e) {
            return Result.failure(outputFailed(id, "Invalid upload request: " + e.getMessage(), null, true, attempt));
        } finally {
            currentCall = null;
            SLOTS.release();
        }
    }

    @Override
    public void onStopped() {
        Call call = currentCall;
        if (call != null) {
            call.cancel();
        }
        super.onStopped();
    }

    // MARK: - Body and headers

    private RequestBody buildBody(String id, File file, Data in) throws JSONException, IOException {
        String mime = in.getString(KEY_MIME);
        MediaType mediaType = MediaType.parse(mime != null ? mime : "application/octet-stream");
        if (mediaType == null) {
            mediaType = MediaType.parse("application/octet-stream");
        }
        RequestBody fileBody = new ProgressRequestBody(RequestBody.create(file, mediaType));

        if ("multipart".equals(in.getString(KEY_MODE))) {
            MultipartBody.Builder builder = new MultipartBody.Builder().setType(MultipartBody.FORM);
            JSONObject fields = new JSONObject(in.getString(KEY_FIELDS) != null ? in.getString(KEY_FIELDS) : "{}");
            Iterator<String> keys = fields.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                builder.addFormDataPart(key, fields.getString(key));
            }
            String fileField = in.getString(KEY_FILE_FIELD) != null ? in.getString(KEY_FILE_FIELD) : "file";
            String fileName = in.getString(KEY_FILE_NAME) != null ? in.getString(KEY_FILE_NAME) : file.getName();
            // The file part is added last, which S3 POST policies require.
            builder.addFormDataPart(fileField, fileName, fileBody);
            return builder.build();
        }
        return fileBody;
    }

    private static void applyHeaders(Request.Builder builder, String headersJson) throws JSONException {
        if (headersJson == null) return;
        JSONObject headers = new JSONObject(headersJson);
        Iterator<String> keys = headers.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            // Body sets Content-Type and Content-Length itself; setting them twice breaks signed requests.
            String lower = key.toLowerCase();
            if (lower.equals("content-type") || lower.equals("content-length")) continue;
            builder.header(key, headers.getString(key));
        }
    }

    private static String readPreview(ResponseBody body) throws IOException {
        if (body == null) return "";
        BufferedSource source = body.source();
        source.request(MAX_RESPONSE_CHARS);
        long size = Math.min(source.getBuffer().size(), MAX_RESPONSE_CHARS);
        return source.getBuffer().readUtf8(size);
    }

    static boolean isTransientStatus(int code) {
        return code == 408 || code == 429 || code >= 500;
    }

    private static File resolveFile(String path) {
        if (path == null) return null;
        if (path.startsWith("file://")) {
            String parsed = Uri.parse(path).getPath();
            return parsed == null ? null : new File(parsed);
        }
        return new File(path);
    }

    // MARK: - Progress

    private void reportProgress(long sent, long total) {
        long now = System.currentTimeMillis();
        if (sent < total && now - lastProgressAt < PROGRESS_INTERVAL_MS) return;
        lastProgressAt = now;
        int percent = total > 0 ? (int) (sent * 100 / total) : 0;
        // Fire-and-forget: WorkManager persists the latest value for the app to observe.
        setProgressAsync(new Data.Builder()
                .putInt(KEY_PROGRESS_PERCENT, percent)
                .putLong(KEY_PROGRESS_SENT, sent)
                .putLong(KEY_PROGRESS_TOTAL, total)
                .build());
    }

    /** Counts bytes as they are written to the socket, so progress reflects the real network transfer. */
    private class ProgressRequestBody extends RequestBody {
        private final RequestBody delegate;

        ProgressRequestBody(RequestBody delegate) {
            this.delegate = delegate;
        }

        @Override
        public MediaType contentType() {
            return delegate.contentType();
        }

        @Override
        public long contentLength() throws IOException {
            return delegate.contentLength();
        }

        @Override
        public void writeTo(BufferedSink sink) throws IOException {
            final long total = contentLength();
            BufferedSink counting = Okio.buffer(new ForwardingSink(sink) {
                private long sent = 0;

                @Override
                public void write(Buffer source, long byteCount) throws IOException {
                    super.write(source, byteCount);
                    sent += byteCount;
                    reportProgress(sent, total);
                }
            });
            delegate.writeTo(counting);
            counting.flush();
        }
    }

    // MARK: - Output helpers

    private static Data outputFailed(String id, String error, Integer statusCode, boolean permanent, int attempts) {
        Data.Builder builder = new Data.Builder()
                .putString(KEY_ID, id)
                .putString(KEY_OUT_STATE, "failed")
                .putString(KEY_OUT_ERROR, error)
                .putBoolean(KEY_OUT_PERMANENT, permanent)
                .putInt(KEY_OUT_ATTEMPTS, attempts);
        if (statusCode != null) {
            builder.putInt(KEY_OUT_STATUS, statusCode);
        }
        return builder.build();
    }

    // MARK: - Foreground notification (keeps long transfers alive on Android 12+ and below)

    private ForegroundInfo foregroundInfo(String id, String title) {
        Context context = getApplicationContext();
        NotificationManagerCompat manager = NotificationManagerCompat.from(context);
        manager.createNotificationChannel(new NotificationChannelCompat.Builder(CHANNEL_ID,
                NotificationManagerCompat.IMPORTANCE_LOW)
                .setName("Uploads")
                .build());

        Notification notification = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(title != null ? title : "Uploading files")
                .setContentText(id)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                : 0;
        return new ForegroundInfo(id.hashCode(), notification, type);
    }
}
