import { readFile, mkdir, writeFile } from 'node:fs/promises'
import { pathToFileURL } from 'node:url'
import acceptanceContext from './acceptance-context.cjs'
const qa = acceptanceContext()

const base = qa.base
const stamp = String(Date.now()).slice(-8)
const phone = (prefix) => `${prefix}${stamp}`
const results = []
const endpoints = []

async function request(path, { method = 'GET', body, token, adminToken } = {}) {
  const headers = {}
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  if (token) headers['X-Qicai-Token'] = token
  if (adminToken) headers.Authorization = `Bearer ${adminToken}`
  const response = await fetch(`${base}${path}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(15000)
  })
  endpoints.push({method,path:path.split('?')[0],status:response.status})
  let payload
  try { payload = await response.json() } catch { payload = { raw: await response.text() } }
  return { status: response.status, payload }
}

async function upload(token, content, type, name) {
  const form = new FormData()
  form.append('file', new Blob([content], { type }), name)
  const headers = token ? { 'X-Qicai-Token': token } : {}
  const response = await fetch(`${base}/qicai/mobile/upload`, { method: 'POST', headers, body: form })
  endpoints.push({method:'POST',path:'/qicai/mobile/upload',status:response.status})
  let payload
  try { payload = await response.json() } catch { payload = {} }
  return { status: response.status, payload }
}

function test(name, passed, detail = '') {
  results.push({ name, passed: Boolean(passed), detail })
  process.stdout.write(`${passed ? 'PASS' : 'FAIL'} ${name}${detail ? ` — ${detail}` : ''}\n`)
  if (!passed) throw new Error(`${name}: ${detail}`)
}

async function sendSms(audience, phoneNumber) {
  const response = await request('/qicai/mobile/auth/sms', {
    method: 'POST', body: { audience, phone: phoneNumber }
  })
  test(`${audience}短信验证码`, response.status === 201 && /^\d{6}$/.test(response.payload.data?.devCode || ''), `HTTP ${response.status}`)
  return response.payload.data.devCode
}

async function register(audience, phoneNumber, fields) {
  const smsCode = await sendSms(audience, phoneNumber)
  const response = await request('/qicai/mobile/auth/register', {
    method: 'POST', body: { audience, phone: phoneNumber, password: 'QcTest123!', contactName: '验收联系人', smsCode, ...fields }
  })
  test(`${audience}注册入库`, response.status === 201 && response.payload.data?.id, `HTTP ${response.status}`)
  return response.payload.data
}

async function login(audience, phoneNumber) {
  const response = await request('/qicai/mobile/auth/login', {
    method: 'POST', body: { audience, phone: phoneNumber, password: 'QcTest123!' }
  })
  test(`${audience}登录`, response.status === 200 && response.payload.data?.token, `HTTP ${response.status}`)
  return response.payload.data.token
}

async function run() {
  let response = await request('/qicai/mobile/health')
  test('后端健康检查', response.status === 200 && response.payload.data?.status === 'UP')

  response = await request('/qicai/mobile/products')
  test('未登录禁止访问商品', response.status === 401, `HTTP ${response.status}`)

  const malformed = await fetch(`${base}/qicai/mobile/auth/login`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{'
  })
  test('畸形JSON返回400且不白屏', malformed.status === 400, `HTTP ${malformed.status}`)

  response = await request('/qicai/mobile/orders/undefined')
  test('非法路径ID返回400', response.status === 400, `HTTP ${response.status}`)

  const consumerPhone = phone('188')
  const otherPhone = phone('187')
  const businessPhone = phone('186')
  const consumer = await register('consumer', consumerPhone, { address: '上海市浦东新区验收路 88 号' })
  const other = await register('consumer', otherPhone, { address: '上海市徐汇区越权测试路 1 号' })
  const business = await register('business', businessPhone, { companyName: '企采通验收企业', taxId: `91310000${stamp}A` })
  test('企业注册默认待审核', business.status === 'PENDING', String(business.status))

  response = await request('/qicai/mobile/auth/sms', { method: 'POST', body: { audience: 'consumer', phone: consumerPhone } })
  test('短信重复发送限流', response.status === 429, `HTTP ${response.status}`)

  response = await request('/qicai/mobile/auth/login', { method: 'POST', body: { audience: 'consumer', phone: consumerPhone, password: 'WrongPassword!' } })
  test('错误密码拒绝登录', response.status === 401, `HTTP ${response.status}`)

  const consumerToken = await login('consumer', consumerPhone)
  const otherToken = await login('consumer', otherPhone)
  const businessToken = await login('business', businessPhone)

  response = await upload('', 'not-an-image', 'text/plain', 'bad.txt')
  test('未登录禁止上传', response.status === 401, `HTTP ${response.status}`)
  response = await upload(consumerToken, 'not-an-image', 'image/png', 'spoof.png')
  test('伪造图片内容被拒绝', response.status === 415, `HTTP ${response.status}`)
  const imageBytes = await readFile(new URL('../三端配送小程序/static/logo.png', import.meta.url))
  response = await upload(consumerToken, imageBytes, 'image/png', 'acceptance.png')
  test('真实图片上传成功', response.status === 201 && response.payload.data?.fileName, `HTTP ${response.status}`)
  const customerProof = response.payload.data.fileName

  response = await request('/qicai/mobile/products', { token: businessToken })
  test('企业审核前禁止查看企业价', response.status === 403, `HTTP ${response.status}`)

  response = await request('/login', { method: 'POST', body: { username: process.env.QICAI_ADMIN_USER || 'admin', password: qa.password, code: '', uuid: '' } })
  test('管理后台登录', response.status === 200 && response.payload.token, `HTTP ${response.status}`)
  const adminToken = response.payload.token

  const courierPhone = phone('139')
  const courierPassword = 'CourierTest123!'
  response = await request('/qicai/admin/couriers', {
    method: 'POST', adminToken,
    body: { phone: courierPhone, password: courierPassword, name: '验收配送员', vehicleNo: `测A${stamp.slice(-5)}`, status: 'ACTIVE', available: true }
  })
  test('后台新增配送员账号', response.status === 200 && response.payload.data?.id)
  const createdCourier = response.payload.data

  response = await request('/qicai/admin/dashboard', { token: consumerToken })
  test('普通用户不能访问管理接口', response.status === 401 || response.status === 403 || response.payload.code === 401 || response.payload.code === 403, `HTTP ${response.status}/code ${response.payload.code}`)

  response = await request(`/qicai/admin/customers/${business.id}/review`, {
    method: 'PUT', adminToken, body: { status: 'ACTIVE', version: business.version }
  })
  test('后台审核企业', response.status === 200 && response.payload.data?.status === 'ACTIVE', `HTTP ${response.status}`)

  const [consumerProducts, businessProducts] = await Promise.all([
    request('/qicai/mobile/products', { token: consumerToken }),
    request('/qicai/mobile/products', { token: businessToken })
  ])
  const cp = consumerProducts.payload.data?.[0]
  const bp = businessProducts.payload.data?.[0]
  test('C端只返回自己的价格', consumerProducts.status === 200 && cp?.price && !('businessPrice' in cp) && !('consumerPrice' in cp))
  test('B端只返回自己的价格', businessProducts.status === 200 && bp?.price && !('businessPrice' in bp) && !('consumerPrice' in bp))
  test('B/C价格真实隔离且不同', Number(cp.price) !== Number(bp.price), `C=${cp.price}, B=${bp.price}`)

  response = await request('/qicai/mobile/profile', { token: consumerToken })
  const profileVersion = response.payload.data.version
  response = await request('/qicai/mobile/profile', {
    method: 'PUT', token: consumerToken,
    body: { phone: consumerPhone, contactName: '验收联系人已修改', address: '上海市浦东新区新地址 99 号', version: profileVersion }
  })
  test('个人注册信息编辑并持久化', response.status === 200 && response.payload.data?.contactName === '验收联系人已修改')
  response = await request('/qicai/mobile/profile', {
    method: 'PUT', token: consumerToken,
    body: { phone: consumerPhone, contactName: '过期版本', address: '旧地址', version: profileVersion }
  })
  test('资料并发版本冲突保护', response.status === 409, `HTTP ${response.status}`)

  const stockBefore = Number(cp.stock)
  const key = `accept-${Date.now()}`
  response = await request('/qicai/mobile/orders', {
    method: 'POST', token: consumerToken, body: { productId: cp.id, quantity: 2, idempotencyKey: key }
  })
  test('创建订单', response.status === 201 && response.payload.data?.id, `HTTP ${response.status}`)
  const order = response.payload.data
  const duplicate = await request('/qicai/mobile/orders', {
    method: 'POST', token: consumerToken, body: { productId: cp.id, quantity: 2, idempotencyKey: key }
  })
  test('重复提交订单幂等', duplicate.status === 201 && duplicate.payload.data?.id === order.id)
  const productsAfter = await request('/qicai/mobile/products', { token: consumerToken })
  const cpAfter = productsAfter.payload.data.find((item) => item.id === cp.id)
  test('幂等请求只扣一次库存', Number(cpAfter.stock) === stockBefore - 2, `${stockBefore} -> ${cpAfter.stock}`)

  response = await request(`/qicai/mobile/orders/${order.id}`, { token: otherToken })
  test('禁止跨用户读取订单', response.status === 404, `HTTP ${response.status}`)

  const couriers = await request('/qicai/admin/couriers', { adminToken })
  test('后台读取配送员', couriers.status === 200 && couriers.payload.data?.some((item) => item.id === createdCourier.id))
  const courierId = createdCourier.id
  response = await request('/qicai/admin/deliveries/assign', {
    method: 'POST', adminToken,
    body: { orderId: order.id, courierId, warehouseName: '验收仓库', warehouseAddress: '上海市验收仓库路1号', deliveryWindow: '今日 14:00-16:00', distanceKm: 3.2 }
  })
  test('后台指派配送任务', response.status === 200 && response.payload.data?.id, `HTTP ${response.status}`)
  const taskId = response.payload.data.id

  response = await request('/qicai/mobile/courier/login', {
    method: 'POST', body: { phone: courierPhone, password: courierPassword }
  })
  test('配送员登录', response.status === 200 && response.payload.data?.token, `HTTP ${response.status}`)
  const courierToken = response.payload.data.token
  const courierUpload = await upload(courierToken, imageBytes, 'image/png', 'pickup.png')
  const courierProof = courierUpload.payload.data.fileName
  response = await request(`/qicai/mobile/courier/tasks/${taskId}/status`, {
    method: 'PUT', token: courierToken, body: { status: 'PICKED_UP', pickupProofUrl: courierProof }
  })
  test('配送员取货状态与凭证同步', response.status === 200 && response.payload.data?.status === 'PICKED_UP' && response.payload.data?.pickupProofUrl === courierProof)
  response = await request(`/qicai/mobile/orders/${order.id}`, { token: consumerToken })
  test('取货状态同步至客户订单', response.payload.data?.status === 'PICKED_UP', String(response.payload.data?.status))
  response = await request(`/qicai/mobile/courier/tasks/${taskId}/status`, {
    method: 'PUT', token: courierToken, body: { status: 'DELIVERED' }
  })
  test('拦截非法配送状态跳转', response.status === 409, `HTTP ${response.status}`)
  response = await request(`/qicai/mobile/courier/tasks/${taskId}/status`, {
    method: 'PUT', token: courierToken, body: { status: 'IN_DELIVERY' }
  })
  test('配送员开始配送', response.status === 200 && response.payload.data?.status === 'IN_DELIVERY')
  response = await request(`/qicai/mobile/orders/${order.id}/receipt`, {
    method: 'POST', token: consumerToken, body: { signedName: '验收联系人已修改', proofUrl: customerProof, remark: '验收签收' }
  })
  test('客户签收回写订单', response.status === 200 && response.payload.data?.status === 'COMPLETED')
  const taskAfterReceipt = await request(`/qicai/mobile/courier/tasks/${taskId}`, { token: courierToken })
  test('客户签收回写配送任务', taskAfterReceipt.payload.data?.status === 'DELIVERED')

  response = await request('/qicai/mobile/coupons', { token: consumerToken })
  const availableCoupon = response.payload.data?.find((item) => item.claimStatus === 'UNCLAIMED')
  if (availableCoupon) {
    const claimed = await request(`/qicai/mobile/coupons/${availableCoupon.couponId}/claim`, { method: 'POST', token: consumerToken })
    test('客户领取优惠券', claimed.status === 201)
    const claimedAgain = await request(`/qicai/mobile/coupons/${availableCoupon.couponId}/claim`, { method: 'POST', token: consumerToken })
    test('重复领取优惠券被拦截', claimedAgain.status === 409, `HTTP ${claimedAgain.status}`)
    const couponQuantity = Math.max(1, Math.ceil(Number(availableCoupon.thresholdAmount) / Number(cp.price)))
    const couponStockBefore = Number((await request(`/qicai/mobile/products/${cp.id}`, { token: consumerToken })).payload.data.stock)
    const couponOrderResponse = await request('/qicai/mobile/orders', {
      method: 'POST', token: consumerToken,
      body: { productId: cp.id, quantity: couponQuantity, customerCouponId: claimed.payload.data.customerCouponId, remark: '优惠券与备注闭环', idempotencyKey: `coupon-${Date.now()}` }
    })
    const couponOrder = couponOrderResponse.payload.data
    test('优惠券下单真实抵扣', couponOrderResponse.status === 201 && Number(couponOrder?.discountAmount) > 0, `优惠 ${couponOrder?.discountAmount}`)
    test('订单备注真实持久化', couponOrder?.remark === '优惠券与备注闭环', String(couponOrder?.remark))
    response = await request(`/qicai/admin/orders/${couponOrder.id}/cancel`, { method: 'POST', adminToken })
    test('后台取消订单并回补库存', response.status === 200 && response.payload.data?.status === 'CANCELLED')
    test('取消精确恢复商品库存', Number((await request(`/qicai/mobile/products/${cp.id}`, { token: consumerToken })).payload.data.stock) === couponStockBefore)
    response = await request('/qicai/mobile/coupons', { token: consumerToken })
    const restoredCoupon = response.payload.data?.find((item) => item.customerCouponId === claimed.payload.data.customerCouponId)
    test('取消订单后优惠券自动退回', restoredCoupon?.claimStatus === 'AVAILABLE', String(restoredCoupon?.claimStatus))
    response = await request(`/qicai/admin/orders/${couponOrder.id}/cancel`, { method: 'POST', adminToken })
    test('重复取消订单保持幂等', response.status === 200 && response.payload.data?.status === 'CANCELLED')
    test('重复取消不会再次增加库存', Number((await request(`/qicai/mobile/products/${cp.id}`, { token: consumerToken })).payload.data.stock) === couponStockBefore)
  } else {
    test('存在可领取优惠券', false, '种子优惠券未返回')
  }

  const sku = `ACCEPT-${stamp}`
  response = await request('/qicai/admin/products', {
    method: 'POST', adminToken,
    body: { sku, name: '验收临时商品', subtitle: 'CRUD闭环', category: '验收', unit: '件', stock: 10, businessPrice: 12.34, consumerPrice: 23.45, status: 'ACTIVE' }
  })
  test('后台新增商品', response.status === 200 && response.payload.data?.id)
  const product = response.payload.data
  response = await request(`/qicai/admin/products/${product.id}`, {
    method: 'PUT', adminToken,
    body: { sku, name: '验收临时商品已编辑', subtitle: 'CRUD闭环', category: '验收', unit: '件', stock: 11, businessPrice: 13.34, consumerPrice: 24.45, status: 'ACTIVE', version: product.version }
  })
  test('后台编辑商品', response.status === 200 && response.payload.data?.stock === 11)
  response = await request(`/qicai/admin/products/${product.id}`, { method: 'DELETE', adminToken })
  test('后台软删除商品', response.status === 200)
  response = await request(`/qicai/admin/products?keyword=${encodeURIComponent(sku)}`, { adminToken })
  test('软删除商品列表不可见', response.status === 200 && response.payload.data?.total === 0)

  response = await request('/qicai/mobile/orders?status=NOT_A_STATUS', { token: consumerToken })
  test('无效枚举返回400', response.status === 400, `HTTP ${response.status}`)
  response = await request('/qicai/mobile/products?keyword=%27%20OR%201%3D1--', { token: consumerToken })
  test('搜索参数使用预编译防注入', response.status === 200 && Array.isArray(response.payload.data))
  response = await request('/qicai/mobile/products?keyword=__NO_SUCH_PRODUCT__', { token: consumerToken })
  test('空数据返回稳定空数组', response.status === 200 && Array.isArray(response.payload.data) && response.payload.data.length === 0)

  response = await request(`/qicai/admin/couriers/${createdCourier.id}`, {
    method: 'PUT', adminToken,
    body: { phone: courierPhone, name: '验收配送员已修改', vehicleNo: `测B${stamp.slice(-5)}`, status: 'ACTIVE', available: false, version: createdCourier.version }
  })
  test('后台编辑配送员', response.status === 200 && response.payload.data?.name === '验收配送员已修改' && !response.payload.data?.available)
  response = await request(`/qicai/admin/couriers/${createdCourier.id}`, { method: 'DELETE', adminToken })
  test('后台软删除配送员', response.status === 200)
  response = await request('/qicai/mobile/courier/login', { method: 'POST', body: { phone: courierPhone, password: courierPassword } })
  test('已删除配送员禁止登录', response.status === 401, `HTTP ${response.status}`)

  await request('/qicai/mobile/auth/session', { method: 'DELETE', token: consumerToken })
  response = await request('/qicai/mobile/profile', { token: consumerToken })
  test('退出后Token立即失效', response.status === 401, `HTTP ${response.status}`)

  const passed = results.filter((item) => item.passed).length
  process.stdout.write(`\nSUMMARY ${passed}/${results.length} passed\n`)
}

run().catch((error) => {
  process.stderr.write(`\nABORT ${error.stack || error.message}\n`)
  process.exitCode = 1
}).finally(async () => {
  const folder = pathToFileURL(qa.out + '/')
  await mkdir(folder,{recursive:true})
  await writeFile(new URL('api-results.json',folder),JSON.stringify({at:new Date().toISOString(),results,endpoints},null,2))
})
