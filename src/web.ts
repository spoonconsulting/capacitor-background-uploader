import { WebPlugin } from '@capacitor/core';

import type {
  BackgroundUploaderPlugin,
  EnqueueResult,
  PendingResult,
  UploadInfo,
  UploadRequest,
} from './definitions';

export class BackgroundUploaderWeb extends WebPlugin implements BackgroundUploaderPlugin {
  async enqueue(_options: { uploads: UploadRequest[] }): Promise<EnqueueResult> {
    throw this.unavailable('BackgroundUploader is only available on iOS and Android.');
  }
  async cancel(_options: { id: string }): Promise<void> {
    throw this.unavailable('BackgroundUploader is only available on iOS and Android.');
  }
  async cancelAll(): Promise<void> {
    throw this.unavailable('BackgroundUploader is only available on iOS and Android.');
  }
  async getUploads(): Promise<{ uploads: UploadInfo[] }> {
    return { uploads: [] };
  }
  async getPendingResults(): Promise<{ results: PendingResult[] }> {
    return { results: [] };
  }
  async acknowledge(_options: { ids: string[] }): Promise<void> {}
}
