import type { PluginListenerHandle } from '@capacitor/core';

/**
 * How the file is sent.
 * - `put`: raw body sent with HTTP PUT. Used for S3 presigned URLs and Azure SAS URLs.
 * - `multipart`: multipart/form-data POST. Used for S3 POST policies and generic form endpoints.
 */
export type UploadMode = 'put' | 'multipart';

/**
 * Storage provider hints. `azure` adds the `x-ms-blob-type: BlockBlob` header automatically.
 */
export type UploadProvider = 's3' | 'azure' | 'generic';

export interface UploadRequest {
  /** Caller-chosen unique id. Re-enqueuing an id replaces the previous upload with that id. */
  id: string;
  /** Local file path (absolute path or file:// URL). */
  filePath: string;
  /** Presigned PUT URL, Azure SAS URL, or POST endpoint. */
  url: string;
  /** Default: 'put'. */
  mode?: UploadMode;
  /** HTTP method. Default: 'PUT' for mode 'put', 'POST' for mode 'multipart'. */
  method?: 'PUT' | 'POST';
  /** Default: 'generic'. */
  provider?: UploadProvider;
  /** Extra request headers. Must include whatever the presigned URL signed (e.g. Content-Type). */
  headers?: Record<string, string>;
  /** Form fields for mode 'multipart' (e.g. S3 POST policy fields). */
  fields?: Record<string, string>;
  /** Form field name for the file part. Default: 'file'. */
  fileField?: string;
  /** Content-Type of the file. Detected from the extension when omitted. */
  mimeType?: string;
  /** Name sent in the multipart part / used for display. Defaults to the file name. */
  fileName?: string;
  /** Total attempts including the first one. Default: 4. Only transient errors are retried. */
  maxAttempts?: number;
  /** Android foreground notification title. Ignored on iOS. */
  notificationTitle?: string;
}

export interface EnqueueResult {
  ids: string[];
}

export type UploadStatus = 'queued' | 'uploading' | 'completed' | 'failed' | 'cancelled';

export interface UploadInfo {
  id: string;
  status: UploadStatus;
  progress: number; // 0-100
  bytesSent?: number;
  totalBytes?: number;
}

export interface UploadProgressEvent {
  id: string;
  progress: number; // 0-100, emitted at most every 500 ms per upload
  bytesSent: number;
  totalBytes: number;
}

export interface UploadCompletedEvent {
  id: string;
  statusCode: number;
  response: string;
  totalBytes: number;
  durationMs: number;
}

export interface UploadFailedEvent {
  id: string;
  error: string;
  statusCode?: number;
  /** true when the failure is permanent (4xx, cancelled, file missing). */
  permanent: boolean;
  attempts: number;
}

export interface PendingResult {
  type: 'completed' | 'failed';
  completed?: UploadCompletedEvent;
  failed?: UploadFailedEvent;
}

export interface BackgroundUploaderPlugin {
  /** Queue one or more uploads. Returns immediately; work continues in the background. */
  enqueue(options: { uploads: UploadRequest[] }): Promise<EnqueueResult>;

  /** Cancel one upload by id. */
  cancel(options: { id: string }): Promise<void>;

  /** Cancel every pending upload. */
  cancelAll(): Promise<void>;

  /** Current state of uploads known to the plugin. */
  getUploads(): Promise<{ uploads: UploadInfo[] }>;

  /**
   * Results that finished while the WebView was not listening (e.g. app was killed or suspended).
   * Call on app start, then call acknowledge() with the ids you handled.
   */
  getPendingResults(): Promise<{ results: PendingResult[] }>;

  /** Mark results as handled so they are not returned again. */
  acknowledge(options: { ids: string[] }): Promise<void>;

  addListener(
    eventName: 'uploadProgress',
    listenerFunc: (event: UploadProgressEvent) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'uploadCompleted',
    listenerFunc: (event: UploadCompletedEvent) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: 'uploadFailed',
    listenerFunc: (event: UploadFailedEvent) => void,
  ): Promise<PluginListenerHandle>;

  removeAllListeners(): Promise<void>;
}
