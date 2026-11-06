declare namespace Api {
  namespace Post {
    interface ManageQueryParam { pageNum: number; pageSize: number; status?: number; keyword?: string }
    interface MediaItem { id:number;assetId?:number;type: 'image' | 'video'; layout: 'square' | 'portrait' | 'wide'; aspectRatio: number;width?:number;height?:number;durationMs?:number;duration?: string; url: string;coverUrl?:string }
    interface PostList {
      id: string; authorId: string | null; authorPublicUid?:string;authorNickname: string; authorLevel: number;mode?:'image'|'video'|'post';channelId?:number;channelName?:string;
      title: string; content: string; date: string; createTime: string; status: number; homeNewsEnabled: boolean; checkinRecommended: boolean;
      media: MediaItem[]; tags: string[]; likeCount: number; commentCount: number;contentBlocks?:unknown;allowDownload?:boolean;downloadWatermark?:boolean;original?:boolean;
    }
    interface PostDetail extends PostList { authorBio: string; favoriteCount: number; shareCount: number; collection: string; permission: string;poll?:unknown }
    interface CommentItem {
      id: string; postId: string; parentId: string | null; authorNickname: string; authorLevel: number;
      region: string; content: string; contentType: 'text' | 'sticker'; stickerUrl: string; pinned: boolean;
      likeCount: number; createTime: string; replyCount: number; replies: CommentItem[];
    }
    interface Page<T> { records: T[]; total: number; current: number; size: number; pages: number }
  }
}
