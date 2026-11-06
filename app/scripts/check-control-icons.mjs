import { readdir, readFile } from 'node:fs/promises'
import { resolve, extname, relative } from 'node:path'

const appRoot = resolve(import.meta.dirname, '..')
const adminRoot = resolve(appRoot, '../ciyuantoen-back/ciyuantown-admin/src')
const roots = [
  ...['components', 'pages'].map(dir => resolve(appRoot, dir)),
  resolve(adminRoot, 'components'),
  resolve(adminRoot, 'layouts'),
  resolve(adminRoot, 'views')
]
const pictograph = /\p{Extended_Pictographic}|[\u2600-\u27bf\ufe0f]/gu
const failures = []
const iconFiles = new Set(await readdir(resolve(appRoot, 'static/icons')))

async function scan(dir) {
  for (const entry of await readdir(dir, { withFileTypes: true })) {
    const path = resolve(dir, entry.name)
    if (entry.isDirectory()) await scan(path)
    else if (['.uvue', '.ts', '.tsx'].includes(extname(path))) {
      const source = await readFile(path, 'utf8')
      const symbols = source.match(pictograph)?.filter(symbol => symbol !== '©') || []
      if (symbols.length) failures.push(relative(resolve(appRoot, '..'), path).replaceAll('\\', '/'))
      for (const [tag] of source.matchAll(/<AppIcon\b[^>]*>/g)) {
        if (tag.includes(':name=')) continue
        const name = tag.match(/\bname="([^"]+)"/)?.[1]
        if (!name || tag.includes(':tone=')) continue
        const tone = tag.match(/\btone="([^"]+)"/)?.[1] || 'dark'
        if (!iconFiles.has(`${name}-${tone}.svg`)) {
          failures.push(`${relative(resolve(appRoot, '..'), path).replaceAll('\\', '/')}: missing static/icons/${name}-${tone}.svg`)
        }
      }
    }
  }
}

for (const root of roots) await scan(root)
if (failures.length) {
  console.error('以下界面源码使用了 Unicode 图形字符，请改用 SVG/Icon 组件：\n' + failures.join('\n'))
  process.exitCode = 1
} else console.log('Control icon audit passed: UI controls use SVG/Icon components.')
