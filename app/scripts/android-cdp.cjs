// Read-only inspection of the project's HBuilder debug WebViews.
const WebSocket = require('ws')
async function connect(title = 'pages/index') {
  const endpoint = process.env.ANDROID_CDP_URL || 'http://127.0.0.1:9224'
  const pages = await (await fetch(endpoint + '/json/list')).json()
  const target = pages.find(page => page.title.includes(title))
  if (!target) throw new Error('Project WebView not found: ' + title)
  const socket = new WebSocket(target.webSocketDebuggerUrl)
  await new Promise((resolve, reject) => { socket.once('open', resolve); socket.once('error', reject) })
  let sequence = 0
  const callbacks = new Map()
  socket.on('message', data => { const message = JSON.parse(data); const callback = callbacks.get(message.id); if (callback) { callbacks.delete(message.id); clearTimeout(callback.timer); message.error ? callback.reject(new Error(message.error.message)) : callback.resolve(message.result) } })
  socket.on('close', () => { for (const callback of callbacks.values()) { clearTimeout(callback.timer); callback.reject(new Error('Android WebView disconnected')) }; callbacks.clear() })
  const call = (method, params = {}) => new Promise((resolve, reject) => {
    const id = ++sequence
    const timer = setTimeout(() => { callbacks.delete(id); reject(new Error('Android CDP timeout: ' + method)) }, 10000)
    callbacks.set(id, { resolve, reject, timer }); socket.send(JSON.stringify({ id, method, params }))
  })
  return { call, close: () => socket.close(), evaluate: async expression => {
    const result = await call('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true })
    if (result.exceptionDetails) throw new Error(result.exceptionDetails.text + ': ' + result.result.description)
    return result.result.value
  } }
}
module.exports = { connect }
if (require.main === module) (async () => {
  const client = await connect()
  try { console.log(JSON.stringify(await client.evaluate(`({icons:document.querySelectorAll('.interaction-icon').length,body:document.body.innerText.slice(0,160),storageKeys:Array.from({length:plus.storage.getLength()},(_,i)=>plus.storage.key(i)).filter(x=>x.includes('app_'))})`))) }
  finally { client.close() }
})().catch(error => { console.error(error.message); process.exit(1) })
