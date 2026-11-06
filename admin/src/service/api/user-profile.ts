import { request } from '../request';

/* 系统头像库管理 */
export const fetchSystemAvatarAdminList = (type?: number) =>
  request<Api.P6.SystemAvatar[]>({ url: '/system-avatar/list', params: { type } });

/** 上传系统头像：原样落盘，动态头像 GIF 保留动画。 */
export const uploadSystemAvatar = (file: File, type: number, sort?: number) => {
  const data = new FormData();
  data.append('file', file);
  data.append('type', String(type));
  if (sort !== undefined) data.append('sort', String(sort));
  return request<Api.P6.SystemAvatar>({
    url: '/system-avatar/upload',
    method: 'post',
    data,
    headers: { 'Content-Type': 'multipart/form-data' }
  });
};

export const updateSystemAvatar = (data: Partial<Api.P6.SystemAvatar> & { id: number }) =>
  request<Api.P6.SystemAvatar>({ url: '/system-avatar/update', method: 'post', data });

export const deleteSystemAvatar = (id: number) => request<void>({ url: '/system-avatar/delete', method: 'post', data: { id } });

/* 用户标签库管理 */
export const fetchUserTagList = () => request<Api.P6.UserTag[]>({ url: '/user-tag/list' });
export const createUserTag = (data: Partial<Api.P6.UserTag>) =>
  request<Api.P6.UserTag>({ url: '/user-tag/create', method: 'post', data });
export const updateUserTag = (data: Partial<Api.P6.UserTag> & { id: number }) =>
  request<Api.P6.UserTag>({ url: '/user-tag/update', method: 'post', data });
export const deleteUserTag = (id: number) => request<void>({ url: '/user-tag/delete', method: 'post', data: { id } });
