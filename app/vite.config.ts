import { defineConfig, loadEnv } from 'vite'
declare const process: { env: Record<string, string | undefined> }
declare const __dirname: string
import { createRequire } from 'node:module'
import { writeFileSync, readFileSync } from 'node:fs'
// Use the compiler that ships with the selected HBuilderX, including Vapor's
// CSS/template compiler. Resolving the old project-local plugin mixes releases.
const compilerRoot = process.env.UNI_CLI_CONTEXT
if (!compilerRoot) throw new Error('请通过 HBuilderX 5.26 或 scripts/uniapp-x.cjs 构建')
const compilerRequire = createRequire(compilerRoot + '/package.json')
const uni = compilerRequire('@dcloudio/vite-plugin-uni').default

export default defineConfig(({ mode, command }) => {
  // HBuilderX 启动 uniapp-cli-vite 时进程工作目录不是项目根目录，
  // 因此不能用 process.cwd() 定位 .env；__dirname 在 vite 配置打包时
  // 被注入为“原始配置文件所在目录”（即项目根目录），与 cwd 无关。
  const projectRoot = __dirname
  const env = loadEnv(mode, projectRoot, 'VITE_')
  // Native development uses Vite's build/watch command, rather than serve.
  if(mode==='development'&&process.env.UNI_PLATFORM==='app'&&[process.env.UNI_APP_PLATFORM,process.env.UNI_UTS_PLATFORM].some(value=>value==='android'||value==='app-android')){
    const projectRequire=createRequire(projectRoot+'/package.json')
    try{
      const result=projectRequire('./scripts/android-dev-tunnel.cjs').ensureAndroidDevTunnel({hxHome:process.env.HX_APP_ROOT||process.env.HBUILDERX_HOME||'D:/Editor/HBuilderX',environment:{...env,...process.env},deviceId:process.env.CT_ANDROID_DEVICE_SERIAL})
      if(result.status==='connected')console.info('[android-dev-tunnel]',result.device,`tcp:${result.sourcePort} -> tcp:${result.backendPort}`)
      else console.warn('[android-dev-tunnel]',result.status,'可手动执行 npm run connect:usb -- 设备序列号')
    }catch(error){console.warn('[android-dev-tunnel]',String(error))}
  }
  // 定位接口密钥（非 VITE_ 前缀，不会被打进前端包）：由开发代理在服务端注入。
  const locateKey = (loadEnv(mode, projectRoot, '').IP_LOCATE_KEY || '').trim()
  const apiUrl = process.env.VITE_API_BASE_URL || env.VITE_API_BASE_URL
  if (!apiUrl || !/^https?:\/\//.test(apiUrl)) throw new Error(`必须配置有效的 VITE_API_BASE_URL（以 http:// 或 https:// 开头，请在项目根目录 .env.${mode} 中配置）`)
  // 发行包（App / 小程序 / H5 生产）不允许明文接口地址：Android 9+ 默认禁止 cleartext，
  // 小程序上架也要求 HTTPS。局域网 http 请写在 .env.development，用 --mode development 构建。
  if (mode === 'production' && !apiUrl.startsWith('https://')) throw new Error(`生产 API 地址必须使用 HTTPS（当前 mode=${mode} 解析到 ${apiUrl}，请检查项目根目录 .env.${mode}）`)
  return {
    base: process.env.CT_POST_PREVIEW_BUILD === '1' ? '/post-preview/' : '/',
    plugins: [uni(), {
      name: 'ct-chat-worker',
      configureServer(server: any) {
        server.middlewares.use('/chat-cache-worker.js', (_req: any, res: any) => {
          res.setHeader('Content-Type', 'application/javascript');res.setHeader('Cache-Control','no-cache');res.end(readFileSync(projectRoot+'/static/chat-cache-worker.js','utf8'))
        })
      },
      generateBundle() {
        if(process.env.UNI_PLATFORM==='h5')this.emitFile({type:'asset',fileName:'chat-cache-worker.js',source:readFileSync(projectRoot+'/static/chat-cache-worker.js','utf8')})
      }
    }, {
      name: 'ct-build-error-trace',
      configResolved(config: any) {
        if (!process.env.CT_COMPILE_TRACE_FILE) return
        for (const plugin of config.plugins) {
          if (!plugin.transform) continue
          const original = typeof plugin.transform === 'function' ? plugin.transform : plugin.transform.handler
          const traced = async function(this: unknown, code: string, id: string, ...args: unknown[]) {
            try { return await original.call(this, code, id, ...args) }
            catch (error) { writeFileSync(process.env.CT_COMPILE_TRACE_FILE!, '// ' + plugin.name + '\n// ' + id + '\n' + code); throw error }
          }
          if (typeof plugin.transform === 'function') plugin.transform = traced
          else plugin.transform.handler = traced
        }
      },
      buildEnd(error: Error | undefined) { if (error) console.error(error.stack || error.message) }
    }],
    envDir: projectRoot,
    server: {
      proxy: {
        // IP 定位接口：H5 端走同源代理，避免浏览器跨域被服务端拦截；
        // 密钥在代理层注入（前端不接触），并透传访客真实 IP 供服务端定位访问者。
        '/api/locate': {
          target: 'https://ip.moonya.cn',
          changeOrigin: true,
          configure(proxy) {
            proxy.on('proxyReq', (proxyReq: { setHeader: (name: string, value: string) => void }, req: { headers?: Record<string, string | string[] | undefined>; socket?: { remoteAddress?: string } }) => {
              if (locateKey) proxyReq.setHeader('X-API-Key', locateKey)
              // 仅透传公网客户端 IP；localhost/内网访问时不传 X-Forwarded-For，
              // 让接口按代理连接的真实出口 IP 定位（开发机与浏览器同一出口，结果即用户所在地），
              // 否则回环/私网地址会被接口判为“内网/未知”。
              const forwarded = req.headers?.['x-forwarded-for']?.toString().split(',')[0]?.trim()
              const remote = forwarded || req.socket?.remoteAddress || ''
              const ip = remote.replace(/^::ffff:/, '')
              const isPrivate = /^(::1|127\.|10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.|fc|fe80)/.test(ip)
              if (ip && !isPrivate) {
                proxyReq.setHeader('X-Forwarded-For', ip)
                proxyReq.setHeader('X-Real-IP', ip)
              }
            })
          }
        }
      }
    }
  }
})
