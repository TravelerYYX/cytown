import type { RouteLocationNormalizedLoaded, RouteRecordRaw, _RouteRecordBase } from 'vue-router';
import type { ElegantConstRoute, LastLevelRouteKey, RouteKey, RouteMap } from '@elegant-router/types';
import { useSvgIcon } from '@/hooks/common/icon';
import { $t, getLocale } from '@/locales';

/**
 * Filter auth routes by roles
 *
 * @param routes Auth routes
 * @param roles Roles
 */
export function filterAuthRoutesByRoles(routes: ElegantConstRoute[], roles: string[]) {
  return routes.flatMap(route => filterAuthRouteByRoles(route, roles));
}

/**
 * Filter auth route by roles
 *
 * @param route Auth route
 * @param roles Roles
 */
function filterAuthRouteByRoles(route: ElegantConstRoute, roles: string[]): ElegantConstRoute[] {
  const routeRoles = (route.meta && route.meta.roles) || [];

  // if the route's "roles" is empty, then it is allowed to access
  const isEmptyRoles = !routeRoles.length;

  // if the user's role is included in the route's "roles", then it is allowed to access
  const hasPermission = routeRoles.some(role => roles.includes(role));

  const filterRoute = { ...route };

  if (filterRoute.children?.length) {
    filterRoute.children = filterRoute.children.flatMap(item => filterAuthRouteByRoles(item, roles));
  }

  // Exclude the route if it has no children after filtering
  if (filterRoute.children?.length === 0) {
    return [];
  }

  return hasPermission || isEmptyRoles ? [filterRoute] : [];
}

/**
 * sort route by order
 *
 * @param route route
 */
function sortRouteByOrder(route: ElegantConstRoute) {
  if (route.children?.length) {
    route.children.sort((next, prev) => (Number(next.meta?.order) || 0) - (Number(prev.meta?.order) || 0));
    route.children.forEach(sortRouteByOrder);
  }

  return route;
}

/**
 * sort routes by order
 *
 * @param routes routes
 */
export function sortRoutesByOrder(routes: ElegantConstRoute[]) {
  routes.sort((next, prev) => (Number(next.meta?.order) || 0) - (Number(prev.meta?.order) || 0));
  routes.forEach(sortRouteByOrder);

  return routes;
}

interface MenuGroupConfig {
  key: string;
  label: string;
  enLabel: string;
  icon: string;
  childKeys: RouteKey[];
}

const MENU_GROUPS: MenuGroupConfig[] = [
  {
    key: 'user-center-group',
    label: '用户中心',
    enLabel: 'User Center',
    icon: 'mdi:account-group',
    childKeys: ['app-user-manage', 'level-settings', 'user-tag', 'user-avatar']
  },
  {
    key: 'content-management-group',
    label: '内容管理',
    enLabel: 'Content Management',
    icon: 'mdi:book-open-page-variant-outline',
    childKeys: ['post-manage', 'report-manage', 'publish-settings', 'chat-manage', 'chat-settings']
  },
  {
    key: 'operation-management-group',
    label: '运营管理',
    enLabel: 'Operation Management',
    icon: 'mdi:bullhorn-outline',
    childKeys: ['checkin-settings', 'home-config', 'notice-manage', 'notice-category', 'notice-push', 'app-releases']
  },
  {
    key: 'media-management-group',
    label: '媒体与存储',
    enLabel: 'Media & Storage',
    icon: 'mdi:cloud-upload-outline',
    childKeys: ['storage-config', 'image-compression', 'video-transcode']
  }
];

const MENU_ORDER_LAYOUT: Array<{ type: 'single'; key: RouteKey } | { type: 'group'; groupKey: string }> = [
  { type: 'single', key: 'home' },
  { type: 'group', groupKey: 'user-center-group' },
  { type: 'group', groupKey: 'content-management-group' },
  { type: 'group', groupKey: 'operation-management-group' },
  { type: 'group', groupKey: 'media-management-group' },
  { type: 'single', key: 'global-settings' },
  { type: 'single', key: 'profile' }
];

