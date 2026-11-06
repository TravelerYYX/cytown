const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')
const storage = new Map()
let request
const uni = {
  getStorageSync: key => storage.get(key),
  setStorageSync: (key, value) => storage.set(key, structuredClone(value)),
  removeStorageSync: key => storage.delete(key),
  getStorageInfoSync: () => ({ keys: [...storage.keys()], currentSize: 10 }),
  request: options => { request = options }
}
function load(file, dependencies) {
  const exports = {}
  const code = ts.transpileModule(fs.readFileSync(path.join(__dirname, '../utils', file + '.uts'), 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 }
  }).outputText
  vm.runInNewContext(code, { exports, uni, require: name => {
    if (!(name in dependencies)) throw Error('Unexpected dependency ' + name)
    return dependencies[name]
  } })
  return exports
}
const env = { API_BASE_URL: 'https://test.invalid' }
const deps = { vue: { ref: value => ({ value }) }, '@/config/env': env, '@/utils/app-settings': { SETTINGS_STORAGE_KEYS: { theme: 'settings_theme' } } }
const auth = load('auth', deps)
const switching = load('account-switch', { './auth': auth, '@/config/env': env })
const account = id => ({ token: 'token-' + id, refreshToken: 'refresh-' + id, userInfo: { userId: id, nickname: 'user-' + id } })
const a = account('a'), b = account('b')
const success = result => request.success({ statusCode: 200, data: { code: '0000', data: result } })
;(async () => {
  // Existing installations have only the three legacy keys.
  storage.set('app_token', a.token); storage.set('app_refresh_token', a.refreshToken); storage.set('app_user_info', a.userInfo)
  assert.equal(auth.getRememberedAccounts()[0].userInfo.userId, 'a')
  auth.saveLogin(b)
  assert.equal(auth.getRememberedAccounts().length, 2)
  auth.saveLogin(b)
  assert.equal(auth.getRememberedAccounts().length, 2, 're-login must not duplicate an account')
  const updated = { ...b.userInfo, nickname: 'renamed' }
  auth.saveUserInfo(updated)
  assert.equal(auth.getRememberedAccounts().find(item => item.userInfo.userId === 'b').userInfo.nickname, 'renamed')
  auth.saveUserInfo(a.userInfo)
  assert.equal(auth.getUserInfo().userId, 'b', 'stale profile must not replace current identity')
  storage.set('cache', 'discard'); storage.set('settings_theme', 'dark')
  auth.clearAppCache()
  assert.equal(storage.has('cache'), false)
  assert.equal(storage.get('settings_theme'), 'dark')
  assert.equal(auth.getRememberedAccounts().length, 2)
  const cold = load('auth', deps)
  assert.equal(cold.getRememberedAccounts().length, 2, 'accounts survive process restart')

  let pending = switching.switchAccount('a')
  assert.equal(auth.getUserInfo().userId, 'b', 'keep original session until target validates')
  assert.equal(request.data.refreshToken, a.refreshToken)
  const fresh = { ...a, token: 'fresh-a', refreshToken: 'fresh-refresh-a' }
  success(fresh); await pending
  assert.equal(auth.getToken(), 'fresh-a')
  assert.equal(auth.getRememberedAccounts().length, 2)
  pending = switching.switchAccount('b')
  request.fail(); await assert.rejects(pending, /无法连接/)
  assert.equal(auth.getToken(), 'fresh-a')
  assert.equal(auth.getRememberedAccounts().length, 2, 'network failures retain saved credentials')
  pending = switching.switchAccount('b')
  success(a); await assert.rejects(pending, /验证失败/)
  assert.equal(auth.getToken(), 'fresh-a', 'wrong-user server response cannot switch identity')
  pending = switching.switchAccount('b')
  request.success({ statusCode: 200, data: { code: '8888' } })
  await assert.rejects(pending, /登录已失效/)
  assert.equal(auth.getRememberedAccounts().length, 1)
  assert.equal(auth.getToken(), 'fresh-a', 'expired target must not log out the current account')

  auth.saveLogin(b)
  pending = switching.switchAccount('a')
  auth.clearLogin()
  success(fresh); await assert.rejects(pending, /登录状态已改变/)
  assert.equal(auth.getToken(), null, 'late switch response cannot undo logout')
  assert.equal(auth.getRememberedAccounts().length, 1, 'logout removes only the current account')
  assert.equal(auth.getRememberedAccounts()[0].userInfo.userId, 'a')
  console.log('PASS: migration, persistence, deduplication, profile isolation, cache protection, verified switching, offline/expired targets, and logout race')
})().catch(error => { console.error(error); process.exitCode = 1 })
