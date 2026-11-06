// Checks the actual App view/service bridge without changing account data.
const { connect } = require('./android-cdp.cjs')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const output = path.resolve(__dirname, '../artifacts/refresh-ui')
const pause = ms => new Promise(resolve => setTimeout(resolve, ms))
async function waitFor(read, predicate) {
  for (let attempt = 0; attempt < 100; attempt++) {
    const value = await read()
    if (predicate(value)) return value
    await pause(100)
  }
  throw new Error('Android refresh state did not settle')
}
async function screenshot(client, name) {
  const { data } = await client.call('Page.captureScreenshot', { format: 'png' })
  fs.writeFileSync(path.join(output, name + '.png'), Buffer.from(data, 'base64'))
}
async function pull(client, distance, inspect) {
  const size = await client.evaluate('({width:innerWidth,height:innerHeight})')
  const x = size.width / 2, y = Math.min(size.height * .4, size.height - distance - 20)
  await client.call('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x, y }] })
  try {
    for (let step = 1; step <= 12; step++) {
      await client.call('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x, y: y + distance * step / 12 }] })
      await pause(25)
    }
    if (inspect) await inspect()
  } finally { await client.call('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] }) }
}
;(async () => {
  fs.mkdirSync(output, { recursive: true })
  const main = await connect()
  const original = await main.evaluate('document.querySelector(".tab-item.selected")?.getAttribute("aria-label")')
  async function tab(label, screen) {
    await main.evaluate(`document.querySelector('.tab-item[aria-label="${label}"]').click()`)
    await waitFor(() => main.evaluate(`!!document.querySelector('${screen}')?.getBoundingClientRect().height`), Boolean)
    await pause(350)
  }
  try {
    assert(await main.evaluate('!!document.querySelector(".home-view .custom-refresher")'), 'Run the updated App build before checking refresh')
    for (const [label, screen, name] of [['关注', '.follow-view', 'follow'], ['消息', '.message-view', 'message'], ['我的', '.mine-view', 'mine']]) {
      await tab(label, screen)
      const read = () => main.evaluate(`({indicators:document.querySelectorAll('${screen} .refresh-indicator').length,native:document.querySelectorAll('${screen} .uni-scroll-view-refresher').length})`)
      assert.deepEqual(await read(), { indicators: 0, native: 0 }, name + ': disabled refresh added content')
      await pull(main, 80)
      assert.deepEqual(await read(), { indicators: 0, native: 0 }, name + ': pull mounted a disabled refresher')
      await screenshot(main, 'android-' + name + '-fixed')
    }
    await tab('首页', '.home-view')
    const readHome = () => main.evaluate(`(()=>{const area=document.querySelector('.home-view .feed-card > .refresh-indicator'),tabs=document.querySelector('.home-view .channel-tabs'),a=area.getBoundingClientRect(),t=tabs.getBoundingClientRect();return{height:a.height,top:a.top,tabsBottom:t.bottom,images:area.querySelectorAll('.refresh-character').length}})()`)
    assert.equal((await readHome()).height, 0)
    await pull(main, 60, async () => {
      const state = await waitFor(readHome, state => state.height > 0)
      assert(Math.abs(state.top - state.tabsBottom) < 2, 'App refresh is not below homepage channels')
      assert.equal(state.images, 1)
      await screenshot(main, 'android-home-pulling')
    })
    await waitFor(readHome, state => state.height < 1 && state.images === 0)
    await pull(main, 200)
    await waitFor(readHome, state => state.height < 1 && state.images === 0)
    // Open the real follower page through its existing navigation button.
    await tab('我的', '.mine-view')
    await main.evaluate('document.querySelector(".mine-view .stats button, .mine-view .stats uni-button").click()')
    let relationship
    try {
      await waitFor(async () => {
        try { relationship = await connect('pages/relationships'); return true } catch { return false }
      }, Boolean)
      const readRelationship = () => relationship.evaluate(`(()=>{const host=document.querySelector('.relationship-scroll'),area=host?.querySelector('.refresh-indicator'),native=host?.querySelector('.uni-scroll-view-refresher');return area?{height:area.getBoundingClientRect().height,images:area.querySelectorAll('.refresh-character').length,nativePosition:native&&getComputedStyle(native).position}:null})()`)
      await waitFor(readRelationship, state => state && state.height < 1)
      await pull(relationship, 60, async () => {
        const state = await waitFor(readRelationship, state => state.height > 0)
        assert.equal(state.images, 1)
        assert.equal(state.nativePosition, 'absolute', 'Native and custom refresh areas both take up space')
        await screenshot(relationship, 'android-followers-pulling')
      })
      await waitFor(readRelationship, state => state.height < 1 && state.images === 0)
      await pull(relationship, 200)
      await waitFor(readRelationship, state => state.height < 1 && state.images === 0)
      await relationship.evaluate('document.querySelector(".relationship-nav button, .relationship-nav uni-button").click()')
    } finally { relationship?.close() }
    console.log('Android refresh passed: no refresher on follow/message/mine; homepage and followers pull, cancel and completion collapse through the real App bridge.')
  } finally {
    if (original) await main.evaluate(`document.querySelector('.tab-item[aria-label="${original}"]')?.click()`)
    main.close()
  }
})().catch(error => { console.error(error); process.exitCode = 1 })
