// Actual production H5 components and Chromium navigation; synthetic read-only
// courier task responses. No database writes, bundle patches, or runtime mocks.
const fs = require('node:fs/promises'), path = require('node:path'), http = require('node:http')
const crypto = require('node:crypto'), assert = require('node:assert/strict')
const { chromium, expect } = require('./playwright-runtime.cjs').test
const root = path.resolve(__dirname, '..'), dist = path.join(root, '三端配送小程序/dist/build/h5')
const out = path.resolve(process.env.QICAI_EVIDENCE_DIR || path.join(root, 'tmp/task-lifecycle'))
const evidence = { startedAt: new Date().toISOString(), kind: 'real Chromium production H5; synthetic read-only task API; no real authentication or database integration', results: [], pageErrors: [], consoleErrors: [], requests: [], bundles: [] }
let browser, page, server, taskReads = 0
function pass(name, extra = {}) { evidence.results.push({ name, passed: true, ...extra }) }
function assertNoRuntimeErrors() { assert.deepEqual(evidence.pageErrors, []); assert.deepEqual(evidence.consoleErrors, []) }

;(async () => {
  await fs.mkdir(out, { recursive: true })
  for (const name of (await fs.readdir(path.join(dist, 'assets'))).filter(name => /^(index-|pages-courier-task\.).*\.js$/.test(name))) {
    evidence.bundles.push({ file: 'assets/' + name, sha256: crypto.createHash('sha256').update(await fs.readFile(path.join(dist, 'assets', name))).digest('hex') })
  }
  assert(evidence.bundles.some(file => file.file.includes('pages-courier-task.')), 'Build H5 before running this regression')
  server = http.createServer(async (request, response) => {
    try {
      const pathname = decodeURIComponent(new URL(request.url, 'http://localhost').pathname)
      let file = path.resolve(dist, '.' + pathname)
      if (file !== dist && !file.startsWith(dist + path.sep)) { response.writeHead(403); response.end(); return }
      try { if ((await fs.stat(file)).isDirectory()) file = path.join(dist, 'index.html') } catch { file = path.join(dist, 'index.html') }
      response.setHeader('Content-Type', file.endsWith('.js') ? 'text/javascript' : file.endsWith('.css') ? 'text/css' : file.endsWith('.png') ? 'image/png' : 'text/html')
      response.end(await fs.readFile(file))
    } catch (error) { response.writeHead(500); response.end('Static test server error') }
  })
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
  const base = 'http://127.0.0.1:' + server.address().port
  browser = await chromium.launch({ headless: true, args: ['--no-proxy-server'] })
  page = await browser.newPage({ viewport: { width: 390, height: 844 } })
  page.on('pageerror', error => evidence.pageErrors.push({ url: page.url(), error: error.stack }))
  page.on('console', message => { if (message.type() === 'error') evidence.consoleErrors.push({ url: page.url(), error: message.text() }) })
  await page.addInitScript(() => localStorage.setItem('qc_courier_token', 'synthetic-lifecycle-token'))
  await page.route('**/qicai/mobile/**', async route => {
    const request = route.request(), pathname = new URL(request.url()).pathname
    if (request.method() !== 'GET' || pathname !== '/qicai/mobile/courier/tasks/7001') {
      evidence.requests.push({ method: request.method(), path: pathname, unexpected: true }); return route.abort('blockedbyclient')
    }
    taskReads++
    evidence.requests.push({ method: request.method(), path: pathname, read: taskReads, courierHeaderPresent: request.headers()['x-qicai-token'] === 'synthetic-lifecycle-token' })
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, data: {
      id: 7001, taskNo: 'synthetic-lifecycle-task', status: 'ASSIGNED', warehouseName: '刷新仓库-' + taskReads,
      warehouseAddress: '合成仓库地址', deliveryAddress: '合成收货地址', contactName: '合成收货人', contactPhone: '13000000000',
      pickupProofUrls: [], deliveryProofUrls: [], items: [{ sku: 'synthetic', productName: '合成物资', quantity: 1, unit: '件' }]
    } }) })
  })
  await page.goto(base + '/#/pages/courier/task?id=7001', { waitUntil: 'networkidle' })
  await expect(page.locator('.task-page .dark-route')).toContainText('刷新仓库-1')
  const textarea = page.locator('.task-page .exception-panel textarea')
  await textarea.fill('保留异常说明草稿')
  await expect(page.locator('.task-page uni-resize-sensor')).toHaveCount(1)
  await expect(page.locator('.task-page uni-image')).toHaveCount(0)
  assertNoRuntimeErrors()
  pass('TLC-001 无照片或凭证的真实任务页挂载异常说明 textarea')
  for (let round = 1; round <= 3; round++) {
    const previousReads = taskReads
    await page.getByText('前往取货', { exact: true }).click()
    await page.locator('.check-title').waitFor()
    await expect(page.locator('.check-page .warehouse')).toContainText('刷新仓库-' + (previousReads + 1))
    await page.locator('.check-page .header__back').click()
    await expect(page.locator('.task-page .dark-route')).toContainText('刷新仓库-' + (previousReads + 2))
    await expect(textarea).toHaveValue('保留异常说明草稿')
    await page.waitForLoadState('networkidle')
    assert.equal(taskReads, previousReads + 2, 'Returning must reload fresh task data once')
    assertNoRuntimeErrors()
    pass('TLC-002 取货页返回任务页第 ' + round + ' 次刷新新数据、保留输入且无生命周期异常')
  }
  assert(evidence.requests.every(request => !request.unexpected && request.courierHeaderPresent))
  pass('TLC-003 所有请求均为预期只读任务请求且携带配送角色 header')
})().catch(error => { evidence.results.push({ name: 'Task lifecycle regression', passed: false, error: error.stack }); process.exitCode = 1 })
.finally(async () => {
  if (page) await page.screenshot({ path: path.join(out, 'task-lifecycle.png'), fullPage: true }).catch(error => evidence.screenshotError = error.message)
  if (browser) await browser.close()
  if (server) await new Promise(resolve => server.close(resolve))
  evidence.endedAt = new Date().toISOString()
  await fs.mkdir(out, { recursive: true })
  await fs.writeFile(path.join(out, 'task-lifecycle-browser-results.json'), JSON.stringify(evidence, null, 2))
  console.log(JSON.stringify({ results: evidence.results, pageErrors: evidence.pageErrors.length, consoleErrors: evidence.consoleErrors.length }, null, 2))
})
