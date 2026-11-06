// Exercise the real request module with native uni.request callbacks and a controlled clock.
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')
const { platformSource } = require('./platform-source.cjs')
const source = fs.readFileSync(path.join(__dirname, '../utils/request.uts'), 'utf8')
const compile = target => ts.transpileModule(platformSource(source, target).replaceAll('import.meta.env.DEV', 'false'), {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 }
}).outputText
const android = compile('app-android')
const web = compile('h5')
function setup(outcomes, { code = android, uploadFile } = {}) {
  const calls = [], toasts = [], timers = []
  let generation = 0
  const exports = {}
  const auth = {
    getSessionGeneration: () => generation, getToken: () => 'test-token',
    getRefreshToken: () => null, clearLogin: () => { generation++ },
    saveLogin: () => { throw new Error('Unexpected login change') }
  }
  vm.runInNewContext(code, {
    exports, require: name => {
      if (name === '@/config/env') return { API_BASE_URL: 'http://test.invalid' }
      if (name === './auth') return auth
      throw new Error('Unexpected import: ' + name)
    },
    console, setTimeout: (fn, delay) => { timers.push({ fn, delay }) },
    uni: {
      uploadFile,
      showToast: value => toasts.push(value.title),
      request: options => {
        calls.push(options)
        const outcome = outcomes.shift()
        assert(outcome, 'Unexpected extra request')
        if (typeof outcome === 'string') options.fail({ errMsg: outcome })
        else options.success(outcome)
      }
    }
  })
  return { api: exports, calls, toasts, timers, logout: auth.clearLogin }
}
const ok = { statusCode: 200, data: { code: '0000', data: { loaded: true } } }
;(async () => {
  // uni-app x Android 连接被拒只回 "server system error"，必须与 timeout / Failed to connect 同等对待：
  // 先自动重试一次，仍失败才提示，否则界面只会剩下无法定位的“网络异常”。
  for (const failure of ['request:fail abort statusCode:-1 timeout', 'request:fail abort statusCode:-1 Failed to connect to /192.168.1.10:8080', 'request:fail server system error']) {
    const test = setup([failure, ok])
    const result = test.api.GET('/app/post/list', { pageNum: 1 })
    assert.equal(test.calls.length, 1)
    assert.equal(test.toasts.length, 0, 'Do not toast before recovery finishes')
    assert.equal(test.timers[0].delay, 300)
    test.timers.shift().fn()
    assert.equal((await result).loaded, true)
    assert.equal(test.calls.length, 2)
    assert.equal(test.calls[0].timeout, 15000)
    assert.equal(test.calls[1].timeout, 5000)
    assert.deepEqual(test.calls[1].data, test.calls[0].data)
    assert.deepEqual(test.calls[1].header, test.calls[0].header)
    assert.equal(test.toasts.length, 0)
  }
  const offline = setup(['request:fail timeout', 'request:fail timeout'])
  const pending = offline.api.GET('/app/home/config')
  const rejected = assert.rejects(pending, /连接超时/)
  offline.timers.shift().fn()
  await rejected
  assert.equal(offline.calls.length, 2)
  assert.equal(offline.timers.length, 0, 'No endless retry')
  assert.deepEqual(offline.toasts, ['连接超时，请重试'])

  // 持续连不上时给出可操作的连接提示，而不是笼统的“网络异常”。
  const refused = setup(['request:fail server system error', 'request:fail server system error'])
  const refusedRejected = assert.rejects(refused.api.GET('/app/post/list'), /无法连接服务器/)
  refused.timers.shift().fn()
  await refusedRejected
  assert.equal(refused.calls.length, 2)
  assert.deepEqual(refused.toasts, ['无法连接服务器，请重试'])

  for (const [method, outcome, message] of [
    ['POST', 'request:fail timeout', /连接超时/],
    ['GET', 'request:fail abort', /网络异常/],
    ['GET', { statusCode: 503, data: {} }, /服务暂不可用/],
    ['GET', { statusCode: 200, data: { code: '1001', msg: '业务失败' } }, /业务失败/]
  ]) {
    const test = setup([outcome])
    await assert.rejects(test.api[method]('/app/test'), message)
    assert.equal(test.calls.length, 1)
    assert.equal(test.timers.length, 0)
  }
  const loggedOut = setup(['request:fail timeout'])
  const stale = loggedOut.api.GET('/app/post/list')
  const staleRejected = assert.rejects(stale, /登录状态已改变/)
  loggedOut.logout()
  loggedOut.timers.shift().fn()
  await staleRejected
  assert.equal(loggedOut.calls.length, 1, 'Never retry credentials from a logged-out session')
  assert.equal(loggedOut.toasts.length, 0)

  const albumPath = '/data/user/0/io.dcloud.uniappx/cache/ct-album/selected.jpg'
  const videoPath = '/data/data/io.dcloud.uniappx/cache/ct-album/selected.mp4'
  for (const [code, input, expected] of [
    [android, albumPath, 'file://' + albumPath],
    [android, videoPath, 'file://' + videoPath],
    [android, 'file://' + albumPath, 'file://' + albumPath],
    [android, 'unifile://cache/photo.jpg', 'unifile://cache/photo.jpg'],
    [web, albumPath, albumPath]
  ]) {
    const test = setup([], { code, uploadFile: options => {
      assert.equal(options.filePath, expected)
      options.success(ok)
      return {}
    } })
    assert.equal((await test.api.upload('/app/publish/assets/image', input)).loaded, true)
  }
  const missing = setup([], { uploadFile: () => { throw new Error('java.io.FileNotFoundException: open failed: ENOENT') } })
  await assert.rejects(missing.api.upload('/app/publish/assets/image', albumPath), /^Error: 本地文件已失效，请重新选择$/)
  assert.deepEqual(missing.toasts, ['本地文件已失效，请重新选择'])
  console.log('Request recovery and Android album upload path checks passed.')
})().catch(error => { console.error(error); process.exitCode = 1 })
