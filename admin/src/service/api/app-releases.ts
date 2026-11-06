import { request } from '../request';
export interface AppRelease {
  id: number; versionName: string; versionCode: number; notes: string; forceUpdate: boolean;
  status: 'draft' | 'published' | 'withdrawn'; current: boolean; packageUrl: string | null;
  packageName: string | null; apkVersionCode: number | null; apkVersionName: string | null;
  sizeBytes: number; sha256: string | null; createdAt: string; publishedAt: string | null;
}
export type ReleaseDraft = Pick<AppRelease, 'versionName' | 'versionCode' | 'notes' | 'forceUpdate'>;
export const fetchReleases = () => request<AppRelease[]>({ url: '/app-releases' });
export const saveRelease = (draft: ReleaseDraft, id?: number) => request<AppRelease>({ url: id ? `/app-releases/${id}` : '/app-releases', method: id ? 'put' : 'post', data: draft });
export const publishRelease = (id: number) => request<AppRelease>({ url: `/app-releases/${id}/publish`, method: 'post' });
export const withdrawRelease = (id: number) => request<AppRelease>({ url: `/app-releases/${id}/withdraw`, method: 'post' });
export const deleteRelease = (id: number) => request<void>({ url: `/app-releases/${id}`, method: 'delete' });
export const forceRelease = (id: number, forceUpdate: boolean) => request<AppRelease>({ url: `/app-releases/${id}/force`, method: 'patch', data: { forceUpdate } });
export function uploadRelease(id: number, file: File, progress: (percent: number) => void) {
  const data = new FormData(); data.append('file', file);
  return request<AppRelease>({ url: `/app-releases/${id}/package`, method: 'post', data, headers: { 'Content-Type': 'multipart/form-data' }, timeout: 1_800_000,
    onUploadProgress: e => { if (e.total) progress(Math.min(100, e.loaded / e.total * 100)); } });
}
