import { readFile, mkdir, writeFile } from 'node:fs/promises'
import path from 'node:path'
import context from './acceptance-context.cjs'

// Dedicated synthetic fixtures only. Never point this script at a live environment.
const { base, out, password } = context()
const results = [], startedAt = new Date().toISOString(), stamp = String(Date.now()).slice(-8)
const output = process.env.QICAI_BACKEND_REPORT || path.join(out, 'backend-results.json')
const fixtures = {}
async function call(path, method = 'GET', body, token, isAdmin = false) {
  const response = await fetch(base + path, { method, headers: {
    'Content-Type': 'application/json', ...(token ? { [isAdmin ? 'Authorization' : 'X-Qicai-Token']: isAdmin ? `Bearer ${token}` : token } : {})
  }, body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(20000) })
  return { http: response.status, ...await response.json() }
}
function check(name, passed, detail) {
  results.push({ name, passed: Boolean(passed), detail })
  console.log(`${passed ? 'PASS' : 'FAIL'} ${name} ${JSON.stringify(detail ?? '')}`)
}
function data(result, description) {
  if (!result.data?.id) throw new Error(`Fixture ${description} failed: HTTP ${result.http} ${result.msg}`)
  return result.data
}
async function run() {
  const logged = await call('/login', 'POST', { username: 'admin', password, code: '', uuid: '' })
  if (!logged.token) throw new Error(`Admin fixture login failed: ${logged.http} ${logged.msg}`)
  const admin = (path, method, body) => call('/qicai/admin' + path, method, body, logged.token, true)
  const mobile = (path, method, body, token) => call('/qicai/mobile' + path, method, body, token)
  async function customer(audience, prefix) {
    const body = { audience, phone: prefix + stamp, password: 'QcTest123!', contactName: '后端回归', address: '隔离验收路1号', companyName: audience === 'business' ? '后端回归企业' : undefined, taxId: audience === 'business' ? '91310000' + stamp + 'A' : undefined }
    const sms = await mobile('/auth/sms', 'POST', body)
    const profile = data(await mobile('/auth/register', 'POST', { ...body, smsCode: sms.data?.devCode }), 'register')
    const login = await mobile('/auth/login', 'POST', body)
    if (!login.data?.token) throw new Error('Customer login failed')
    return { ...profile, token: login.data.token }
  }
  const consumer = await customer('consumer', '177'), business = await customer('business', '176')
  fixtures.customerIds = [consumer.id, business.id]
  const changedBusiness = await mobile('/profile', 'PUT', { ...business, companyName: '更新后需重新审核企业', version: business.version }, business.token)
  if (changedBusiness.http !== 200) throw new Error('Business profile fixture update failed')
  const staleReview = await admin(`/customers/${business.id}/review`, 'PUT', { status: 'ACTIVE', version: business.version })
  const afterStaleReview = await mobile('/profile', 'GET', undefined, business.token)
  check('旧版本审核不得通过已变更的企业资料', staleReview.http === 409 && afterStaleReview.data?.status === 'PENDING', { http: staleReview.http, status: afterStaleReview.data?.status })
  const reviewed = await admin(`/customers/${business.id}/review`, 'PUT', { status: 'ACTIVE', version: afterStaleReview.data.version })
  check('重新读取后按当前资料版本审核成功', reviewed.http === 200 && reviewed.data?.status === 'ACTIVE', reviewed.http)

  const product = data(await admin('/products', 'POST', { sku: 'BACK-' + stamp, name: '后端回归商品', category: '验收', unit: '件', stock: 20, businessPrice: 50, consumerPrice: 100, status: 'ACTIVE' }), 'product')
  fixtures.productId = product.id
  const template = { name: '后端范围券' + stamp, audience: 'business', thresholdAmount: 0, discountAmount: 10, validFrom: '2020-01-01 00:00:00', validTo: '2035-01-01 00:00:00', status: 'ACTIVE' }
  const businessCoupon = data(await admin('/coupons', 'POST', template), 'business coupon')
  const invalidGrant = await admin(`/coupons/${businessCoupon.id}/grant/${consumer.id}`, 'POST')
  const afterInvalidGrant = await mobile('/coupons', 'GET', undefined, consumer.token)
  const wrongGrantSaved = afterInvalidGrant.data?.some(c => c.couponId === businessCoupon.id && c.customerCouponId)
  check('企业专用券不可发给个人客户', invalidGrant.http === 409 && !wrongGrantSaved, { http: invalidGrant.http, persisted: wrongGrantSaved })
  const expiredCoupon = data(await admin('/coupons', 'POST', { ...template, name: '过期券', audience: 'consumer', validTo: '2021-01-01 00:00:00' }), 'expired coupon')
  const expiredGrant = await admin(`/coupons/${expiredCoupon.id}/grant/${consumer.id}`, 'POST')
  const afterExpiredGrant = await mobile('/coupons', 'GET', undefined, consumer.token)
  const expiredGrantSaved = afterExpiredGrant.data?.some(c => c.couponId === expiredCoupon.id && c.customerCouponId)
  check('过期券不可发放', expiredGrant.http === 409 && !expiredGrantSaved, { http: expiredGrant.http, persisted: expiredGrantSaved })
  const mutableCoupon = data(await admin('/coupons', 'POST', { ...template, name: '变更适用范围券', audience: 'consumer' }), 'mutable coupon')
  const claim = await mobile(`/coupons/${mutableCoupon.id}/claim`, 'POST', undefined, consumer.token)
  if (!claim.data?.customerCouponId) throw new Error('Coupon claim fixture failed')
  data(await admin(`/coupons/${mutableCoupon.id}`, 'PUT', { ...template, version: mutableCoupon.version }), 'coupon audience update')
  const listed = await mobile('/coupons', 'GET', undefined, consumer.token)
  const projected = listed.data?.find(c => c.couponId === mutableCoupon.id)
  check('已领券改为其他客群后不再显示可用', projected && !projected.usable && projected.claimStatus !== 'AVAILABLE', { usable: projected?.usable, claimStatus: projected?.claimStatus })
  const invalidUse = await mobile('/orders', 'POST', { productId: product.id, quantity: 1, customerCouponId: claim.data.customerCouponId, idempotencyKey: 'wrong-audience-' + stamp }, consumer.token)
  const stockAfter = await mobile(`/products/${product.id}`, 'GET', undefined, consumer.token)
  const ordersAfter = await mobile('/orders', 'GET', undefined, consumer.token)
  check('客群不匹配优惠券不能抵扣且不扣库存', invalidUse.http === 409 && stockAfter.data.stock === 20 && ordersAfter.data.total === 0, { http: invalidUse.http, stock: stockAfter.data.stock, orders: ordersAfter.data.total })

  const tooLong = await admin('/couriers', 'POST', { phone: '175' + stamp, password: '密'.repeat(30), name: '超字节密码', status: 'ACTIVE', available: true })
  check('配送员创建拒绝超过BCrypt限制的密码且不报500', tooLong.http === 400, tooLong.http)
  const aliasedPassword = await mobile('/courier/login', 'POST', { phone: '175' + stamp, password: '密'.repeat(24) + '不同后缀' })
  check('超限密码不能产生忽略后缀即可登录的账号', aliasedPassword.http === 401, aliasedPassword.http)
  const courier = data(await admin('/couriers', 'POST', { phone: '174' + stamp, password: 'QcTest123!', name: '后端回归配送员', status: 'ACTIVE', available: true }), 'courier')
  fixtures.courierId = courier.id
  const courierLogin = await mobile('/courier/login', 'POST', { phone: courier.phone, password: 'QcTest123!' })
  const courierToken = courierLogin.data.token
  const order = data(await mobile('/orders', 'POST', { productId: product.id, quantity: 1, idempotencyKey: 'proof-' + stamp }, consumer.token), 'order')
  fixtures.orderId = order.id
  const task = data(await admin('/deliveries/assign', 'POST', { orderId: order.id, courierId: courier.id, warehouseName: '测试仓库', warehouseAddress: '验收仓库路', distanceKm: 1 }), 'delivery')
  fixtures.taskId = task.id
  const form = new FormData()
  form.append('file', new Blob([await readFile(new URL('../三端配送小程序/static/logo.png', import.meta.url))], { type: 'image/png' }), 'proof.png')
  const upload = await fetch(base + '/qicai/mobile/upload', { method: 'POST', headers: { 'X-Qicai-Token': courierToken }, body: form })
  const proof = (await upload.json()).data?.fileName
  if (!proof) throw new Error('Upload fixture failed')
  const pickup = await mobile(`/courier/tasks/${task.id}/status`, 'PUT', { status: 'PICKED_UP', pickupProofUrl: proof }, courierToken)
  check('真实上传凭证取货并保存', pickup.http === 200 && pickup.data.pickupProofUrl === proof, pickup.http)
  const injection = await mobile(`/courier/tasks/${task.id}/status`, 'PUT', { status: 'IN_DELIVERY', pickupProofUrl: '/profile/not-owned.png', deliveryProofUrl: 'https://invalid.example/forged.png', signedName: '未签收' }, courierToken)
  const afterInjection = await mobile(`/courier/tasks/${task.id}`, 'GET', undefined, courierToken)
  check('非凭证阶段拒绝伪造字段且不覆盖有效装车凭证', injection.http === 400 && afterInjection.data.pickupProofUrl === proof && afterInjection.data.deliveryProofUrl == null && afterInjection.data.signedName == null && afterInjection.data.status === 'PICKED_UP', { http: injection.http, status: afterInjection.data?.status, pickup: afterInjection.data?.pickupProofUrl === proof, deliveryProofEmpty: afterInjection.data?.deliveryProofUrl == null })
  const progress = await mobile(`/courier/tasks/${task.id}/status`, 'PUT', { status: 'IN_DELIVERY' }, courierToken)
  const finish = await mobile(`/courier/tasks/${task.id}/status`, 'PUT', { status: 'DELIVERED', deliveryProofUrl: proof, signedName: '测试签收人' }, courierToken)
  const customerOrder = await mobile(`/orders/${order.id}`, 'GET', undefined, consumer.token)
  check('合法履约仍可完成且客户重读状态一致', progress.http === 200 && finish.http === 200 && customerOrder.data?.status === 'COMPLETED' && customerOrder.data?.delivery?.signedName === '测试签收人', { progress: progress.http, finish: finish.http, status: customerOrder.data?.status })
}
let failure
try { await run() } catch (error) { failure = error.message; console.error(failure) }
await mkdir(out, { recursive: true })
await writeFile(output, JSON.stringify({ startedAt, finishedAt: new Date().toISOString(), base, fixtures, results, failure, passed: !failure && results.length === 11 && results.every(r => r.passed) }, null, 2))
if (failure || results.length !== 11 || results.some(r => !r.passed)) process.exitCode = 1
