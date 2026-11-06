import { request } from '../request';
export interface StorageConfig { id: string; provider: 'local' | 'oss' | 'qiniu' | 'ftp'; localDir: string; endpoint: string; region: string; bucket: string; accessKey: string; secretKey: string; domain: string; ftpHost: string; ftpPort: number; ftpUsername: string; ftpPassword: string; ftpRootDir: string }
export interface MediaSettings { images: { coverMaxEdge: number; coverQuality: number; previewMaxEdge: number; previewQuality: number; pngCompression: number; cacheMaxBytes: number }; videos: { segmentSeconds: number; maxFps: number; audioKbps: number; crf: number; concurrency: number; bitrates: Record<string, number> } }
export const getStorageConfig = () => request<StorageConfig>({ url: '/media-config/storage' });
export const saveStorageConfig = (data: StorageConfig) => request<StorageConfig>({ url: '/media-config/storage', method: 'post', data });
// Detection performs several remote transfers and cleanup; the shared 10s limit is too short.
export const testStorageConfig = (data: StorageConfig) => request<void>({ url: '/media-config/storage/test', method: 'post', data, timeout: 300_000, timeoutErrorMessage: '存储连接检测超时，请检查存储网络和访问域名' });
export const getMediaSettings = () => request<MediaSettings>({ url: '/media-config/processing' });
export const saveMediaSettings = (data: MediaSettings) => request<MediaSettings>({ url: '/media-config/processing', method: 'post', data });
