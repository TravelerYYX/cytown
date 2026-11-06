const { chromium } = require('playwright-core');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');

const origin = process.env.ADMIN_UI_TEST_URL || 'http://127.0.0.1:9527';
const output = path.resolve(__dirname, '../artifacts/media-ui');

(async () => {
  fs.mkdirSync(output, { recursive: true });
  const browser = await chromium.launch({ headless: true, executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe' });
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 1100 } });
    const errors = [], tested = [], saved = [];
    let readOnly = false;
    let storage = { id: 'legacy-local', provider: 'local', localDir: 'D:/uploads', endpoint: '', region: '', bucket: '', accessKey: '', secretKey: '', domain: '' };
    await page.addInitScript(() => localStorage.setItem('SOY_token', JSON.stringify('ftp-ui-fixture-token')));
    page.on('pageerror', error => errors.push(error.message));
    await page.route('**/*', async route => {
      const request = route.request(), name = new URL(request.url()).pathname;
      if (!['xhr', 'fetch'].includes(request.resourceType())) return route.continue();
      let data = {};
      if (name.endsWith('/auth/getUserInfo')) data = { userId: '1', userName: 'FTP 测试', nickName: '测试管理员', avatar: '', roles: ['R_SUPER'], buttons: [], userType: readOnly ? 1 : 0 };
      else if (name.endsWith('/media-config/storage/test')) {
        tested.push(request.postDataJSON()); data = null;
        // A real FTP round trip can exceed the common 10s request limit.
        if (tested.length === 1) await new Promise(resolve => setTimeout(resolve, 11_000));
      }
      else if (name.endsWith('/media-config/storage')) {
        if (request.method() === 'POST') {
          saved.push(request.postDataJSON());
          storage = { ...request.postDataJSON(), id: 'saved-ftp', ftpPassword: '', secretKey: '' };
        }
        data = storage;
      }
      else if (name.endsWith('/media-config/processing')) data = { images: { coverMaxEdge: 1280, coverQuality: 78, previewMaxEdge: 2560, previewQuality: 85, pngCompression: 6, cacheMaxBytes: 3 * 1024 ** 3 }, videos: { segmentSeconds: 4, maxFps: 30, audioKbps: 128, crf: 23, concurrency: 1, bitrates: { 720: 2800 } } };
      return route.fulfill({ json: { code: '0000', msg: 'ok', data } });
    });
    await page.goto(origin + '/storage-config');
    await page.locator('.n-base-selection').first().click();
    await page.getByText('远程 FTP', { exact: true }).click();
    const field = label => page.locator('.n-form-item').filter({ hasText: label }).locator('input');
    assert.equal(await field('FTP 端口').inputValue(), '21');
    assert.equal(await field('FTP 根目录').inputValue(), '/');
    assert.equal(await field('Bucket').count(), 0);
    assert.equal(await field('Access Key').count(), 0);
    await field('FTP 主机').fill('ftp.example.com');
    await field('FTP 用户名').fill('ftp-user');
    await field('FTP 密码').fill(' secret with spaces ');
    await field('FTP 根目录').fill('/media/中文目录');
    await field('访问域名').fill('https://cdn.example.com/files');
    await page.getByRole('button', { name: '连接检测', exact: true }).click();
    await page.getByText('连接检测通过', { exact: true }).waitFor();
    assert.equal(await field('FTP 密码').inputValue(), ' secret with spaces ');
    assert.equal(tested[0].ftpPassword, ' secret with spaces ');
    await page.getByRole('button', { name: '保存存储配置', exact: true }).click();
    await page.getByText('存储配置已保存', { exact: true }).waitFor();
    assert.equal(saved[0].provider, 'ftp');
    assert.equal(saved[0].ftpPort, 21);
    assert.equal(saved[0].ftpRootDir, '/media/中文目录');
    assert.equal(saved[0].ftpPassword, ' secret with spaces ');
    assert.equal(await field('FTP 密码').inputValue(), '');
    await page.screenshot({ path: path.join(output, 'admin-ftp-storage.png') });
    await page.reload();
    await field('FTP 主机').waitFor();
    assert.equal(await field('FTP 主机').inputValue(), 'ftp.example.com');
    assert.equal(await field('FTP 密码').inputValue(), '');
    await page.getByRole('button', { name: '连接检测', exact: true }).click();
    await page.getByText('连接检测通过', { exact: true }).waitFor();
    assert.equal(tested[1].ftpPassword, '');
    await field('FTP 密码').fill('unsaved-password');
    await page.locator('.n-base-selection').first().click();
    await page.getByText('本地存储', { exact: true }).click();
    await page.locator('.n-base-selection').first().click();
    await page.getByText('远程 FTP', { exact: true }).click();
    assert.equal(await field('FTP 密码').inputValue(), '');
    readOnly = true;
    await page.reload();
    await field('FTP 主机').waitFor({ timeout: 10000 }).catch(async error => {
      await page.screenshot({ path: path.join(output, 'admin-ftp-readonly-debug.png') });
      console.error('Read-only page:', page.url(), 'profile:', storage.provider, 'errors:', errors, await page.locator('body').innerText());
      throw error;
    });
    assert.equal(await field('FTP 主机').isDisabled(), true);
    assert.equal(await page.getByRole('button', { name: '连接检测', exact: true }).isDisabled(), true);
    assert.equal(await page.getByRole('button', { name: '保存存储配置', exact: true }).isDisabled(), true);
    assert.deepEqual(errors, []);
    fs.writeFileSync(path.join(output, 'admin-ftp-result.json'), JSON.stringify({ status: 'passed', checks: ['legacy profile defaults', 'FTP fields', 'detection can exceed 10 seconds', 'connection test retains password spaces', 'masked save and reload', 'blank password submission', 'provider switch clears password', 'read-only permissions'], errors }, null, 2));
    console.log('FTP admin form, credential handling, defaults and read-only permissions passed.');
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
