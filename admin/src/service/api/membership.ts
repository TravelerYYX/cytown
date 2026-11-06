import { request } from '../request';
export interface MembershipPlan {
  id: string;
  name: string;
  description: string;
  priceFen: number;
  experience: number;
  coins: number;
  badgeUrl: string;
  frameUrl: string;
  selling: boolean;
  archived: boolean;
  benefits: string[];
  deleted?: boolean;
}
export interface MembershipConfig {
  version: number;
  selling: boolean;
  notice: string;
  contact: string;
  agreement: string;
  plans: MembershipPlan[];
}
export interface PaymentProfile {
  id: string;
  channel: string;
  environment: string;
  appId: string;
  sellerId: string;
  mode: string;
  privateKey: string;
  publicKey: string;
  appCert: string;
  alipayCert: string;
  rootCert: string;
  notifyUrl: string;
  returnUrl: string;
}
export interface MembershipOrder {
  id: string;
  userId: string;
  planId: string;
  plan: MembershipPlan;
  channel: string;
  amountFen: number;
  status: string;
  tradeNo: string;
  errorMessage: string;
  createdAt: string;
  expiresAt: string;
  paidAt: string;
  grantStart: string;
  grantEnd: string;
}
export const loadMembership = () => request<MembershipConfig>({ url: '/membership/config' });
export const saveMembership = (data: MembershipConfig) =>
  request<MembershipConfig>({ url: '/membership/config', method: 'post', data });
export const loadPaymentProfiles = () => request<PaymentProfile[]>({ url: '/membership/payments' });
export const savePaymentProfile = (data: PaymentProfile) =>
  request<PaymentProfile>({ url: '/membership/payments', method: 'post', data });
export const loadMembershipOrders = (params: { pageNum: number; pageSize: number; status?: string; search?: string }) =>
  request<{ records: MembershipOrder[]; total: number }>({ url: '/membership/orders', params });
export const refreshMembershipOrder = (id: string) =>
  request<MembershipOrder>({ url: '/membership/orders/' + id + '/refresh', method: 'post' });
