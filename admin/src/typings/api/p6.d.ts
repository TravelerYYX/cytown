declare namespace Api {
  namespace P6 {
    interface SystemAvatar { id: number; url: string; type: 0 | 1; sort: number; status: 0 | 1; createTime: string }
    interface UserTag { id: number; name: string; category: string; source: 0 | 1; status: 0 | 1; sort: number; createTime: string }
  }
}
