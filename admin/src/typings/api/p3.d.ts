declare namespace Api {
  namespace P3 {
    interface HeaderEntry { name: string; iconUrl: string; targetType: 'link'|'app'; linkUrl: string; openMode: 'inapp'|'browser'; showTopbar: boolean; enabled: boolean }
    interface Channel { id: number; name: string; sortOrder: number; enabled: boolean; isDefault: boolean; adminOnly: boolean; showAllPosts: boolean; allowPublish: boolean }
    interface EntryPage { label: string; value: string; hint: string }
    interface Entry { id: number; name: string; iconUrl: string; targetType: 'link'|'app'; linkUrl: string; openMode: 'inapp'|'browser'; showTopbar: boolean; sortOrder: number; enabled: boolean }
    interface MineEntry { id: number; title: string; description: string; iconUrl: string; targetType: 'link'|'app'; linkUrl: string; openMode: 'inapp'|'browser'; showTopbar: boolean; sortOrder: number; enabled: boolean }
    interface NewsConfig { enabled: boolean; interval: number; emptyText: string }
    interface LayoutConfig { defaultFeedLayout: 'single' | 'waterfall' }
    type RecipientPolicy = 'all' | 'users' | 'choose';
    interface Category { id: number; name: string; iconUrl: string; sortOrder: number; enabled: boolean; recipientPolicy: RecipientPolicy; recipientUserIds: (string|number)[] }
    interface Block { type: 'text'|'image'; text?: string; url?: string }
    interface Notice { audienceType: 'all'|'users'; recipientUserIds?: (string|number)[]; pushEnabled: boolean; publicationId?: number; pushTasks?: {status:string;count:number}[]; id: number; categoryId: number; categoryName: string; title: string; status: number; publishTime: string; createTime: string }
    interface NoticeDetail extends Notice { blocks: string; blocksJson: string; postId: number|null }
    interface NoticeQuery { pageNum: number; pageSize: number; categoryId?: number; status?: number }
    interface Page<T> { records: T[]; total: number; current: number; size: number; pages: number }
  }
}
