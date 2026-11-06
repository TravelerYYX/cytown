import { request } from '@/service/request';
export interface ChatRule { blocked: boolean; globalMutedUntil: number; conversationMutedUntil: number }
export interface ChatPerson { id: string; account: string; nickname: string; avatar: string; status: number; deleted: boolean; restriction: ChatRule }
export interface AdminConversation { id: string; a: ChatPerson; b: ChatPerson; lastMessage: string; lastTime: number }
export interface ChatAsset { id: string; kind: string; coverId: string; mime: string; sizeBytes: number }
export interface AdminMessage { id: string; senderId: string; content: string; kind: string; sentAt: number; revoked: boolean; asset: ChatAsset | null; post?: { id:string; title:string; coverUrl:string; authorName:string; video:boolean } | null }
export interface ChatDeleteJob { id: string; conversationId: string; state: 'cleaning' | 'retrying' | 'completed'; remaining: number; errorMessage: string }
export interface ChatHistory { records: AdminMessage[]; older: string; newer: string }
export const chatAdminList = (keyword: string, pageNum: number) => request<{ records: AdminConversation[]; total: number }>({ url: '/chat-admin/conversations', params: { keyword, pageNum, pageSize: 20 } });
export const chatAdminDetail = (id: string) => request<AdminConversation>({ url: `/chat-admin/conversations/${id}` });
export const chatAdminHistory = (id: string, before = '', after = '') => request<ChatHistory>({ url: `/chat-admin/conversations/${id}/messages`, params: { before: before || '0', after: after || '0', limit: 50 } });
export const chatAdminDelete = (id: string, message: string) => request<ChatDeleteJob>({ url: `/chat-admin/conversations/${id}/messages/${message}/delete`, method: 'post' });
export const chatAdminClear = (id: string) => request<ChatDeleteJob>({ url: `/chat-admin/conversations/${id}/clear`, method: 'post' });
export const chatAdminJobs = (id: string) => request<ChatDeleteJob[]>({ url: `/chat-admin/conversations/${id}/jobs` });
export const chatAdminRule = (userId: string, conversationId: string, action: string, minutes?: number) => request<void>({ url: '/chat-admin/restrictions', method: 'post', data: { userId, conversationId, action, minutes } });
export const chatAdminAccountStatus = (userId: string, status: number) => request<void>({ url: '/chat-admin/accounts/status', method: 'post', data: { userId, status } });
