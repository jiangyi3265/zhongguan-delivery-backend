// Real H5/Chromium + isolated HTTP/MySQL. The browser clock deliberately keeps
// uni-input's 100ms v-model throttle pending while real input and submit occur.
const qa = require('./acceptance-context.cjs')()
const { chromium, test: { expect } } = require('./playwright-runtime.cjs')
const assert = require('node:assert/strict')
const fs = require('node:fs/promises')
const path = require('node:path')
const base = process.env.QICAI_H5_URL || 'http://127.0.0.1:25187'
const results = [], requests = [], errors = [], failures = [], pending = []
let browser, currentCase = ''
function isCatalog(r) { return new URL(r.url()).pathname === '/qicai/mobile/catalog/products' && r.request().method() === 'GET' }
async function query(page, action) {
  const response = page.waitForResponse(isCatalog)
  await action()
  const r = await response
  await r.finished()
  assert.equal(r.status(), 200)
  const body = await r.json()
  assert.ok(Array.isArray(body.data?.rows), 'explicit pagination must contain persisted product rows')
  assert.equal(body.data.pageNum, Number(new URL(r.url()).searchParams.get('pageNum')))
  assert.equal(body.data.pageSize, Number(new URL(r.url()).searchParams.get('pageSize')))
  assert.ok(Number.isInteger(body.data.total) && body.data.total >= body.data.rows.length)
  return { params: new URL(r.url()).searchParams, rows: body.data.rows }
}
async function visibleRows(page, rows) {
  await expect.poll(() => page.locator('.catalog-product').count()).toBe(rows.length)
  for (const row of rows) await expect(page.locator('.catalog-product').filter({ hasText: 'SKU：' + row.sku })).toHaveCount(1)
}
async function scenario(name, run, freeze = true) {
  currentCase = name
  const page = await browser.newPage({ viewport: { width: 390, height: 844 } })
  page.setDefaultTimeout(8000)
  page.on('pageerror', e => errors.push({ name, message: e.message }))
  page.on('console', m => { if (m.type() === 'error') errors.push({ name, message: m.text() }) })
  page.on('requestfailed', r => failures.push({ name, path: new URL(r.url()).pathname, failure: r.failure()?.errorText }))
  page.on('response', r => { if (isCatalog(r)) pending.push((async () => { await r.finished(); const d = await r.json(); requests.push({ at: new Date().toISOString(), name, query: new URL(r.url()).search, status: r.status(), rows: d.data?.rows?.length, total: d.data?.total, skus: d.data?.rows?.map(x => x.sku) }) })()) })
  try {
    const initial = await query(page, () => page.goto(base + '/#/', { waitUntil: 'networkidle' }))
    assert.ok(initial.rows.length >= 2, 'isolated catalog seed requires at least two products')
    await visibleRows(page, initial.rows)
    if (freeze) {
      const start = new Date('2026-10-17T12:00:00Z')
      await page.clock.install({ time: start })
      await page.clock.pauseAt(new Date(start.getTime() + 1000))
    }
    await run(page, initial.rows)
    if (freeze) await page.clock.resume()
    results.push({ name, passed: true, kind: freeze ? 'controlled 100ms input-throttle window; real browser input and HTTP/DB' : 'natural browser clock; real input and HTTP/DB' })
    console.log('PASS ' + name)
  } catch (error) {
    results.push({ name, passed: false, error: error.message })
    await page.screenshot({ path: path.join(qa.out, 'search-failure-' + results.length + '.png'), fullPage: true, timeout: 3000 }).catch(() => {})
    console.error('FAIL ' + name + ': ' + error.message)
  } finally { await page.close() }
}
const input = page => page.locator('.catalog-search input')
const search = page => page.locator('.catalog-search uni-button').click({ force: true })
async function clearSearch(page, initial) {
  await input(page).fill('__SEARCH_REGRESSION_NO_MATCH__')
  const missing = await query(page, () => search(page))
  assert.equal(missing.params.get('keyword'), '__SEARCH_REGRESSION_NO_MATCH__')
  assert.equal(missing.rows.length, 0)
  await visibleRows(page, [])
  await input(page).fill('')
  const restored = await query(page, () => search(page))
  assert.equal(restored.params.get('keyword') || '', '', 'cleared visible input must submit an empty keyword immediately')
  assert.deepEqual(restored.rows.map(x => x.id), initial.map(x => x.id))
  await visibleRows(page, restored.rows)
}
;(async () => {
  browser = await chromium.launch({ headless: true, args: ['--no-proxy-server'] })
  await scenario('clear input immediately submits empty keyword and restores persisted products', clearSearch)
  await scenario('rapid replacement then search submits latest visible SKU', async (page, rows) => {
    await input(page).fill(rows[0].sku)
    await input(page).fill(rows[1].sku)
    const found = await query(page, () => search(page))
    assert.equal(found.params.get('keyword'), rows[1].sku)
    assert.ok(found.rows.some(x => x.id === rows[1].id))
    await visibleRows(page, found.rows)
  })
  await scenario('category click commits latest input and submits the selected category', async (page, rows) => {
    await input(page).fill('__SEARCH_REGRESSION_NO_MATCH__')
    await input(page).fill('')
    const category = rows[0].category
    const found = await query(page, () => page.locator('.catalog-categories uni-button').filter({ hasText: new RegExp('^' + category + '$') }).click({ force: true }))
    assert.equal(found.params.get('keyword') || '', '')
    assert.equal(found.params.get('category'), category)
    assert.ok(found.rows.length && found.rows.every(x => x.category === category))
    await visibleRows(page, found.rows)
  })
  await scenario('Enter confirm uses current event value before deferred model update', async (page, rows) => {
    await input(page).fill(rows[0].sku)
    await input(page).fill(rows[1].sku)
    const found = await query(page, () => input(page).press('Enter'))
    assert.equal(found.params.get('keyword'), rows[1].sku)
    assert.ok(found.rows.some(x => x.id === rows[1].id))
    await visibleRows(page, found.rows)
  })
  await scenario('natural clock no-delay clear/search still restores real products', clearSearch, false)
  await Promise.all(pending)
  results.push({ name: 'all search scenarios have zero runtime and network errors', passed: errors.length === 0 && failures.length === 0 })
})().catch(error => { results.push({ name: 'search regression interrupted', passed: false, error: error.stack }) }).finally(async () => {
  await browser?.close()
  await fs.writeFile(path.join(qa.out, 'search-input-browser-results.json'), JSON.stringify({ at: new Date().toISOString(), kind: 'real Chromium H5 + isolated API/MySQL; controlled input-throttle timing and natural clock; no response mocks; not native/phone', results, requests, errors, failures }, null, 2))
  if (!results.length || results.some(r => !r.passed) || errors.length || failures.length) process.exitCode = 1
})
