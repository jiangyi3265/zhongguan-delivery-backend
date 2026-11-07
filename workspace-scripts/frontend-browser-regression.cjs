// Real browser + real isolated backend. Only response loss/delay is injected;
// no successful API response is mocked. Run after the main UI acceptance.
const qa = require('./acceptance-context.cjs')()
const { chromium, expect } = require('./playwright-runtime.cjs').test
const fs = require('node:fs/promises'), path = require('node:path'), assert = require('node:assert/strict')
const MINI = process.env.QICAI_H5_URL || 'http://127.0.0.1:25187', ADMIN = process.env.QICAI_ADMIN_URL || 'http://127.0.0.1:25186'
const stamp = String(Date.now()).slice(-8), results = [], errors = [], requests = [], orders = [], tasks = []
const password = 'QcTest123!'
let browser, adminToken, buyerToken, courierToken, buyer, courier, product, coupon, originalProfile, passwordChanged = false
const pages = []
async function api(url, method = 'GET', body, token = adminToken, mobile = false) {
  const response = await fetch(qa.base + url, { method, headers: { 'Content-Type': 'application/json', ...(token ? { [mobile ? 'X-Qicai-Token' : 'Authorization']: mobile ? token : 'Bearer ' + token } : {}) }, body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(15000) })
  const data = await response.json(); assert(response.ok && Number(data.code) === 200, `${method} ${url}: HTTP ${response.status}, ${data.msg}`); return data
}
const adminApi = (url, method, data) => api('/qicai/admin' + url, method, data)
const mobileApi = (url, method, data, token = buyerToken) => api('/qicai/mobile' + url, method, data, token, true)
const mf = (page, placeholder) => page.locator('uni-input,uni-textarea').filter({ hasText: placeholder }).locator('input,textarea').first()
const field = (page, label) => page.locator('.el-form-item').filter({ has: page.locator('.el-form-item__label').getByText(label, { exact: true }) }).locator('input').first()
const pass = name => { results.push({ name, passed: true }); console.log('PASS ' + name) }
async function newPage(name, mobile = false) {
  const page = await browser.newPage({ viewport: mobile ? { width: 390, height: 844 } : { width: 1440, height: 960 } })
  pages.push([name, page]); page.setDefaultTimeout(15000)
  page.on('pageerror', error => errors.push({ page: name, message: error.message }))
  page.on('console', message => { if (message.type() === 'error' && !message.text().startsWith('Failed to load resource:')) errors.push({ page: name, message: message.text() }) })
  page.on('response', response => { const url = new URL(response.url()); if (/qicai|profile/.test(url.pathname)) requests.push({ page: name, method: response.request().method(), path: url.pathname, status: response.status() }) })
  return page
}
async function loginAdmin(page, pwd = qa.password) {
  await page.goto(ADMIN + '/login', { waitUntil: 'networkidle' })
  await page.getByPlaceholder('账号', { exact: true }).fill('admin'); await page.getByPlaceholder('密码', { exact: true }).fill(pwd)
  await page.getByRole('button', { name: '登 录', exact: true }).click(); await page.waitForURL(url => !url.pathname.includes('login'))
}
async function loginMini(page, audience, phone) {
  await page.goto(MINI + '/#/pages/auth/login?audience=' + audience, { waitUntil: 'networkidle' })
  await mf(page, '请输入手机号').fill(phone); await mf(page, '请输入密码').fill(password)
  await page.locator('uni-button').filter({ hasText: /^登录$/ }).click(); await page.waitForURL(/pages\/(buyer|courier)\/home/); await page.waitForLoadState('networkidle')
}
async function createOrder() {
  const order = (await mobileApi('/orders', 'POST', { productId: product.id, quantity: 1, deliveryAddress: '隔离回归收货路1号', idempotencyKey: 'frontend-' + stamp + '-' + orders.length })).data
  orders.push(order); return order
}
async function assign(order) {
  const task = (await adminApi('/deliveries/assign', 'POST', { orderId: order.id, courierId: courier.id, warehouseName: '隔离回归仓库', warehouseAddress: '隔离回归仓库路1号', distanceKm: 1 })).data
  tasks.push(task); return task
}
async function upload(token) {
  const form = new FormData(); form.append('file', new Blob([await fs.readFile(path.resolve('三端配送小程序/static/logo.png'))], { type: 'image/png' }), 'proof.png')
  const response = await fetch(qa.base + '/qicai/mobile/upload', { method: 'POST', headers: { 'X-Qicai-Token': token }, body: form })
  assert.equal(response.status, 201); return (await response.json()).data.fileName
}
async function transition(task, status, extra = {}) { return mobileApi('/courier/tasks/' + task.id + '/status', 'PUT', { status, ...extra }, courierToken) }

