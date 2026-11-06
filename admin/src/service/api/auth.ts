import { request } from '../request';

/**
 * Login
 *
 * @param userName User name
 * @param password Password
 */
export function fetchLogin(userName: string, password: string) {
  return request<Api.Auth.LoginToken>({
    url: '/auth/login',
    method: 'post',
    data: {
      userName,
      password
    }
  });
}

/** Get user info */
export function fetchGetUserInfo() {
  return request<Api.Auth.UserInfo>({ url: '/auth/getUserInfo' });
}

/**
 * Refresh token
 *
 * @param refreshToken Refresh token
 */
export function fetchRefreshToken(refreshToken: string) {
  return request<Api.Auth.LoginToken>({
    url: '/auth/refreshToken',
    method: 'post',
    data: {
      refreshToken
    }
  });
}

/**
 * Update user name
 *
 * @param userName New user name
 */
export function fetchUpdateUserName(userName: string) {
  return request<void>({
    url: '/auth/updateUserName',
    method: 'post',
    data: {
      userName
    }
  });
}

/**
 * Update nickname
 *
 * @param nickname New nickname
 */
export function fetchUpdateNickname(nickname: string) {
  return request<void>({
    url: '/auth/updateNickname',
    method: 'post',
    data: {
      nickname
    }
  });
}

/**
 * Upload avatar
 *
 * @param file Avatar image file
 */
export function fetchUpdateAvatar(file: File) {
  const formData = new FormData();
  formData.append('file', file);
  return request<string>({
    url: '/auth/updateAvatar',
    method: 'post',
    data: formData,
    // 覆盖请求实例默认的 application/json，避免 FormData 被序列化为 JSON；boundary 由 axios/浏览器自动生成
    headers: { 'Content-Type': 'multipart/form-data' }
  });
}

/**
 * Update password
 *
 * @param oldPassword Old password
 * @param newPassword New password
 */
export function fetchUpdatePassword(oldPassword: string, newPassword: string) {
  return request<void>({
    url: '/auth/updatePassword',
    method: 'post',
    data: {
      oldPassword,
      newPassword
    }
  });
}

/**
 * return custom backend error
 *
 * @param code error code
 * @param msg error message
 */
export function fetchCustomBackendError(code: string, msg: string) {
  return request({ url: '/auth/error', params: { code, msg } });
}
