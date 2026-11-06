const { test } = require('node:test')
const assert = require('node:assert/strict')
const crypto = require('node:crypto')
const bridge = require('../uniCloud-aliyun/cloudfunctions/ct-notice-push/index.js')
const secret = 'test-key-for-notice-bridge-only-12345678'
function event() {
  const body = JSON.stringify({ appId: '__UNI__test', requestId: 'a'.repeat(32), cid: 'device-1', title: '通知', content: '测试', payload: { type: 'notice', noticeId: 1, categoryId: 1 } })
  const time = String(Date.now()), nonce = crypto.randomUUID()
  return { httpMethod: 'POST', body, headers: { 'X-CT-Timestamp': time, 'X-CT-Nonce': nonce, 'X-CT-Signature': crypto.createHmac('sha256', secret).update(time + '\n' + nonce + '\n' + body).digest('hex') } }
}
test('signed body round trips and rejects mutation, wrong key, expired timestamp and non POST', () => {
  const request = event(); assert.equal(bridge.authenticate(request, secret).body.cid, 'device-1')
  assert.throws(() => bridge.authenticate({ ...request, body: request.body + ' ' }, secret))
  assert.throws(() => bridge.authenticate(request, 'wrong'.repeat(10)))
  assert.throws(() => bridge.authenticate(request, secret, Date.now() + 600000))
  assert.throws(() => bridge.authenticate({ ...request, httpMethod: 'GET' }, secret))
})
test('cloud replay guard and request id dedup send only once; rejection can retry', async () => {
  process.env.CT_PUSH_SECRET = secret; process.env.CT_PUSH_APP_ID = '__UNI__test'
  const tables = new Map(), calls = []
  global.uniCloud = {
    database: () => ({ collection(name) {
      if (!tables.has(name)) tables.set(name, new Map()); const table = tables.get(name)
      return { async add(row) { if (table.has(row._id)) throw Error('duplicate'); table.set(row._id, { ...row }) }, doc(id) { return { async get() { return { data: table.has(id) ? [table.get(id)] : [] } }, async update(value) { Object.assign(table.get(id), value) }, async remove() { table.delete(id) } } } }
    } }),
    getPushManager: () => ({ async sendMessage(message) { calls.push(message); return { errCode: 0 } } })
  }
  const first = event(); assert.equal((await bridge.main(first)).statusCode, 200)
  assert.equal((await bridge.main(first)).statusCode, 409)
  assert.equal((await bridge.main(event())).statusCode, 200)
  assert.equal(calls.length, 1)
  assert.equal(calls[0].force_notification, true)
  assert.deepEqual(calls[0].payload, { type: 'notice', noticeId: 1, categoryId: 1 })
})
