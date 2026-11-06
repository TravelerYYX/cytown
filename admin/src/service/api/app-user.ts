import { request } from '../request';
export function fetchDeleteAppUser(id: string) {
  return request<void>({ url: '/app-user/delete', method: 'post', data: { id } });
}
export function fetchAppUserList(params: Api.AppUser.ManageQueryParam) {
  return request<Api.AppUser.Page<Api.AppUser.ListItem>>({ url: '/app-user/list', method: 'post', data: params });
}
/** 新建 APP 用户：后端校验 QQ 号唯一，密码可选（留空=仅验证码登录） */
export function fetchCreateAppUser(params: Api.AppUser.CreateParam) {
  return request<Api.AppUser.ListItem>({ url: '/app-user/create', method: 'post', data: params });
}
export function fetchUpdateAppUserStatus(id: string, status: 0 | 1) {
  return request<void>({ url: '/app-user/updateStatus', method: 'post', data: { id, status } });
}

export function fetchAppUserDetail(id: string) {
  return request<Api.AppUser.Detail>({ url: '/app-user/detail', method: 'get', params: { id } });
}
export function fetchUpdateAppUserAccount(id: string, account: string) {
  return request<void>({ url: '/app-user/updateAccount', method: 'post', data: { id, account } });
}
export function fetchResetAppUserPassword(id: string, password: string) {
  return request<void>({ url: '/app-user/resetPassword', method: 'post', data: { id, password } });
}
