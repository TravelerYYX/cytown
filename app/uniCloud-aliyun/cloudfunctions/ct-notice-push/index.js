'use strict'
const crypto = require('crypto')
const reply = (statusCode, body) => ({ statusCode, headers: { 'content-type': 'application/json' }, body: JSON.stringify(body) })

// Exported separately so authentication can be exercised without calling a push provider.
function authenticate(event, secret, now = Date.now()) {
  if (!secret || secret.length < 32) throw Error('server_not_configured')
  if (event.httpMethod !== 'POST' || event.isBase64Encoded) throw Error('invalid_request')
  const headers = Object.fromEntries(Object.entries(event.headers || {}).map(([key, value]) => [key.toLowerCase(), value]))
  const time = headers['x-ct-timestamp'], nonce = headers['x-ct-nonce'], signature = headers['x-ct-signature']
  const raw = event.body
  if (typeof raw !== 'string' || raw.length > 8192 || !/^\d{13}$/.test(time || '') || Math.abs(now - Number(time)) > 300000 || !/^[a-zA-Z0-9-]{16,64}$/.test(nonce || '') || !/^[a-f0-9]{64}$/.test(signature || '')) throw Error('invalid_signature')
  const expected = crypto.createHmac('sha256', secret).update(time + '\n' + nonce + '\n' + raw).digest()
  if (!crypto.timingSafeEqual(expected, Buffer.from(signature, 'hex'))) throw Error('invalid_signature')
  const body = JSON.parse(raw)
  if (!/^[a-zA-Z0-9_-]{1,128}$/.test(body.cid || '') || !/^[a-f0-9]{32}$/.test(body.requestId || '') || !body.appId || typeof body.title !== 'string' || typeof body.content !== 'string' || !body.payload || typeof body.payload !== 'object') throw Error('invalid_message')
  return { body, nonce }
}

exports.main = async event => {
  let auth
  try { auth = authenticate(event, process.env.CT_PUSH_SECRET) } catch { return reply(401, { accepted: false, error: 'invalid_request' }) }
  const { body, nonce } = auth
  if (!process.env.CT_PUSH_APP_ID || body.appId !== process.env.CT_PUSH_APP_ID) return reply(400, { accepted: false, error: 'app_mismatch' })
  const db = uniCloud.database(), jobs = db.collection('ct-notice-push-jobs'), nonces = db.collection('ct-notice-push-nonces')
  // _id uniqueness makes replay rejection and provider job reservation atomic across instances.
  try { await nonces.add({ _id: nonce, expiresAt: new Date(Date.now() + 600000) }) }
  catch { return reply(409, { accepted: false, error: 'replayed_request' }) }
  const existing = await jobs.doc(body.requestId).get()
  if (existing.data.length) {
    const job = existing.data[0]
    return reply(job.status === 'accepted' ? 200 : 409, { accepted: job.status === 'accepted', error: job.status === 'accepted' ? undefined : 'request_pending_or_failed' })
  }
  try { await jobs.add({ _id: body.requestId, status: 'sending', createdAt: new Date() }) }
  catch { return reply(409, { accepted: false, error: 'request_in_progress' }) }
  try {
    const push = uniCloud.getPushManager({ appId: body.appId })
    const result = await push.sendMessage({
      push_clientid: body.cid,
      title: Array.from(body.title).slice(0, 19).join(''),
      content: Array.from(body.content).slice(0, 49).join(''),
      payload: body.payload,
      force_notification: true,
      request_id: body.requestId,
      settings: { ttl: 7200000 },
      options: { android: { HW: { '/message/android/notification/channel_id': 'ct-notice' }, XM: { '/extra.channel_id': 'ct-notice' }, OP: { '/channel_id': 'ct-notice' } } }
    })
    if (result.errCode !== 0) {
      // Explicit rejection is safe to retry; an ambiguous timeout remains reserved.
      await jobs.doc(body.requestId).remove()
      return reply(502, { accepted: false, error: 'provider_rejected' })
    }
    await jobs.doc(body.requestId).update({ status: 'accepted', acceptedAt: new Date() })
    return reply(200, { accepted: true })
  } catch {
    await jobs.doc(body.requestId).update({ status: 'uncertain' })
    return reply(502, { accepted: false, error: 'provider_result_uncertain' })
  }
}
exports.authenticate = authenticate