const routeDefaultIconMap: Partial<Record<RouteKey, string>> = {
  home: 'mdi:monitor-dashboard',
  'app-user-manage': 'mdi:account-details',
  'level-settings': 'mdi:medal-outline',
  'user-tag': 'mdi:tag-multiple',
  'user-avatar': 'mdi:account-box-multiple',
  'post-manage': 'mdi:card-text-outline',
  'publish-settings': 'mdi:tune-vertical',
  'chat-settings': 'mdi:message-cog-outline',
  'home-config': 'mdi:view-dashboard-edit-outline',
  'notice-manage': 'mdi:bell-outline',
  'notice-push': 'mdi:bell-cog-outline',
  'notice-category': 'mdi:shape-outline',
  'storage-config': 'mdi:server-network',
  'image-compression': 'mdi:image-filter-vintage',
  'video-transcode': 'mdi:video-vintage',
  'global-settings': 'mdi:cog-outline',
  profile: 'mdi:account-cog'
};

/**
 * Get global menus by auth routes
 *
 * @param routes Auth routes
 */
export function getGlobalMenusByAuthRoutes(routes: ElegantConstRoute[]) {
  const { SvgIconVNode } = useSvgIcon();
  const isZh = getLocale() === 'zh-CN';

  // 1. Build a map of all visible base routes
  const baseMenuMap = new Map<string, App.Global.Menu>();
  routes.forEach(route => {
    if (!route.meta?.hideInMenu) {
      const menu = getGlobalMenuByBaseRoute(route);

      if (route.children?.some(child => !child.meta?.hideInMenu)) {
        menu.children = getGlobalMenusByAuthRoutes(route.children);
      }

      baseMenuMap.set(route.name, menu);
    }
  });

  const menus: App.Global.Menu[] = [];
  const consumedKeys = new Set<string>();

  // 2. Iterate through structured layout
  MENU_ORDER_LAYOUT.forEach(item => {
    if (item.type === 'single') {
      const menu = baseMenuMap.get(item.key);
      if (menu) {
        menus.push(menu);
        consumedKeys.add(item.key);
      }
    } else if (item.type === 'group') {
      const groupConfig = MENU_GROUPS.find(g => g.key === item.groupKey);
      if (!groupConfig) return;

      const children: App.Global.Menu[] = [];
      groupConfig.childKeys.forEach(childKey => {
        const childMenu = baseMenuMap.get(childKey);
        if (childMenu) {
          children.push(childMenu);
          consumedKeys.add(childKey);
        }
      });

      if (children.length > 0) {
        const firstChild = children[0];
        const groupMenu: App.Global.Menu = {
          key: groupConfig.key,
          label: isZh ? groupConfig.label : groupConfig.enLabel,
          routeKey: groupConfig.key as RouteKey,
          routePath: firstChild.routePath,
          icon: SvgIconVNode({ icon: groupConfig.icon, fontSize: 20 }),
          children
        };
        menus.push(groupMenu);
      }
    }
  });

  // 3. Append any remaining unconsumed routes in order
  baseMenuMap.forEach((menu, key) => {
    if (!consumedKeys.has(key)) {
      menus.push(menu);
    }
  });

  return menus;
}

/**
 * Update locale of global menus
 *
 * @param menus
 */
export function updateLocaleOfGlobalMenus(menus: App.Global.Menu[]) {
  const isZh = getLocale() === 'zh-CN';
  const groupConfigMap = new Map(MENU_GROUPS.map(g => [g.key, g]));
  const result: App.Global.Menu[] = [];

  menus.forEach(menu => {
    const { i18nKey, label, children, key } = menu;

    let newLabel = label;
    if (i18nKey) {
      newLabel = $t(i18nKey);
    } else if (groupConfigMap.has(key)) {
      const group = groupConfigMap.get(key)!;
      newLabel = isZh ? group.label : group.enLabel;
    }

    const newMenu: App.Global.Menu = {
      ...menu,
      label: newLabel
    };

    if (children?.length) {
      newMenu.children = updateLocaleOfGlobalMenus(children);
    }

    result.push(newMenu);
  });

  return result;
}

/**
 * Get global menu by route
 *
 * @param route
 */
function getGlobalMenuByBaseRoute(route: RouteLocationNormalizedLoaded | ElegantConstRoute) {
  const { SvgIconVNode } = useSvgIcon();

  const { name, path } = route;
  const defaultIcon = routeDefaultIconMap[name as RouteKey] || import.meta.env.VITE_MENU_ICON;
  const { title, i18nKey, icon = defaultIcon, localIcon, iconFontSize } = route.meta ?? {};

  const label = i18nKey ? $t(i18nKey) : title!;

  const menu: App.Global.Menu = {
    key: name as string,
    label,
    i18nKey,
    routeKey: name as RouteKey,
    routePath: path as RouteMap[RouteKey],
    icon: SvgIconVNode({ icon, localIcon, fontSize: iconFontSize || 20 })
  };

  return menu;
}

