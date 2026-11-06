import { request } from '../request';

export const fetchMineEntries = () => request<Api.P3.MineEntry[]>({ url: '/mine/entry/list' });
export const createMineEntry = (data: Partial<Api.P3.MineEntry>) => request<Api.P3.MineEntry>({ url: '/mine/entry/create', method: 'post', data });
export const updateMineEntry = (data: Partial<Api.P3.MineEntry>) => request<Api.P3.MineEntry>({ url: '/mine/entry/update', method: 'post', data });
export const deleteMineEntry = (id: number) => request<void>({ url: '/mine/entry/delete', method: 'post', data: { id } });
export const sortMineEntries = (ids: number[]) => request<void>({ url: '/mine/entry/sort', method: 'post', data: { ids } });
export const saveMineSvgCode = (svg: string) => request<{ url: string }>({ url: '/mine/entry/icon/code', method: 'post', data: { svg } });
export function uploadMineSvg(file: File) {
  const data = new FormData();
  data.append('file', file);
  return request<{ url: string }>({ url: '/mine/entry/icon/upload', method: 'post', data, headers: { 'Content-Type': 'multipart/form-data' } });
}
