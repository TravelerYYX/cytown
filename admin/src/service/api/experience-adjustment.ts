import { request } from '../request';

export interface ExperienceAdjustmentResult {
  id: string; delta: number; beforeExperience: number; afterExperience: number; beforeLevel: number; afterLevel: number;
}
export interface ExperienceAdjustmentRecord extends ExperienceAdjustmentResult {
  operatorId: string; operatorName: string | null; reason: string; createTime: string;
}
export interface ExperienceAdjustmentInput { id: string; delta: number; reason: string; requestId: string }
export function adjustExperience(kind: 'app' | 'official', data: ExperienceAdjustmentInput) {
  return request<ExperienceAdjustmentResult>({ url: `/${kind === 'app' ? 'app-user' : 'admin-user'}/adjustExperience`, method: 'post', data });
}
export function fetchExperienceHistory(kind: 'app' | 'official', id: string, pageNum: number) {
  return request<Api.AppUser.Page<ExperienceAdjustmentRecord>>({
    url: `/${kind === 'app' ? 'app-user' : 'admin-user'}/experienceHistory`, params: { id, pageNum, pageSize: 20 }
  });
}
