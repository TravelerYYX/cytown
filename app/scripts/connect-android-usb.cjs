const { execFileSync } = require('node:child_process')
const fs = require('node:fs')
const path = require('node:path')
const { uniqueDevices } = require('./android-dev-tunnel.cjs')

const adb = process.env.ADB_PATH || path.join(process.env.HBUILDERX_HOME || 'D:/Editor/HBuilderX', 'plugins/launcher-tools/tools/adbs/adb.exe')
const root = path.resolve(__dirname, '..')

/** 读取 .env.development 中的键值，注释行自然被忽略。 */
function readEnv(file) {
  const values = {}
  if (!fs.existsSync(file)) return values
  for (const line of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
    const matched = /^\s*([A-Za-z0-9_]+)\s*=\s*(.*?)\s*$/.exec(line)
    if (matched) values[matched[1]] = matched[2].replace(/^["']|["']$/g, '').trim()
  }
  return values
}

function parseUrl(value, name) {
  try {
    return new URL(value)
  } catch {
    throw new Error(`.env.development 中的 ${name} 不是合法地址：${value || '(空)'}`)
  }
}

function run(args) {
  return execFileSync(adb, args, { encoding: 'utf8', windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] }).trim()
}

const env = readEnv(path.join(root, '.env.development'))
const tunnel = parseUrl(env.VITE_ANDROID_DEV_API_BASE_URL, 'VITE_ANDROID_DEV_API_BASE_URL')
const backend = parseUrl(env.VITE_API_BASE_URL || 'http://127.0.0.1:8080', 'VITE_API_BASE_URL')
const tunnelPort = Number(tunnel.port || 80)
const backendPort = Number(backend.port || 80)

;(async () => {
  if (!fs.existsSync(adb)) throw new Error(`未找到 adb：${adb}。请用 ADB_PATH 指定 adb.exe 路径。`)
  if (!['127.0.0.1', 'localhost', '::1'].includes(tunnel.hostname)) {
    throw new Error(
      `VITE_ANDROID_DEV_API_BASE_URL 当前为 ${tunnel.origin}，不是 USB 隧道地址。\n` +
        'USB 真机调试需要 http://127.0.0.1:<端口>，否则手机会走 Wi-Fi 局域网，出现“无法连接服务器”。'
    )
  }

  const devices = run(['devices'])
    .split(/\r?\n/)
    .slice(1)
    .map(line => line.trim().split(/\s+/))
    .filter(parts => parts[1] === 'device')
    .map(parts => parts[0])
  // 同一台设备可能被两条传输通道同时列出（HBuilderX 的 127.0.0.1:16384 与 SDK 的 emulator-5554），
  // 按设备身份去重后仍只有一台时直接使用，避免把“同一台设备”报成“多台设备”。
  const candidates = devices.length > 1 ? uniqueDevices(run, devices) : devices
  const device = process.argv[2] || (candidates.length === 1 ? candidates[0] : '')
  if (!device || !devices.includes(device)) {
    throw new Error(
      devices.length
        ? `检测到多台设备（${candidates.join(', ')}），请指定序列号：npm run connect:usb -- 序列号`
        : '未检测到已授权的 USB 设备。请连接手机、打开 USB 调试并在手机上允许调试。'
    )
  }

  let response
  try {
    response = await fetch(`http://127.0.0.1:${backendPort}/app/home/config`, { signal: AbortSignal.timeout(5000) })
  } catch {
    throw new Error(`电脑 ${backendPort} 端口连不上，后端未启动。请先启动后端再执行本命令。`)
  }
  if (!response.ok || (await response.json()).code !== '0000') {
    throw new Error(`后端 ${backendPort} 端口响应异常（HTTP ${response.status}），请确认服务已就绪。`)
  }

  // 幂等：先清掉同名旧规则，避免残留指向错误端口的转发。
  try {
    run(['-s', device, 'reverse', '--remove', `tcp:${tunnelPort}`])
  } catch {}
  run(['-s', device, 'reverse', `tcp:${tunnelPort}`, `tcp:${backendPort}`])

  const rules = run(['-s', device, 'reverse', '--list'])
  if (!rules.includes(`tcp:${tunnelPort} tcp:${backendPort}`)) {
    throw new Error(`adb reverse 未生效，当前规则：\n${rules || '(空)'}`)
  }

  // 从手机侧实测隧道，确认是手机真的能连上，而不只是电脑侧看到规则存在。
  let deviceCheck = '已建立（未做手机侧实测）'
  try {
    const code = run([
      '-s', device, 'shell',
      `curl -s -m 5 -o /dev/null -w '%{http_code}' http://127.0.0.1:${tunnelPort}/app/home/config`,
    ])
    deviceCheck = code === '200' ? '手机侧实测 HTTP 200' : `手机侧实测 HTTP ${code || '无响应'}`
    if (code !== '200') throw new Error(`手机侧访问 http://127.0.0.1:${tunnelPort} 返回 ${code || '无响应'}`)
  } catch (error) {
    if (error instanceof Error && error.message.startsWith('手机侧访问')) throw error
    deviceCheck = '设备无 curl，跳过手机侧实测'
  }

  console.log(`设备：${device}`)
  console.log(`隧道：手机 http://127.0.0.1:${tunnelPort} -> 电脑 127.0.0.1:${backendPort}`)
  console.log(`状态：${deviceCheck}`)
  console.log('下一步：在 HBuilderX 运行到该手机。USB 拔插或 ADB 重启后需重新执行本命令。')
})().catch(error => {
  console.error(error.message)
  process.exitCode = 1
})
