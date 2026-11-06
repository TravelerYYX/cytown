import { request } from '../request';
export function fetchPostList(params: Api.Post.ManageQueryParam) {
  return request<Api.Post.Page<Api.Post.PostList>>({ url: '/post/list', method: 'post', data: params });
}
export function fetchPostDetail(id: string) {
  return request<Api.Post.PostDetail>({ url: `/post/${encodeURIComponent(id)}` });
}
export function fetchPostComments(id:string,params:Record<string,unknown>){return request<Api.Post.Page<Api.Post.CommentItem>>({url:`/post/${encodeURIComponent(id)}/comments`,params});}
export function fetchPostReplies(id:string,rootId:string,params:Record<string,unknown>){return request<Api.Post.Page<Api.Post.CommentItem>>({url:`/post/${encodeURIComponent(id)}/comments/${encodeURIComponent(rootId)}/replies`,params});}
export function fetchUpdatePostStatus(id: string, status: number) {
  return request<void>({ url: '/post/updateStatus', method: 'post', data: { id, status } });
}
export function fetchUpdatePostHomeNews(id: string, homeNewsEnabled: boolean) {
  return request<void>({ url: '/post/updateHomeNews', method: 'post', data: { id, homeNewsEnabled } });
}
export const fetchRecommendPost=(id:string,recommended:boolean)=>request<void>({url:'/post/recommendation',method:'post',data:{id,recommended}});
export function fetchDeletePost(id: string) {
  return request<void>({ url: '/post/delete', method: 'post', data: { id } });
}