/**
 * Get cache route names
 *
 * @param routes Vue routes (two levels)
 */
export function getCacheRouteNames(routes: RouteRecordRaw[]) {
  const cacheNames: LastLevelRouteKey[] = [];

  routes.forEach(route => {
    // only get last two level route, which has component
    route.children?.forEach(child => {
      if (child.component && child.meta?.keepAlive) {
        cacheNames.push(child.name as LastLevelRouteKey);
      }
    });
  });

  return cacheNames;
}

/**
 * Is route exist by route name
 *
 * @param routeName
 * @param routes
 */
export function isRouteExistByRouteName(routeName: RouteKey, routes: ElegantConstRoute[]) {
  return routes.some(route => recursiveGetIsRouteExistByRouteName(route, routeName));
}

/**
 * Recursive get is route exist by route name
 *
 * @param route
 * @param routeName
 */
function recursiveGetIsRouteExistByRouteName(route: ElegantConstRoute, routeName: RouteKey) {
  let isExist = route.name === routeName;

  if (isExist) {
    return true;
  }

  if (route.children && route.children.length) {
    isExist = route.children.some(item => recursiveGetIsRouteExistByRouteName(item, routeName));
  }

  return isExist;
}

/**
 * Get selected menu key path
 *
 * @param selectedKey
 * @param menus
 */
export function getSelectedMenuKeyPathByKey(selectedKey: string, menus: App.Global.Menu[]) {
  const keyPath: string[] = [];

  menus.some(menu => {
    const path = findMenuPath(selectedKey, menu);

    const find = Boolean(path?.length);

    if (find) {
      keyPath.push(...path!);
    }

    return find;
  });

  return keyPath;
}

/**
 * Find menu path
 *
 * @param targetKey Target menu key
 * @param menu Menu
 */
function findMenuPath(targetKey: string, menu: App.Global.Menu): string[] | null {
  const path: string[] = [];

  function dfs(item: App.Global.Menu): boolean {
    path.push(item.key);

    if (item.key === targetKey) {
      return true;
    }

    if (item.children) {
      for (const child of item.children) {
        if (dfs(child)) {
          return true;
        }
      }
    }

    path.pop();

    return false;
  }

  if (dfs(menu)) {
    return path;
  }

  return null;
}

/**
 * Transform menu to breadcrumb
 *
 * @param menu
 */
function transformMenuToBreadcrumb(menu: App.Global.Menu) {
  const { children, ...rest } = menu;

  const breadcrumb: App.Global.Breadcrumb = {
    ...rest
  };

  if (children?.length) {
    breadcrumb.options = children.map(transformMenuToBreadcrumb);
  }

  return breadcrumb;
}

/**
 * Get breadcrumbs by route
 *
 * @param route
 * @param menus
 */
export function getBreadcrumbsByRoute(
  route: RouteLocationNormalizedLoaded,
  menus: App.Global.Menu[]
): App.Global.Breadcrumb[] {
  const key = route.name as string;
  const activeKey = route.meta?.activeMenu;

  for (const menu of menus) {
    if (menu.key === key) {
      return [transformMenuToBreadcrumb(menu)];
    }

    if (menu.key === activeKey) {
      const ROUTE_DEGREE_SPLITTER = '_';

      const parentKey = key.split(ROUTE_DEGREE_SPLITTER).slice(0, -1).join(ROUTE_DEGREE_SPLITTER);

      const breadcrumbMenu = getGlobalMenuByBaseRoute(route);
      if (parentKey !== activeKey) {
        return [transformMenuToBreadcrumb(breadcrumbMenu)];
      }

      return [transformMenuToBreadcrumb(menu), transformMenuToBreadcrumb(breadcrumbMenu)];
    }

    if (menu.children?.length) {
      const result = getBreadcrumbsByRoute(route, menu.children);
      if (result.length > 0) {
        return [transformMenuToBreadcrumb(menu), ...result];
      }
    }
  }

  return [];
}

/**
 * Transform menu to searchMenus
 *
 * @param menus - menus
 * @param treeMap
 */
export function transformMenuToSearchMenus(menus: App.Global.Menu[], treeMap: App.Global.Menu[] = []) {
  if (menus && menus.length === 0) return [];
  return menus.reduce((acc, cur) => {
    if (!cur.children) {
      acc.push(cur);
    }
    if (cur.children && cur.children.length > 0) {
      transformMenuToSearchMenus(cur.children, treeMap);
    }
    return acc;
  }, treeMap);
}
