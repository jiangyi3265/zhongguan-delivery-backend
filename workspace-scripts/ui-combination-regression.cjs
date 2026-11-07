// Real browser/API/DB combinations left open by the initial acceptance matrix.
// The single failed upload is aborted; successful responses always come from the server.
const qa = require('./acceptance-context.cjs')()
const { chromium, expect } = require('./playwright-runtime.cjs').test
const assert = require('node:assert/strict'), fs = require('node:fs'), path = require('node:path'), cp = require('node:child_process'), crypto = require('node:crypto')
const MINI = process.env.QICAI_H5_URL, ADMIN = process.env.QICAI_ADMIN_URL
if (process.env.QICAI_ISOLATED_TEST !== '1' || !MINI || !ADMIN) throw Error('Isolated acceptance runner required')
const stamp = Date.now().toString().slice(-8), prefix = 'COMBO' + stamp, pwd = 'Combination123!', results = [], errors = [], network = []
let browser, at
const pages = [], startedAt = new Date().toISOString()
const pass = (name, detail) => { results.push({ name, passed: true, detail }); console.log('PASS ' + name) }
function sql(query) {
  const p = cp.spawnSync('mysql', ['--no-defaults', '--protocol=TCP', '--host=127.0.0.1', '--port=13319', '--user=root', '--default-character-set=utf8mb4', '--batch', '--raw', '--skip-column-names', 'qicai_acceptance'], { input: query, encoding: 'utf8' })
  assert.equal(p.status, 0, p.stderr); return p.stdout.trim()
}
async function req(url, method = 'GET', body, token, admin = false) {
  const r = await fetch(qa.base + url, { method, headers: { 'Content-Type': 'application/json', ...(token ? { [admin ? 'Authorization' : 'X-Qicai-Token']: admin ? 'Bearer ' + token : token } : {}) }, body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(20000) })
  return { http: r.status, ...await r.json() }
}
async function a(url, method, body) { const r = await req('/qicai/admin' + url, method, body, at, true); assert(r.http < 400 && r.data, JSON.stringify(r)); return r.data }
async function m(url, method, body, token) { const r = await req('/qicai/mobile' + url, method, body, token); assert(r.http < 400 && r.data, JSON.stringify(r)); return r.data }
async function reg(index, audience = 'consumer', status = 'ACTIVE') {
  const tag = prefix + (index > 0 ? 'FILTER' : '')
  const body = { audience, phone: '16' + index.toString().padStart(2, '0') + stamp.slice(-7), password: pwd, contactName: tag + '-' + index, address: '隔离组合测试住址' + index, ...(audience === 'business' ? { companyName: tag + '企业' + index, taxId: '91310000' + stamp + index } : {}) }
  const sms = await m('/auth/sms', 'POST', body), customer = await m('/auth/register', 'POST', { ...body, smsCode: sms.devCode })
  if (customer.status !== status) await a('/customers/' + customer.id + '/review', 'PUT', { status, version: customer.version })
  return { body, customer }
}
async function newPage(name, mobile = false) {
  const page = await browser.newPage({ viewport: mobile ? { width: 390, height: 844 } : { width: 1600, height: 1000 } }); page.setDefaultTimeout(12000); pages.push([page, name])
  page.on('pageerror', e => errors.push({ page: name, message: e.message }))
  page.on('console', c => { if (c.type() === 'error' && !c.text().startsWith('Failed to load resource:')) errors.push({ page: name, message: c.text() }) })
  page.on('response', r => { const u = new URL(r.url()); if (/qicai|logout|login|getInfo/.test(u.pathname)) network.push({ page: name, method: r.request().method(), path: u.pathname, status: r.status() }) })
  page.on('requestfailed', r => network.push({ page: name, method: r.method(), path: new URL(r.url()).pathname, failure: r.failure()?.errorText }))
  return page
}
const mf = (page, hint) => page.locator('uni-input,uni-textarea').filter({ hasText: hint }).locator('input,textarea').first()
async function loginMini(page, audience, phone) {
  await page.goto(MINI + '/#/pages/auth/login?audience=' + audience, { waitUntil: 'networkidle' }); await mf(page, '请输入手机号').fill(phone); await mf(page, '请输入密码').fill(pwd)
  await page.locator('uni-button').filter({ hasText: /^登录$/ }).click(); await page.waitForURL(/pages\/(buyer|courier)\/home/); await page.waitForLoadState('networkidle')
}
async function loginAdmin(page) {
  await page.goto(ADMIN + '/login', { waitUntil: 'networkidle' }); await page.getByPlaceholder('账号', { exact: true }).fill('admin'); await page.getByPlaceholder('密码', { exact: true }).fill(qa.password)
  const response = page.waitForResponse(r => new URL(r.url()).pathname.endsWith('/login') && r.request().method() === 'POST'); await page.getByRole('button', { name: '登 录', exact: true }).click()
  const token = (await (await response).json()).token; assert(token); await page.waitForURL(u => !u.pathname.includes('login')); await page.waitForLoadState('networkidle'); return token
}
async function upload(token) {
  const form = new FormData(); form.append('file', new Blob([fs.readFileSync(path.resolve('三端配送小程序/static/logo.png'))], { type: 'image/png' }), 'combo-proof.png')
  const r = await fetch(qa.base + '/qicai/mobile/upload', { method: 'POST', headers: { 'X-Qicai-Token': token }, body: form }); assert.equal(r.status, 201); return (await r.json()).data.fileName
}
async function uploadFailure(role, fixture, buyerToken, driverToken, product, driver, proof) {
  const order = await m('/orders', 'POST', { productId: product.id, quantity: 1, idempotencyKey: prefix + '-' + role, deliveryAddress: fixture.body.address }, buyerToken)
  const task = await a('/deliveries/assign', 'POST', { orderId: order.id, courierId: driver.id, warehouseName: '隔离组合仓库', warehouseAddress: '隔离组合仓库地址', distanceKm: 1 })
  await m('/courier/tasks/' + task.id + '/status', 'PUT', { status: 'PICKED_UP', pickupProofUrls: [proof] }, driverToken); await m('/courier/tasks/' + task.id + '/status', 'PUT', { status: 'IN_DELIVERY' }, driverToken)
  const page = await newPage('partial-upload-' + role, true); await loginMini(page, role === 'customer' ? 'consumer' : 'courier', role === 'customer' ? fixture.body.phone : driver.phone)
  const targetPath = '/qicai/mobile/' + (role === 'customer' ? 'orders/' + order.id : 'courier/tasks/' + task.id)
  const detailReady = page.waitForResponse(r => new URL(r.url()).pathname === targetPath && r.request().method() === 'GET')
  await page.goto(MINI + '/#/pages/' + (role === 'customer' ? 'buyer/receipt?id=' + order.id : 'courier/complete?id=' + task.id), { waitUntil: 'networkidle' })
  async function loaded(response) {
    assert.equal(response.status(), 200); const data = (await response.json()).data
    assert.equal(data.id, role === 'customer' ? order.id : task.id); assert.equal(data.status, 'IN_DELIVERY')
    if (role === 'customer') assert.equal(data.delivery.status, 'IN_DELIVERY')
    await response.finished(); await page.getByText(role === 'customer' ? order.orderNo : task.taskNo, { exact: true }).waitFor(); await page.locator('.photo--add').waitFor(); await page.waitForLoadState('networkidle')
  }
  await loaded(await detailReady)
  const refreshedDetail = page.waitForResponse(r => new URL(r.url()).pathname === targetPath && r.request().method() === 'GET')
  await page.reload({ waitUntil: 'networkidle' }); await loaded(await refreshedDetail)
  const chooser = page.waitForEvent('filechooser'); await page.locator('.photo--add').click(); await (await chooser).setFiles([0, 1, 2].map(i => ({ name: 'proof-' + i + '.png', mimeType: 'image/png', buffer: fs.readFileSync(path.resolve('三端配送小程序/static/logo.png')) })))
  await expect(page.locator('.photos .photo:not(.photo--add)')).toHaveCount(3); await page.locator(role === 'customer' ? '.check-row' : '.confirm-row').click()
  const before = sql(`SELECT status FROM qc_order WHERE id=${order.id}; SELECT status FROM qc_delivery_task WHERE id=${task.id}; SELECT COUNT(*) FROM qc_delivery_attachment WHERE delivery_id=${task.id}; SELECT COUNT(*) FROM qc_delivery_event WHERE delivery_id=${task.id};`)
  let calls = 0; const uploadOutcomes = []
  await page.route('**/qicai/mobile/upload', async route => {
    const index = ++calls
    if (index === 2) { uploadOutcomes.push({ index, aborted: true }); await route.abort('failed'); return }
    const response = await route.fetch(); uploadOutcomes.push({ index, status: response.status() }); await route.fulfill({ response })
  })
  const label = role === 'customer' ? '确认收货并提交' : '提交并完成配送', button = page.locator('uni-button').filter({ hasText: new RegExp('^' + label + '$') })
  await button.click(); await page.getByText('图片上传失败，请检查网络', { exact: true }).waitFor(); await expect.poll(() => uploadOutcomes.length).toBe(3)
  assert.equal(sql(`SELECT status FROM qc_order WHERE id=${order.id}; SELECT status FROM qc_delivery_task WHERE id=${task.id}; SELECT COUNT(*) FROM qc_delivery_attachment WHERE delivery_id=${task.id}; SELECT COUNT(*) FROM qc_delivery_event WHERE delivery_id=${task.id};`), before)
  await expect(page.locator('.photos .photo:not(.photo--add)')).toHaveCount(3); assert.equal(await button.getAttribute('disabled'), null)
  assert.equal(network.filter(n => n.page === 'partial-upload-' + role && ((n.path.endsWith('/receipt') && n.method === 'POST') || (n.path.endsWith('/status') && n.method === 'PUT'))).length, 0)
  assert.deepEqual(uploadOutcomes.filter(x => x.status).map(x => x.status), [201, 201]); await page.screenshot({ path: path.join(qa.out, 'partial-upload-' + role + '-failed.png'), fullPage: true })
  await button.click(); await page.waitForURL(role === 'customer' ? /buyer\/orders/ : /courier\/home/); await expect.poll(() => uploadOutcomes.length).toBe(6)
  const saved = await m('/orders/' + order.id, 'GET', undefined, buyerToken), taskSaved = await m('/courier/tasks/' + task.id, 'GET', undefined, driverToken)
  assert.equal(saved.status, 'COMPLETED'); assert.equal(taskSaved.status, 'DELIVERED'); assert.equal(taskSaved.deliveryProofUrls.length, 3); assert.equal(taskSaved.events.filter(e => e.status === 'DELIVERED').length, 1)
  const fileHash = crypto.createHash('sha256').update(fs.readFileSync(path.resolve('三端配送小程序/static/logo.png'))).digest('hex')
  for (const file of taskSaved.deliveryProofUrls) { const r = await fetch(qa.base + file, { headers: { 'X-Qicai-Token': buyerToken } }); assert.equal(r.status, 200); assert.equal(crypto.createHash('sha256').update(Buffer.from(await r.arrayBuffer())).digest('hex'), fileHash) }
  await page.reload({ waitUntil: 'networkidle' }); pass('F34 ' + role + ' 三图中单图失败不提交业务，保留选择后重试真实签收且事件仅一次', { orderId: order.id, taskId: task.id, uploadOutcomes, finalStatus: saved.status, finalProofs: taskSaved.deliveryProofUrls, deliveredEvents: 1 })
}
async function customerFilters(page) {
  // Every list fixture is registered through real business APIs, not invented rows.
  for (let i = 1; i <= 26; i++) await reg(i)
  for (let i = 27; i <= 30; i++) await reg(i, 'business', ['PENDING', 'ACTIVE', 'REJECTED', 'DISABLED'][i - 27])
  await page.goto(ADMIN + '/qicai/customers', { waitUntil: 'networkidle' })
  const input = page.getByPlaceholder('手机号、联系人、企业名称或税号'), selects = page.locator('.toolbar .el-select')
  async function select(index, label) { await selects.nth(index).click(); await page.getByRole('option', { name: label, exact: true }).click() }
  async function query() { const waiter = page.waitForResponse(r => r.url().includes('/qicai/admin/customers?')); await page.getByRole('button', { name: '查询', exact: true }).click(); return (await (await waiter).json()).data }
  async function verify(data, audience = '', status = '', keyword = prefix + 'FILTER', pageNum = 1) {
    assert.equal(data.pageNum, pageNum)
    const where = [`deleted=0`, `(phone LIKE '%${keyword}%' OR contact_name LIKE '%${keyword}%' OR company_name LIKE '%${keyword}%' OR tax_id LIKE '%${keyword}%')`, ...(audience ? [`audience='${audience}'`] : []), ...(status ? [`status='${status}'`] : [])].join(' AND ')
    const rowsText = sql(`SELECT JSON_OBJECT('id',id,'audience',audience,'phone',phone,'contactName',contact_name,'companyName',company_name,'taxId',tax_id,'address',address,'status',status,'version',version) FROM qc_customer WHERE ${where} ORDER BY id DESC LIMIT ${data.pageSize} OFFSET ${(pageNum - 1) * data.pageSize};`)
    const expected = rowsText ? rowsText.split(/\r?\n/).map(JSON.parse) : []
    assert.equal(data.total, Number(sql(`SELECT COUNT(*) FROM qc_customer WHERE ${where}`))); assert.deepEqual(data.rows.map(r => Object.fromEntries(Object.keys(expected[0] || { id: 0 }).map(key => [key, r[key]]))), expected)
    if (expected.length) { await page.locator('.el-table__body tr').first().getByText(expected[0].phone, { exact: true }).waitFor(); const texts = await page.locator('.el-table__body tr').allTextContents(); assert.equal(texts.length, expected.length); expected.forEach((row, index) => { for (const value of [row.phone, row.contactName, row.companyName, row.taxId, row.address].filter(Boolean)) assert(texts[index].includes(value), value) }) }
    else await page.getByText('暂无客户', { exact: true }).waitFor()
    return { audience, status, keyword, pageNum, total: data.total, ids: expected.map(r => r.id) }
  }
  await input.fill(prefix + 'FILTER'); const first = await query(), details = [await verify(first)]; assert.equal(first.total, 30)
  const secondReady = page.waitForResponse(r => r.url().includes('/qicai/admin/customers?') && new URL(r.url()).searchParams.get('pageNum') === '2'); await page.locator('.el-pagination .btn-next').click(); details.push(await verify((await (await secondReady).json()).data, '', '', prefix + 'FILTER', 2))
  for (const [audience, audienceLabel] of [['business', '企业客户'], ['consumer', '个人客户']]) {
    await select(0, audienceLabel)
    for (const [status, label] of [['PENDING', '待审核'], ['ACTIVE', '已启用'], ['REJECTED', '未通过'], ['DISABLED', '已停用']]) { await select(1, label); details.push(await verify(await query(), audience, status)) }
  }
  await page.screenshot({ path: path.join(qa.out, 'customer-filter-combinations.png'), fullPage: true })
  const resetReady = page.waitForResponse(r => r.url().includes('/qicai/admin/customers?')); await page.getByRole('button', { name: '重置', exact: true }).click(); const reset = (await (await resetReady).json()).data
  assert.equal(reset.pageNum, 1); assert.equal(reset.total, Number(sql('SELECT COUNT(*) FROM qc_customer WHERE deleted=0'))); assert.equal(await input.inputValue(), ''); await page.reload({ waitUntil: 'networkidle' })
  pass('F39 客户身份×全部状态、关键词、两页及重置与API/DB字段逐值一致', { setup: '30 real registered fixtures', details, resetTotal: reset.total })
}
async function couponStates(buyer, bt, product) {
  const create = label => a('/coupons', 'POST', { name: prefix + label, audience: 'consumer', thresholdAmount: 0, discountAmount: 1, validFrom: '2026-01-01 00:00:00', validTo: '2030-12-31 23:59:59', status: 'ACTIVE' })
  const states = []
  for (const [label, status, button] of [['可用', 'AVAILABLE', '已领取'], ['已使用', 'USED', '已使用'], ['未来已领', 'INVALID', '已失效'], ['已到期', 'EXPIRED', '已过期'], ['停用', 'INVALID', '已失效']]) {
    const coupon = await create(label), claim = await m('/coupons/' + coupon.id + '/claim', 'POST', undefined, bt)
    if (label === '未来已领') sql(`UPDATE qc_coupon SET valid_from=DATE_ADD(NOW(),INTERVAL 1 HOUR),valid_to=DATE_ADD(NOW(),INTERVAL 2 HOUR) WHERE id=${coupon.id};`)
    if (label === '已到期') sql(`UPDATE qc_coupon SET valid_to=DATE_SUB(NOW(),INTERVAL 1 SECOND) WHERE id=${coupon.id};`)
    if (label === '停用') await a('/coupons/' + coupon.id, 'PUT', { ...coupon, status: 'INACTIVE' })
    if (label === '已使用') await m('/orders', 'POST', { productId: product.id, quantity: 1, customerCouponId: claim.customerCouponId, idempotencyKey: prefix + '-coupon-used' }, bt)
    states.push({ ...coupon, expectedStatus: status, button })
  }
  const hidden = await create('未来未领'); sql(`UPDATE qc_coupon SET valid_from=DATE_ADD(NOW(),INTERVAL 1 HOUR),valid_to=DATE_ADD(NOW(),INTERVAL 2 HOUR) WHERE id=${hidden.id};`)
  const list = await m('/coupons', 'GET', undefined, bt); assert(!list.some(c => c.couponId === hidden.id))
  states.forEach(c => assert.equal(list.find(row => row.couponId === c.id)?.claimStatus, c.expectedStatus))
  const page = await newPage('coupon-state-ui', true); await loginMini(page, 'consumer', buyer.body.phone); await page.goto(MINI + '/#/pages/buyer/coupons', { waitUntil: 'networkidle' })
  for (const tab of ['已领取', '已失效']) {
    await page.locator('.coupon-tabs').getByText(tab, { exact: true }).click()
    const expected = states.filter(c => tab === '已领取' ? ['AVAILABLE', 'USED'].includes(c.expectedStatus) : ['INVALID', 'EXPIRED'].includes(c.expectedStatus))
    for (const coupon of expected) { const row = page.locator('.coupon').filter({ hasText: coupon.name }); await row.getByText(coupon.button, { exact: true }).waitFor(); assert.notEqual(await row.locator('uni-button').getAttribute('disabled'), null) }
    await page.screenshot({ path: path.join(qa.out, tab === '已领取' ? 'coupons-claimed-used.png' : 'coupons-future-expired-inactive.png'), fullPage: true })
  }
  await page.goto(MINI + '/#/pages/buyer/checkout?id=' + product.id, { waitUntil: 'networkidle' }); await page.locator('.checkout-row--action').click(); await page.locator('.coupon-choices').waitFor()
  const available = states.find(c => c.expectedStatus === 'AVAILABLE'); await page.locator('.coupon-choices').getByText(available.name + '（减¥1.00）', { exact: true }).waitFor()
  for (const coupon of states.filter(c => c.expectedStatus !== 'AVAILABLE')) assert.equal(await page.locator('.coupon-choices').getByText(coupon.name, { exact: false }).count(), 0)
  assert.equal(await page.locator('.coupon-choices').getByText(hidden.name, { exact: false }).count(), 0)
  await page.screenshot({ path: path.join(qa.out, 'checkout-valid-coupon-only.png'), fullPage: true }); pass('F24 已领取/已使用/未来/过期/停用显示及禁用正确，结算仅可选择有效券', { states: states.map(c => ({ couponId: c.id, claimStatus: c.expectedStatus, button: c.button })), hiddenFutureCouponId: hidden.id, selectableCouponId: available.id })
}
;(async () => { try {
  at = (await req('/login', 'POST', { username: 'admin', password: qa.password }, undefined, true)).token; assert(at)
  const buyer = await reg(0), bt = (await m('/auth/login', 'POST', buyer.body)).token
  const driver = await a('/couriers', 'POST', { phone: '167' + stamp, password: pwd, name: prefix + '配送', available: true, status: 'ACTIVE' }), dt = (await m('/courier/login', 'POST', { phone: driver.phone, password: pwd })).token
  const product = await a('/products', 'POST', { sku: prefix, name: prefix, category: '组合验收', unit: '件', stock: 4, businessPrice: 2, consumerPrice: 3, status: 'ACTIVE' }), proof = await upload(dt)
  browser = await chromium.launch({ headless: true, args: ['--no-proxy-server'] })
  await uploadFailure('customer', buyer, bt, dt, product, driver, proof); await uploadFailure('courier', buyer, bt, dt, product, driver, proof)
  await couponStates(buyer, bt, product)
  const admin = await newPage('combination-admin'), uiToken = await loginAdmin(admin); await customerFilters(admin)
  await admin.locator('.avatar-wrapper').hover(); await admin.getByText('退出登录', { exact: true }).click(); await admin.locator('.el-message-box').getByRole('button', { name: '取消', exact: true }).click()
  assert.equal((await req('/getInfo', 'GET', undefined, uiToken, true)).http, 200); assert.equal(network.filter(n => n.page === 'combination-admin' && n.path.endsWith('/logout')).length, 0)
  await admin.locator('.avatar-wrapper').hover(); await admin.getByText('退出登录', { exact: true }).click(); await admin.locator('.el-message-box').getByRole('button', { name: '确定', exact: true }).click(); await admin.waitForURL(/\/login/)
  assert.equal((await req('/getInfo', 'GET', undefined, uiToken, true)).http, 401); await admin.goto(ADMIN + '/qicai/customers', { waitUntil: 'networkidle' }); await admin.waitForURL(/\/login/); assert.equal(await admin.locator('.el-table__body tr').count(), 0)
  await admin.screenshot({ path: path.join(qa.out, 'admin-logout-redirect.png'), fullPage: true }); pass('F37 后台注销取消保留会话，确认后旧JWT401，直接进入受保护页面引导登录', { cancelHttp: 200, logoutOldJwtHttp: 401, redirectedPath: new URL(admin.url()).pathname })
  assert.equal(errors.length, 0, JSON.stringify(errors)); const failures = network.filter(n => n.failure), injected = failures.filter(n => n.method === 'POST' && n.path.endsWith('/upload') && n.failure === 'net::ERR_FAILED'), cancelled = failures.filter(n => n.method === 'GET' && n.failure === 'net::ERR_ABORTED')
  assert.equal(injected.length, 2); assert.equal(failures.length, injected.length + cancelled.length)
  assert(cancelled.every(n => /^\/qicai\/mobile\/(orders|courier\/tasks)\/\d+$/.test(n.path) || n.path === '/prod-api/captchaImage' || /^\/static\/jpg\/login-background-/.test(n.path)))
  pass('浏览器无未处理异常，上传失败仅两次注入，页面导航取消单独记录', { errors: 0, injectedFailedUploads: 2, navigationCancellations: cancelled })
} catch (e) { results.push({ name: 'UI combination validation', passed: false, error: e.stack }); process.exitCode = 1
} finally {
  for (const [page, name] of pages) await page.screenshot({ path: path.join(qa.out, name + '-last.png'), fullPage: true }).catch(() => {})
  if (browser) await browser.close()
  fs.writeFileSync(path.join(qa.out, 'ui-combination-results.json'), JSON.stringify({ startedAt, endedAt: new Date().toISOString(), kind: 'real Chromium/API/MySQL; aborted uploads only', results, errors, network, exitCode: process.exitCode || 0 }, null, 2)); console.log(JSON.stringify(results, null, 2))
} })()
