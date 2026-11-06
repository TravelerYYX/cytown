const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')
const vue = require('vue')

const storage = new Map()
const uni = {
  getStorageSync: key => storage.get(key),
  setStorageSync: (key, value) => storage.set(key, value),
  removeStorageSync: key => storage.delete(key)
}
function loadModule(file, imports) {
  const source = fs.readFileSync(path.resolve(__dirname, '../utils', file), 'utf8')
  const compiled = ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 }
  }).outputText
  const exports = {}
  vm.runInNewContext(compiled, { exports, uni, require: name => {
    if (name === 'vue') return vue
    if (name in imports) return imports[name]
    throw new Error('Unexpected import: ' + name)
  } })
  return exports
}
const auth = loadModule('auth.ts', {
  '@/config/env': { API_BASE_URL: 'http://test.invalid' },
  '@/utils/app-settings': { SETTINGS_STORAGE_KEYS: {} }
})
const user = { userId: '1', nickname: '原昵称' }
auth.saveLogin({ token: 'test-token', refreshToken: 'refresh', userInfo: user })
let response
const interactions = loadModule('interactions.ts', {
  './auth': auth, './auth-api': {}, './request': { GET: async () => response }
})
const fixture = (id, authorId, nickname) => ({ id, author: { id: authorId, nickname }, likeCount: 3, commentCount: 1,
  detail: { liked: true, favorited: true, favoriteCount: 2 } })

;(async () => {
  const mine = interactions.registerPost(fixture('101', '1', '发布时旧昵称'))
  const other = interactions.registerPost(fixture('102', '2', '其他作者'))
  const displayedName = vue.computed(() => mine.author.nickname)
  assert.equal(displayedName.value, '原昵称')
  auth.saveUserInfo({ ...user, nickname: '修改后昵称' })
  await vue.nextTick()
  assert.equal(displayedName.value, '修改后昵称', 'Loaded cards must update after saving a nickname')
  assert.equal(other.author.nickname, '其他作者', 'Do not change another author')
  assert.equal(mine.detail.liked, true, 'Renaming must retain interaction state')
  assert.strictEqual(interactions.registerPost(fixture('101', '1', '原昵称')), mine)
  assert.equal(displayedName.value, '修改后昵称', 'An old in-flight response must not undo the rename')

  response = { authorNickname: '原昵称', likeCount: 3, commentCount: 1, liked: true, favorited: true }
  await interactions.refreshPostInteraction(mine)
  assert.equal(displayedName.value, '修改后昵称', 'Detail refresh must not restore an old nickname')
  response = { ...response, authorNickname: '其他作者的新昵称' }
  await interactions.refreshPostInteraction(other)
  assert.equal(other.author.nickname, '其他作者的新昵称', 'Detail refresh must update another author from the server')

  auth.clearLogin()
  await vue.nextTick()
  assert.equal(mine.detail.liked, false)
  assert.equal(displayedName.value, '修改后昵称', 'Logout must retain the visible author name')
  auth.saveLogin({ token: 'second', refreshToken: 'refresh', userInfo: { userId: '2', nickname: '第二个账号' } })
  await vue.nextTick()
  const second = interactions.registerPost(fixture('102', '2', '旧昵称'))
  assert.equal(second.author.nickname, '第二个账号')
  assert.equal(interactions.registerPost(fixture('101', '1', '修改后昵称')).author.nickname, '修改后昵称')
  console.log('Post author regression checks passed: reactive rename, stale responses, other authors, logout and account switching.')
})().catch(error => { console.error(error); process.exitCode = 1 })
