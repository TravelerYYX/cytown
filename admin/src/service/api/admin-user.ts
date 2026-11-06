import { request } from '../request';

export function fetchOfficialAccountDelete(id: string) {
  return request<void>({ url: '/admin-user/delete', method: 'post', data: { id } });
}

/** 创建官方账号：填写完整自定义账号 */
export function fetchOfficialAccountCreate(account: string, password: string, nickname?: string) {
  return request<Api.AdminUser.ListItem>({
    url: '/admin-user/create',
    method: 'post',
    data: { account, password, nickname: nickname || '' }
  });
}

/** 官方账号分页列表 */
export function fetchOfficialAccountList(params: Api.AdminUser.QueryParam) {
  return request<Api.AdminUser.Page<Api.AdminUser.ListItem>>({ url: '/admin-user/list', method: 'post', data: params });
}

/** 重置官方账号密码：成功后该账号所有旧登录失效 */
export function fetchOfficialAccountResetPassword(id: string, password: string) {
  return request<void>({ url: '/admin-user/resetPassword', method: 'post', data: { id, password } });
}

/** 启用/禁用官方账号：禁用后该账号立即下线且无法登录 */
export function fetchOfficialAccountUpdateStatus(id: string, status: 0 | 1) {
  return request<void>({ url: '/admin-user/updateStatus', method: 'post', data: { id, status } });
}

export function fetchOfficialAccountDetail(id: string) {
  return request<Api.AdminUser.Detail>({ url: '/admin-user/detail', method: 'get', params: { id } });
}
export function fetchOfficialAccountUpdateAccount(id: string, account: string) {
  return request<void>({ url: '/admin-user/updateAccount', method: 'post', data: { id, account } });
}
