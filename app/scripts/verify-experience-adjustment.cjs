const { chromium } = require('playwright-core');
const fs = require('node:fs'), path = require('node:path'), assert = require('node:assert/strict');
const output = path.resolve(__dirname, '../../artifacts/experience-adjustment');
fs.mkdirSync(output, { recursive: true });
const envelope = data => ({ json: { code: '0000', msg: 'ok', data } });
const failure = msg => ({ json: { code: '1000', msg, data: null } });
const results = [];
let activePage;
async function check(browser, readOnly = false) {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1080 } });
  const page = await context.newPage(); activePage = page;
  const errors = []; page.on('pageerror', error => errors.push(error.message));
  const user = { id: '9007199254740993', publicUid: '10001', account: 'point_user', nickname: '积分测试', avatar: '',
    qqNumber: '12345678', status: 1, level: 1, experience: 90, tags: [], statistics: {}, createTime: '2026-10-22 10:00', officialAdminId: null };
  const community = { ...user, id: '9007199254740994', account: 'point_official', officialAdminId: '8', status: 0 };
  const official = { id: '8', username: 'point_official', nickname: '官方积分测试', status: 0, userType: 1, community, statistics: {} };
  const noCommunity = { ...official, id: '9', username: 'new_official', nickname: '未登录官方账号', community: null };
  let detailFail = !readOnly, historyFail = !readOnly, loseResponse = false, rejectNext = false;
  let writeCalls = 0, pendingWrite = null, holdWrite = false;
  const requests = [], replay = new Map();
  const history = Array.from({ length: 23 }, (_, index) => ({ id: String(23-index), delta: 1,
    beforeExperience: index, afterExperience: index+1, beforeLevel: 1, afterLevel: 1,
    reason: '历史纠错', operatorId: '1', operatorName: '测试管理员', createTime: '2026-10-22T10:00:00' }));
  await page.addInitScript(() => localStorage.setItem('SOY_token', JSON.stringify('experience-ui-token')));
  await page.route('**/*', async route => {
    const request = route.request(), url = new URL(request.url()), pathname = url.pathname;
    if (!['xhr', 'fetch'].includes(request.resourceType())) return route.continue();
    if (pathname.endsWith('/auth/getUserInfo')) return route.fulfill(envelope({ userId: '1', userName: '积分测试', nickName: '测试管理员', avatar: '', roles: ['R_SUPER'], buttons: [], userType: readOnly ? 1 : 0 }));
    if (pathname.endsWith('/app-user/list')) return route.fulfill(envelope({ records: [user], total: 1, current: 1, size: 10, pages: 1 }));
    if (pathname.endsWith('/admin-user/list')) return route.fulfill(envelope({ records: [official, noCommunity], total: 2, current: 1, size: 10, pages: 1 }));
    if (pathname.endsWith('/app-user/detail')) {
      if (detailFail) { detailFail = false; return route.fulfill(failure('模拟详情失败')); }
      return route.fulfill(envelope(user));
    }
    if (pathname.endsWith('/admin-user/detail')) return route.fulfill(envelope(url.searchParams.get('id') === '9' ? noCommunity : official));
    if (pathname.endsWith('/experienceHistory')) {
      if (historyFail) { historyFail = false; return route.fulfill(failure('模拟记录失败')); }
      const p = Number(url.searchParams.get('pageNum') || 1);
      return route.fulfill(envelope({ records: history.slice((p-1)*20, p*20), total: history.length, current: p, size: 20, pages: Math.ceil(history.length/20) }));
    }
    if (pathname.endsWith('/adjustExperience')) {
      writeCalls++; const body = request.postDataJSON(); requests.push(body);
      assert.equal(readOnly, false);
      if (holdWrite) { holdWrite = false; await new Promise(resolve => { pendingWrite = resolve; }); }
      if (rejectNext) { rejectNext = false; return route.fulfill(failure('模拟提交失败')); }
      let result = replay.get(body.requestId);
      if (!result) {
        const target = pathname.endsWith('/admin-user/adjustExperience') ? community : user;
        result = { id: String(100+writeCalls), delta: body.delta, beforeExperience: target.experience,
          afterExperience: target.experience+body.delta, beforeLevel: target.level, afterLevel: target.experience+body.delta >= 100 ? 2 : 1 };
        target.experience = result.afterExperience; target.level = result.afterLevel;
        history.unshift({ ...result, reason: body.reason, operatorId: '1', operatorName: '测试管理员', createTime: '2026-10-22T11:00:00' });
        replay.set(body.requestId, result);
      }
      if (loseResponse) { loseResponse = false; return route.fulfill(failure('模拟提交后响应丢失，请重试')); }
      return route.fulfill(envelope(result));
    }
    return route.fulfill(envelope([]));
  });
  await page.goto('http://127.0.0.1:9527/app-user-manage');
  await page.getByText('point_user', { exact: true }).waitFor();
  if (readOnly) {
    assert.equal(await page.getByRole('button', { name: '调整积分', exact: true }).count(), 0);
    assert.equal(await page.getByText('官方账号', { exact: true }).count(), 0);
    assert.equal(writeCalls, 0); assert.deepEqual(errors, []);
    results.push({ readOnly: true, writeCalls, errors }); await context.close(); return;
  }
  await page.getByRole('button', { name: '调整积分', exact: true }).click();
  const modal = page.locator('.n-modal').last();
  await modal.getByText('用户积分加载失败', { exact: false }).waitFor();
  await modal.getByRole('button', { name: '重试', exact: true }).click();
  await modal.getByText('当前积分：90', { exact: false }).waitFor();
  await modal.getByText('记录加载失败', { exact: false }).waitFor();
  await modal.getByRole('button', { name: '重试', exact: true }).click();
  await modal.getByText('记录加载失败', { exact: false }).waitFor({ state: 'hidden' });
  const amount = modal.locator('.n-input-number input'), reason = modal.locator('textarea');
  const submit = modal.getByRole('button', { name: '确认调整', exact: true });
  assert(await submit.isDisabled());
  await amount.fill('100'); await reason.fill('   '); assert(await submit.isDisabled());
  await reason.fill('补发活动积分'); await modal.getByText('扣减积分', { exact: true }).click();
  assert(await submit.isDisabled()); // Cannot deduct 100 from 90.
  await modal.getByText('增加积分', { exact: true }).click(); assert(!(await submit.isDisabled()));
  await modal.locator('.n-pagination-item').filter({ hasText: /^2$/ }).click();
  await page.waitForFunction(() => document.querySelector('.n-modal .n-data-table-tbody')?.querySelectorAll('tr').length === 3);
  // A committed operation whose response was lost must retry the exact same UUID.
  loseResponse = true;
  async function send() {
    const response = page.waitForResponse(r => new URL(r.url()).pathname.endsWith('/adjustExperience'));
    await submit.click(); await response;
    await page.waitForFunction(() => ![...document.querySelectorAll('.n-modal button')].some(button => button.textContent.includes('确认调整') && button.classList.contains('n-button--loading')));
  }
  await send(); assert.equal(await amount.inputValue(), '100'); assert.equal(await reason.inputValue(), '补发活动积分');
  await send(); assert.equal(requests[0].requestId, requests[1].requestId); assert.equal(user.experience, 190);
  assert.equal(requests[0].id, user.id); assert.equal(await reason.inputValue(), '');
  await modal.getByText('当前积分：190', { exact: false }).waitFor();
  // Editing a rejected request creates a new operation.
  await amount.fill('10'); await reason.fill('第一次原因'); rejectNext = true; await send();
  const failedKey = requests.at(-1).requestId;
  await reason.fill('修改后的原因'); holdWrite = true;
  const callCount = writeCalls; await submit.click();
  for (let i=0; i<100 && !pendingWrite; i++) await page.waitForTimeout(20);
  assert(pendingWrite); assert(await modal.getByRole('button', { name: '关闭', exact: true }).isDisabled());
  await modal.locator('button').filter({ hasText: '确认调整' }).evaluate(button => button.click()); // Loading adds an icon to the accessible name.
  await page.keyboard.press('Escape'); assert(await modal.isVisible()); assert.equal(writeCalls, callCount+1);
  pendingWrite(); pendingWrite = null;
  await modal.getByText('当前积分：200', { exact: false }).waitFor();
  assert.notEqual(requests.at(-1).requestId, failedKey);
  await amount.fill('200'); await reason.fill('处罚扣减'); await modal.getByText('扣减积分', { exact: true }).click();
  await send(); await modal.getByText('当前积分：0', { exact: false }).waitFor(); assert.equal(user.level, 1);
  assert((await modal.innerText()).includes('-200'));
  await page.evaluate(() => window.$message?.destroyAll());
  await page.waitForTimeout(400); // Let the message exit animation finish before screenshots.
  await page.screenshot({ path: path.join(output, 'admin-adjustment.png'), fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.screenshot({ path: path.join(output, 'admin-adjustment-mobile.png'), fullPage: true });
  await page.setViewportSize({ width: 1440, height: 1080 });
  await modal.getByRole('button', { name: '关闭', exact: true }).click();
  await page.locator('.n-tabs-tab').filter({ hasText: /^官方账号$/ }).click();
  await page.getByText('point_official', { exact: true }).waitFor();
  await page.getByRole('button', { name: '调整积分', exact: true }).first().click();
  await modal.getByText('当前积分：90', { exact: false }).waitFor();
  await amount.fill('10'); await reason.fill('官方账号积分纠错'); await send();
  assert.equal(requests.at(-1).id, official.id); assert.equal(community.experience, 100);
  await modal.getByRole('button', { name: '关闭', exact: true }).click();
  await page.getByRole('button', { name: '调整积分', exact: true }).nth(1).click();
  await modal.getByText('该官方账号尚未建立社区身份，无法调整积分。', { exact: true }).waitFor(); assert(await submit.isDisabled());
  assert.deepEqual(errors, []);
  results.push({ readOnly: false, writeCalls, duplicateRetrySafe: true, pagination: true, disabledAccount: true,
    officialAccount: true, unlinkedOfficialRejected: true, errors });
  await context.close();
}
(async () => {
  const browser = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: true });
  try {
    await check(browser); await check(browser, true);
    fs.writeFileSync(path.join(output, 'admin-ui-report.json'), JSON.stringify({ status: 'passed', scope: 'Browser interaction checks with mocked APIs; backend tested independently in isolated MySQL', results }, null, 2));
    console.log(JSON.stringify({ status: 'passed', scenarios: results.length }));
  } catch (error) {
    if (activePage && !activePage.isClosed()) { await activePage.screenshot({ path: path.join(output, 'admin-ui-failure.png') }); console.error((await activePage.locator('body').innerText()).slice(-1800)); }
    throw error;
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
