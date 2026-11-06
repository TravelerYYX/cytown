declare namespace Api {
  /**
   * namespace Auth
   *
   * backend api module: "auth"
   */
  namespace Auth {
    interface LoginToken {
      token: string;
      refreshToken: string;
    }

    interface UserInfo {
      userId: string;
      userName: string;
      nickName: string;
      avatar: string;
      roles: string[];
      buttons: string[];
      /** 账号类型：0超级管理员 1官方只读账号 */
      userType: number;
    }
  }
}
