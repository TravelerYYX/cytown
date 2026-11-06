declare namespace Api {
  namespace AppUser {
    interface ManageQueryParam { pageNum: number; pageSize: number; keyword?: string }
    interface CreateParam {
      /** 5-11 位数字 */
      qqNumber: string;
      account?: string;
      /** 选填，默认「町酱+QQ号」 */
      nickname?: string;
      /** 选填初始密码，留空=仅验证码登录 */
      password?: string;
    }
    interface ListItem {
      id: string; publicUid: string | null; qqNumber: string | null; account: string; officialAdminId: string | null; nickname: string; avatar: string; status: 0 | 1;
      lastLoginTime: string | null; createTime: string;
    }
    interface Statistics { postCount: number; followerCount: number; followingCount: number; receivedLikeCount: number; favoriteCount: number }
    interface Detail extends ListItem {
      email: string; signature: string; gender: number; level: number; experience: number; cardBackground: string;
      tags: { tagId: string | null; name: string; custom: boolean }[];
      hasPassword: boolean; updateTime: string | null; statistics: Statistics;
    }
    interface Page<T> { records: T[]; total: number; current: number; size: number; pages: number }
  }
}
