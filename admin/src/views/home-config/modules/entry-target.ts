export function validateEntryTarget(type: string | undefined, target: string | undefined): boolean {
  const value = target?.trim() || '';
  if (type === 'app') {
    if (!/^\/pages\/[A-Za-z0-9/-]+(?:\?[^#\s]*)?$/.test(value) || value.length > 2048) {
      window.$message?.warning('请选择 APP 页面并填写有效的页面地址'); return false;
    }
    const url = new URL(value, 'https://app.local');
    const key = ['/pages/post/index', '/pages/notice/detail'].includes(url.pathname) ? 'id' : url.pathname === '/pages/notice/list' ? 'categoryId' : '';
    if (key && !/^[1-9]\d{0,18}$/.test(url.searchParams.get(key) || '')) {
      window.$message?.warning('请填写页面所需的有效 ID 参数'); return false;
    }
    return true;
  }
  try {
    const url = new URL(value);
    if (value.length > 2048 || !['http:', 'https:'].includes(url.protocol) || url.username || url.password) throw new Error();
    return true;
  } catch { window.$message?.warning('请输入有效的 HTTP/HTTPS 完整链接'); return false; }
}
