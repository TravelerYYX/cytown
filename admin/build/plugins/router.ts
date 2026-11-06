import type { RouteMeta } from 'vue-router';
import ElegantVueRouter from '@elegant-router/vue/vite';
import type { RouteKey } from '@elegant-router/types';

export function setupElegantRouter() {
  return ElegantVueRouter({
    layouts: {
      base: 'src/layouts/base-layout/index.vue',
      blank: 'src/layouts/blank-layout/index.vue'
    },
    routePathTransformer(routeName, routePath) {
      const key = routeName as RouteKey;

      if (key === 'login') {
        const modules: UnionKey.LoginModule[] = ['pwd-login', 'code-login', 'register', 'reset-pwd', 'bind-wechat'];

        const moduleReg = modules.join('|');

        return `/login/:module(${moduleReg})?`;
      }

      return routePath;
    },
    onRouteMetaGen(routeName) {
      const key = routeName as RouteKey;

      const constantRoutes: RouteKey[] = ['login', '403', '404', '500'];

      const meta: Partial<RouteMeta> = {
        title: key,
        i18nKey: `route.${key}` as App.I18n.I18nKey
      };

      if (constantRoutes.includes(key)) {
        meta.constant = true;
      }

      const routeMetaMap: Partial<Record<RouteKey, Partial<RouteMeta>>> = {
        'membership-plans': {icon:'mdi:card-account-details',order:45,roles:['R_SUPER']},
        'membership-payments': {icon:'mdi:credit-card-settings',order:46,roles:['R_SUPER']},
        'membership-orders': {icon:'mdi:receipt-text',order:47,roles:['R_SUPER']},
        'shop-manage': {icon:'mdi:storefront-outline',order:48,roles:['R_SUPER']},
        'chat-manage': { icon: 'mdi:message-text-outline', order: 33, roles: ['R_SUPER'] },
        'chat-settings': { icon: 'mdi:message-cog-outline', order: 34 },
        home: { icon: 'mdi:monitor-dashboard', order: 10 },
        'app-releases': { icon: 'mdi:cellphone-arrow-down', order: 44 },
        'app-user-manage': { icon: 'mdi:account-details', order: 21 },
        'user-tag': { icon: 'mdi:tag-multiple', order: 23 },
        'user-avatar': { icon: 'mdi:account-box-multiple', order: 24 },
        'level-settings': { icon: 'mdi:medal-outline', order: 22 },
        'post-manage': { icon: 'mdi:card-text-outline', order: 31 },
        'publish-settings': { icon: 'mdi:tune-vertical', order: 32 },
        'media-settings': { hideInMenu:true },
        'storage-config': { icon: 'mdi:server-network', order: 51 },
        'image-compression': { icon: 'mdi:image-filter-vintage', order: 52 },
        'video-transcode': { icon: 'mdi:video-vintage', order: 53 },
        'notice-manage': { icon: 'mdi:bell-outline', order: 42 },
        'notice-category': { icon: 'mdi:shape-outline', order: 43 },
        'home-config': { icon: 'mdi:view-dashboard-edit-outline', order: 41 },
        'global-settings': { icon: 'mdi:cog-outline', order: 59 },
        profile: { icon: 'mdi:account-cog', order: 60 }
      };

      if (routeMetaMap[key]) {
        Object.assign(meta, routeMetaMap[key]);
      }

      return meta;
    }
  });
}
