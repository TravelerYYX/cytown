import { request } from '../request';

export interface ShopProduct {
  id: string;
  version: number;
  name: string;
  description: string;
  images: string[];
  frameUrl: string;
  priceFen: number;
  discounts: Record<string, number>;
  sortOrder: number;
  enabled: boolean;
  soldCount: number;
}
export interface ShopTier { id: string; name: string }
export interface ShopOrder {
  id: string;
  userId: string;
  productName: string;
  method: string;
  channel: string | null;
  amountFen: number;
  coins: number;
  status: string;
  tradeNo: string | null;
  errorMessage: string;
  createdAt: string;
}

export const loadShopProducts = () => request<{ products: ShopProduct[]; tiers: ShopTier[] }>({ url: '/shop/products' });
export const saveShopProduct = (data: ShopProduct) => request<void>({ url: '/shop/products/save', method: 'post', data });
export const loadShopSettings = () => request<{ h5BaseUrl: string }>({ url: '/shop/settings' });
export const saveShopSettings = (h5BaseUrl: string) =>
  request<{ h5BaseUrl: string }>({ url: '/shop/settings', method: 'post', data: { h5BaseUrl } });
export const loadShopOrders = (page: number, search: string) =>
  request<{ records: ShopOrder[]; total: number }>({ url: '/shop/orders', params: { page, search } });
export const refreshShopOrder = (id: string) => request<ShopOrder>({ url: '/shop/orders/' + id + '/refresh', method: 'post' });
