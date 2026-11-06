import { readdir, readFile } from 'node:fs/promises'
import { resolve, relative, extname, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const sourceDirs = ['components', 'composables', 'config', 'mock', 'pages', 'types', 'utils']
const imageOwners = new Set(['components/AppIcon.uvue', 'components/HomeBanner.uvue', 'components/CustomTabBar.uvue'])
const failures = []
let checked = 0
async function scan(path) {
  for (const entry of await readdir(path, { withFileTypes: true })) {
    const full = resolve(path, entry.name)
    if (entry.isDirectory()) await scan(full)
    else if (['.uvue', '.ts', '.scss'].includes(extname(full))) await check(full)
  }
}
async function check(path) {
  const name = relative(root, path).replaceAll('\\', '/')
  const text = await readFile(path, 'utf8')
  checked++
  if (/(?:linear|radial|conic)-gradient\s*\(/i.test(text)) failures.push(name + ': gradient found')
  if (/\p{Extended_Pictographic}|[\u2600-\u27bf\ufe0f]/u.test(text)) failures.push(name + ': pictographic character found')
  if (/<(?:image|img|video)\b/i.test(text) && !imageOwners.has(name)) failures.push(name + ': media element outside the approved UI/background components')
  if (/url\s*\(/i.test(text)) failures.push(name + ': unexpected CSS image reference')
}
for (const dir of sourceDirs) await scan(resolve(root, dir))
await check(resolve(root, 'App.uvue'))
await check(resolve(root, 'uni.scss'))
const mediaTypes = await readFile(resolve(root, 'types/home.uts'), 'utf8')
const spec = mediaTypes.match(/interface MediaSkeletonSpec\s*\{([^}]+)\}/)?.[1] || ''
if (!spec || /\b(?:src|background|placeholder)\s*\??:/i.test(spec)) failures.push('MediaSkeletonSpec must not accept arbitrary backgrounds; P1 url is transport metadata only')
if (failures.length) { console.error(failures.join('\n')); process.exitCode = 1 }
else console.log('UI source audit passed: ' + checked + ' files; no gradients, pictographs, or unapproved content media.')
