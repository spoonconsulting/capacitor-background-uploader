import { registerPlugin } from '@capacitor/core';

import type { BackgroundUploaderPlugin } from './definitions';

export * from './definitions';

export const BackgroundUploader = registerPlugin<BackgroundUploaderPlugin>('BackgroundUploader', {
  // Web has no background transfer. Uploads are not available in the browser build.
  web: () => import('./web').then((m) => new m.BackgroundUploaderWeb()),
});