;(async () => {
  adminToken = (await api('/login', 'POST', { username: 'admin', password: qa.password, code: '', uuid: '' }, null)).token
  const phone = '174' + stamp, devCode = (await mobileApi('/auth/sms', 'POST', { audience: 'consumer', phone }, null)).data.devCode
  buyer = (await mobileApi('/auth/register', 'POST', { audience: 'consumer', phone, password, contactName: '前端回归', address: '隔离回归收货路1号', smsCode: devCode }, null)).data
  buyerToken = (await mobileApi('/auth/login', 'POST', { audience: 'consumer', phone, password }, null)).data.token
  courier = (await adminApi('/couriers', 'POST', { phone: '173' + stamp, name: '前端回归配送' + stamp, password, vehicleNo: '验QA', available: true, status: 'ACTIVE' })).data
  courierToken = (await mobileApi('/courier/login', 'POST', { phone: courier.phone, password }, null)).data.token
  product = (await adminApi('/products', 'POST', { sku: 'FE-' + stamp, name: '前端回归商品' + stamp, category: '食品采购', unit: '件', stock: 130, businessPrice: 1, consumerPrice: 2, status: 'ACTIVE' })).data
  for (let index = 0; index < 103; index++) await createOrder()
  const incoming = await assign(orders[0]), picked = await assign(orders[1]), assigned = await assign(orders[2])
  const proof = await upload(courierToken)
  await transition(incoming, 'PICKED_UP', { pickupProofUrls: [proof] }); await transition(incoming, 'IN_DELIVERY')
  await transition(picked, 'PICKED_UP', { pickupProofUrls: [proof] })
  browser = await chromium.launch({ headless: true, args: ['--no-proxy-server'] })
  const customerPage = await newPage('frontend-customer', true), driverPage = await newPage('frontend-driver', true), adminPage = await newPage('frontend-admin')
  await loginMini(customerPage, 'consumer', phone)
  await expect(customerPage.locator('.summary__value')).toHaveText(['1', '3', '103', '0'])
  await customerPage.reload({ waitUntil: 'networkidle' }); await expect(customerPage.locator('.summary__value')).toHaveText(['1', '3', '103', '0'])
  pass('FUI-001 103 笔真实订单首页统计及刷新与服务端状态一致')

  await loginMini(driverPage, 'courier', courier.phone)
  for (const [lostStatus, task] of [['PICKED_UP', assigned], ['IN_DELIVERY', await assign(await createOrder())]]) {
    let lost = false, uploads = 0
    const countUploads = request => { if (request.method() === 'POST' && request.url().endsWith('/upload')) uploads++ }
    driverPage.on('request', countUploads)
    await driverPage.goto(MINI + '/#/pages/courier/task?id=' + task.id, { waitUntil: 'networkidle' })
    // Each loss scenario starts from a fresh deep-link load. A hash-only goto
    // during uni-app's prior redirect transition can be superseded by it.
    await driverPage.reload({ waitUntil: 'networkidle' })
    await expect(driverPage.locator('.task-id')).toContainText('任务编号 ' + task.id)
    await driverPage.getByText('前往取货', { exact: true }).click(); await driverPage.locator('.check-goods').first().click()
    const chooser = driverPage.waitForEvent('filechooser'); await driverPage.locator('.photo--add').click()
    await (await chooser).setFiles(path.resolve('三端配送小程序/static/logo.png')); await driverPage.locator('.photos .photo:not(.photo--add)').waitFor()
    const matcher = '**/courier/tasks/' + task.id + '/status'
    await driverPage.route(matcher, async route => {
      if (!lost && route.request().method() === 'PUT' && route.request().postDataJSON().status === lostStatus) {
        const response = await route.fetch(); assert.equal(response.status(), 200); lost = true; return route.abort('failed')
      }
      return route.continue()
    })
    await driverPage.getByText('确认装车并开始配送', { exact: true }).click()
    await driverPage.getByText('无法连接服务，请检查网络', { exact: true }).waitFor()
    await driverPage.getByText('确认装车并开始配送', { exact: true }).click(); await driverPage.waitForURL(/courier\/navigation/)
    await expect(driverPage.getByText('该订单尚未选择导航地点，请联系客户在订单详情补选。', { exact: true })).toBeVisible()
    await expect(driverPage.getByText('请在微信小程序中打开地图导航。', { exact: true })).toBeVisible()
    await expect(driverPage.locator('.nav-address')).toHaveText(task.deliveryAddress)
    await expect(driverPage.getByText('打开微信地图导航', { exact: true })).toHaveCount(0)
    await expect(driverPage.locator('.fixed-action uni-button')).not.toHaveAttribute('disabled', 'true')
    await driverPage.waitForLoadState('networkidle')
    await driverPage.unroute(matcher); driverPage.off('request', countUploads)
    assert(lost); assert.equal(uploads, 1)
    const saved = (await mobileApi('/courier/tasks/' + task.id, 'GET', undefined, courierToken)).data
    assert.equal(saved.status, 'IN_DELIVERY'); assert.equal(saved.pickupProofUrls.length, 1)
    assert.equal(saved.events.filter(event => event.status === 'PICKED_UP').length, 1)
    assert.equal(saved.events.filter(event => event.status === 'IN_DELIVERY').length, 1)
    pass('FUI-002 ' + lostStatus + ' 响应丢失重试恢复且仅上传/写事件一次')
  }

  await loginAdmin(adminPage)
  coupon = (await adminApi('/coupons', 'POST', { name: '日期回归' + stamp, audience: 'all', thresholdAmount: 0, discountAmount: 1, validFrom: '2026-01-01 00:00:00', validTo: '2030-12-31 23:59:59', status: 'ACTIVE' })).data
  await adminPage.goto(ADMIN + '/qicai/coupons', { waitUntil: 'networkidle' })
  await adminPage.locator('.el-table__body tr').filter({ hasText: coupon.name }).getByRole('button', { name: '编辑', exact: true }).click()
  const dialog = adminPage.getByRole('dialog'); await dialog.locator('.el-date-editor').hover(); await dialog.locator('.el-range__close-icon').click()
  let couponWrites = 0
  const countCouponWrites = request => { if (['POST', 'PUT'].includes(request.method()) && request.url().includes('/qicai/admin/coupons')) couponWrites++ }
  adminPage.on('request', countCouponWrites); await dialog.getByRole('button', { name: '保存', exact: true }).click()
  await adminPage.getByText('请填写名称与完整有效期', { exact: true }).waitFor(); assert.equal(couponWrites, 0)
  await dialog.getByRole('button', { name: '取消', exact: true }).click(); adminPage.off('request', countCouponWrites)
  pass('FUI-003 真实日期控件清空后阻止保存并显示校验提示')

  await adminPage.goto(ADMIN + '/qicai/orders', { waitUntil: 'networkidle' })
  let releaseOld, oldReceivedResolve
  const oldReceived = new Promise(resolve => { oldReceivedResolve = resolve }), hold = new Promise(resolve => { releaseOld = resolve })
  await adminPage.route('**/qicai/admin/orders?*', async route => {
    if (new URL(route.request().url()).searchParams.get('keyword') === orders[0].orderNo) {
      const response = await route.fetch(); oldReceivedResolve(); await hold; return route.fulfill({ response })
    }
    return route.continue()
  })
  const search = adminPage.getByPlaceholder('订单号、联系人或电话')
  await search.fill(orders[0].orderNo); await search.press('Enter'); await oldReceived
  const newerResponse = adminPage.waitForResponse(response => new URL(response.url()).searchParams.get('keyword') === orders[1].orderNo)
  await search.fill(orders[1].orderNo); await search.press('Enter'); await newerResponse
  await expect(adminPage.locator('.el-table__body tr')).toHaveCount(1)
  await expect(adminPage.locator('.el-table__body tr').first()).toContainText(orders[1].orderNo)
  const oldResponse = adminPage.waitForResponse(response => response.url().includes(encodeURIComponent(orders[0].orderNo)))
  releaseOld(); await oldResponse; await adminPage.waitForLoadState('networkidle')
  await expect(adminPage.locator('.el-table__body tr')).toHaveCount(1)
  await expect(adminPage.locator('.el-table__body tr').first()).toContainText(orders[1].orderNo); await adminPage.unroute('**/qicai/admin/orders?*')
  pass('FUI-004 真实响应延迟交错不覆盖最新订单搜索结果')

  await adminPage.goto(ADMIN + '/qicai/deliveries', { waitUntil: 'networkidle' })
  const deliveredRow = adminPage.locator('.el-table__body tr').filter({ hasText: incoming.taskNo })
  await deliveredRow.locator('.el-image img').waitFor()
  assert((await deliveredRow.locator('.el-image img').first().getAttribute('src')).startsWith('blob:'))
  assert(await deliveredRow.locator('.el-image img').first().evaluate(image => image.complete && image.naturalWidth > 0))
  await deliveredRow.locator('.el-image').first().click(); await adminPage.locator('.el-image-viewer__wrapper').waitFor(); await adminPage.keyboard.press('Escape')
  pass('FUI-005 受保护凭证经 Authorization 下载 Blob 后真实解码和预览')

  originalProfile = (await api('/system/user/profile')).data
  await adminPage.goto(ADMIN + '/user/profile', { waitUntil: 'networkidle' })
  await field(adminPage, '用户昵称').fill('验收管理员' + stamp); await field(adminPage, '手机号码').fill('172' + stamp); await field(adminPage, '邮箱').fill('qa' + stamp + '@example.invalid')
  await adminPage.getByRole('tabpanel', { name: '基本资料', exact: true }).getByRole('button', { name: '保存', exact: true }).click()
  await adminPage.getByText('修改成功', { exact: true }).waitFor(); await adminPage.reload({ waitUntil: 'networkidle' })
  await expect(field(adminPage, '用户昵称')).toHaveValue('验收管理员' + stamp)
  // A separate earlier login retains its own user cache. Reauthenticate to
  // verify the database-backed profile, rather than reading that old snapshot.
  adminToken = (await api('/login', 'POST', { username: 'admin', password: qa.password, code: '', uuid: '' }, null)).token
  assert.equal((await api('/system/user/profile')).data.email, 'qa' + stamp + '@example.invalid')
  pass('FUI-006 后台个人资料编辑后刷新与接口持久化一致')
  await adminPage.locator('.user-info-head').click(); const avatarDialog = adminPage.getByRole('dialog', { name: '修改头像' })
  await avatarDialog.locator('input[type=file]').setInputFiles(path.resolve('三端配送小程序/static/logo.png'))
  // vue-cropper renders its actual crop preview with an object URL.
  await expect(avatarDialog.locator('.avatar-upload-preview img')).toHaveAttribute('src', /^blob:/)
  assert(await avatarDialog.locator('.avatar-upload-preview img').evaluate(image => image.complete && image.naturalWidth > 0))
  const avatarSaved = adminPage.waitForResponse(response => response.url().endsWith('/system/user/profile/avatar') && response.request().method() === 'POST')
  await avatarDialog.getByRole('button', { name: '提 交' }).click(); assert.equal((await (await avatarSaved).json()).code, 200)
  await avatarDialog.waitFor({ state: 'hidden' }); await adminPage.reload({ waitUntil: 'networkidle' })
  assert(await adminPage.locator('.user-info-head > img').evaluate(image => image.complete && image.naturalWidth > 0))
  pass('FUI-007 后台选择头像裁剪提交并刷新显示真实文件')

  await adminPage.getByRole('tab', { name: '修改密码', exact: true }).click()
  await adminPage.getByPlaceholder('请输入旧密码').fill(qa.password); await adminPage.getByPlaceholder('请输入新密码').fill('QaChanged123!'); await adminPage.getByPlaceholder('请确认新密码').fill('QaChanged123!')
  const passwordResponse = adminPage.waitForResponse(response => response.url().endsWith('/system/user/profile/updatePwd') && response.request().method() === 'PUT')
  await adminPage.getByRole('tabpanel', { name: '修改密码', exact: true }).getByRole('button', { name: '保存', exact: true }).click()
  assert.equal((await (await passwordResponse).json()).code, 200); passwordChanged = true
  const relogin = await newPage('frontend-admin-relogin'); await loginAdmin(relogin, 'QaChanged123!')
  const changedLogin = await api('/login', 'POST', { username: 'admin', password: 'QaChanged123!', code: '', uuid: '' }, null)
  adminToken = changedLogin.token
  await api('/system/user/profile/updatePwd', 'PUT', { oldPassword: 'QaChanged123!', newPassword: qa.password }); passwordChanged = false
  pass('FUI-008 后台改密后用新密码重新登录并恢复隔离环境密码')
  // Restoring the password is another password change and correctly revokes
  // the UI session above. Test deep routes with a newly authenticated context.
  // The separate framework suite asserts the revoked JWT is actually rejected.
  const restored = await newPage('frontend-admin-restored'); await loginAdmin(restored)
  await restored.goto(ADMIN + '/not-a-real-page-' + stamp, { waitUntil: 'networkidle' }); await restored.getByText('找不到网页！', { exact: false }).waitFor()
  await restored.goto(ADMIN + '/user/profile', { waitUntil: 'networkidle' }); await field(restored, '用户昵称').waitFor()
  pass('FUI-009 后台404页面及重新进入资料页面')
})().catch(error => { results.push({ name: '浏览器补漏流程', passed: false, error: error.message }); console.error(error.message) }).finally(async () => {
  if (passwordChanged) await api('/system/user/profile/updatePwd', 'PUT', { oldPassword: 'QaChanged123!', newPassword: qa.password }).catch(error => errors.push({ cleanup: error.message }))
  if (originalProfile) await api('/system/user/profile', 'PUT', { nickName: originalProfile.nickName, phonenumber: originalProfile.phonenumber, email: originalProfile.email, sex: originalProfile.sex }).catch(error => errors.push({ cleanup: error.message }))
  for (const [name, page] of pages) await page.screenshot({ path: path.join(qa.out, name + '.png'), fullPage: true }).catch(error => errors.push({ screenshot: error.message }))
  // Keep the harness-owned synthetic records for the final SQL consistency check.
  // The harness stops its services and retains its isolated database/files for
  // inspection and explicit later cleanup; no production data is touched.
  await fs.writeFile(path.join(qa.out, 'frontend-browser-results.json'), JSON.stringify({ at: new Date().toISOString(), kind: 'real browser + real isolated API, deliberate response loss/delay only', results, errors, requests, fixtures: { customerId: buyer?.id, courierId: courier?.id, productId: product?.id, couponId: coupon?.id, orderIds: orders.map(order => order.id), taskIds: tasks.map(task => task.id) } }, null, 2))
  if (browser) await browser.close()
  process.exitCode = results.some(result => !result.passed) || errors.length ? 1 : 0
  console.log(`FRONTEND BROWSER ${results.filter(result => result.passed).length}/${results.length}, runtime errors ${errors.length}`)
})
