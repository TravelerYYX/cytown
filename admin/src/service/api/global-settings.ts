import { request } from '../request';

export interface GlobalSettings { deleteResourceFilesEnabled: boolean }
export function fetchGlobalSettings() {
  return request<GlobalSettings>({ url: '/global-settings' });
}
export function saveGlobalSettings(data: GlobalSettings) {
  return request<GlobalSettings>({ url: '/global-settings', method: 'post', data });
}

export interface LegalLink { linkUrl: string; openMode: 'browser' | 'inapp' }
export interface LegalLinks {
  userAgreement: LegalLink;
  privacyPolicy: LegalLink;
  personalInfoCollection: LegalLink;
}
export function fetchLegalLinks() {
  return request<LegalLinks>({ url: '/global-settings/legal-links' });
}
export function saveLegalLinks(data: LegalLinks) {
  return request<LegalLinks>({ url: '/global-settings/legal-links', method: 'post', data });
}
