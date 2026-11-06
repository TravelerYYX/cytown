import { request } from '../request';
export interface CheckinAsset {url:string;originalBytes:number;processedBytes:number}
export interface CheckinFrame {id:string;name:string;image:CheckinAsset}
export interface CheckinDay {day:number;coins:number;experience:number;frameId:string;frameDays:number;primary:'coins'|'experience'|'frame'}
export interface CheckinConfig {version:number;makeupPrice:number;coinIcon:CheckinAsset;experienceIcon:CheckinAsset;frames:CheckinFrame[];days:CheckinDay[]}
export const fetchCheckinConfig=()=>request<CheckinConfig>({url:'/checkin/config'});
export const saveCheckinConfig=(data:CheckinConfig)=>request<CheckinConfig>({url:'/checkin/config',method:'post',data});

export interface CoinPunchConfig { points: number; coins: number }
export const fetchCoinPunchConfig = () => request<CoinPunchConfig>({ url: '/checkin/coin-punch-config' });
export const saveCoinPunchConfig = (data: CoinPunchConfig) => request<CoinPunchConfig>({ url: '/checkin/coin-punch-config', method: 'post', data });
