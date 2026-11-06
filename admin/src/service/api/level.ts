import { request } from '../request';

export interface LevelTier { id: number; minLevel: number; maxLevel: number; title: string; fullTitle: string; color: string; description: string; entryBonus: number }
export interface LevelItem { level: number; requiredExp: number; badgeUrl: string }
export interface LevelRule { action: string; name: string; experience: number; dailyLimit: number; enabled: boolean }
export interface LevelConfig { version: number; tiers: LevelTier[]; levels: LevelItem[]; rules: LevelRule[] }
export const fetchLevelConfig = () => request<LevelConfig>({ url: '/level/config' });
export const saveLevelConfig = (data: LevelConfig) => request<LevelConfig>({ url: '/level/config', method: 'post', data });
export function uploadLevelBadge(file: File) {
  const data = new FormData(); data.append('file', file);
  return request<{ url: string }>({ url: '/level/badge', method: 'post', data, headers: { 'Content-Type': 'multipart/form-data' } });
}
