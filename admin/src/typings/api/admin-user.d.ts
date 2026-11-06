declare namespace Api {
  /**
   * namespace AdminUser
   *
   * backend api module: "admin-user"（官方只读账号管理，仅超管可用）
   */
  namespace AdminUser {
    interface QueryParam {
      pageNum: number;
      pageSize: number;
      keyword?: string;
      /** 状态筛选：不传=全部 */
      status?: 0 | 1;
    }
    interface ListItem {
      id: string;
      /** 完整自定义登录账号 */
      username: string;
      nickname: string;
      status: 0 | 1;
      userType: 0 | 1;
      createdBy: string | null;
      createTime: string | null;
    }
    interface Detail extends ListItem {
      avatar: string; hasPassword: boolean; createdByName: string | null;
      updateTime: string | null; community: Api.AppUser.Detail | null; statistics: Api.AppUser.Statistics;
    }
    interface Page<T> { records: T[]; total: number; current: number; size: number; pages: number }
  }
}
