import { readFile, writeFile, mkdir } from 'node:fs/promises'
import { pathToFileURL } from 'node:url'
import acceptanceContext from './acceptance-context.cjs'
const qa = acceptanceContext(), output = pathToFileURL(qa.out + '/')
const base = qa.base
if (base !== 'http://127.0.0.1:15190') throw new Error('This destructive QA fixture only runs against the isolated SSH QA tunnel')
const results = [], stamp = String(Date.now()).slice(-8)
const call = async (path, method = 'GET', body, token, admin = false) => {
  const response = await fetch(base + path, { method, headers: { 'Content-Type': 'application/json', ...(token ? { [admin ? 'Authorization' : 'X-Qicai-Token']: admin ? 'Bearer ' + token : token } : {}) }, body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(15000) })
  return { status: response.status, ...await response.json() }
}
function check(name, pass, detail) { results.push({ name, passed: !!pass, detail }); console.log((pass ? 'PASS ' : 'FAIL ') + name + (detail ? ' ' + JSON.stringify(detail) : '')) }
async function customer(prefix, audience = 'consumer') {
  const fields = { audience, phone: prefix + stamp, password: 'QcTest123!', contactName: 'QA联系人', address: audience === 'consumer' ? '上海市验收路88号' : undefined, companyName: audience === 'business' ? 'QA企业' : undefined, taxId: audience === 'business' ? '91310000' + stamp + 'A' : undefined }
  const sms = await call('/qicai/mobile/auth/sms', 'POST', fields)
  const registered = await call('/qicai/mobile/auth/register', 'POST', { ...fields, smsCode: sms.data.devCode })
  if (!registered.data?.id) throw new Error('fixture registration: ' + JSON.stringify(registered))
  const logged = await call('/qicai/mobile/auth/login', 'POST', fields)
  return { ...fields, ...registered.data, token: logged.data.token }
}
const admin = (await call('/login', 'POST', { username: 'admin', password: qa.password, code: '', uuid: '' })).token
if (!admin) throw new Error('QA admin login failed')
const a = (path, method, body) => call('/qicai/admin' + path, method, body, admin, true)
const c = await customer('185'), b = await customer('184', 'business')
const mobile = (path, method, body, token = c.token) => call('/qicai/mobile' + path, method, body, token)
await a(`/customers/${b.id}/review`, 'PUT', { status: 'ACTIVE', version: b.version })
const product = (await a('/products', 'POST', { sku: 'EDGE-' + stamp, name: 'QA边界商品', category: '食品采购', unit: '件', stock: 100, businessPrice: 12.34, consumerPrice: 23.45, status: 'ACTIVE' })).data

const phone = '183' + stamp
const sms = await mobile('/auth/sms', 'POST', { audience: 'consumer', phone })
const badCode = sms.data.devCode === '000000' ? '111111' : '000000'
let wrong
for (let i = 0; i < 6; i++) wrong = await mobile('/auth/register', 'POST', { audience: 'consumer', phone, smsCode: badCode, password: 'QcTest123!', contactName: '错误码测试', address: '验收路' })
check('短信验证码连续错误五次后限流', wrong.status === 429, wrong.status)

const concurrent = await Promise.all(Array.from({ length: 4 }, () => mobile('/orders', 'POST', { productId: product.id, quantity: 1, idempotencyKey: 'concurrent-' + stamp })))
check('并发同幂等键返回同一订单', concurrent.every(r => r.status === 201 && r.data?.id === concurrent[0].data?.id), concurrent.map(r => r.status))
const order = concurrent.find(r => r.data?.id)?.data
const stock = (await mobile('/products/' + product.id)).data.stock
check('并发请求只扣一次库存', stock === 99, stock)

const badAddress = await mobile('/orders', 'POST', { productId: product.id, quantity: 1, idempotencyKey: 'b-missing-' + stamp }, b.token)
check('企业未填收货地址不能用企业名代替地址下单', badAddress.status === 400, badAddress.data?.deliveryAddress || badAddress.status)
const address = '上海市闵行区验收大街 66 号'
const bOrder = await mobile('/orders', 'POST', { productId: product.id, quantity: 1, deliveryAddress: address, idempotencyKey: 'b-address-' + stamp }, b.token)
check('企业下单地址真实保存', bOrder.status === 201 && bOrder.data.deliveryAddress === address, bOrder.data?.deliveryAddress)

const disabledCustomer = await a(`/customers/${c.id}/review`, 'PUT', { status: 'DISABLED', version: c.version })
const disabled = await mobile('/profile')
check('停用客户的旧Token不可继续读取资料', [401, 403].includes(disabled.status), disabled.status)
await a(`/customers/${c.id}/review`, 'PUT', { status: 'ACTIVE', version: disabledCustomer.data.version })
c.token = (await mobile('/auth/login', 'POST', c)).data.token

let courier = (await a('/couriers', 'POST', { phone: '182' + stamp, name: 'QA边界配送', password: 'QcTest123!', vehicleNo: '验收01', available: true, status: 'ACTIVE' })).data
let courierToken = (await mobile('/courier/login', 'POST', { phone: courier.phone, password: 'QcTest123!' })).data.token
const task = (await a('/deliveries/assign', 'POST', { orderId: order.id, courierId: courier.id, warehouseName: '验收仓库', warehouseAddress: '上海市验收仓库路1号', distanceKm: 1, deliveryWindow: '验收窗口' })).data
const missingPickup = await mobile('/courier/tasks/' + task.id + '/status', 'PUT', { status: 'PICKED_UP' }, courierToken)
check('后端拦截未上传装车凭证的取货请求', missingPickup.status === 400, missingPickup.status)

const bytes = await readFile(new URL('../三端配送小程序/static/logo.png', import.meta.url))
async function upload(content, name = 'proof.png') {
  const form = new FormData(); form.append('file', new Blob([content], { type: 'image/png' }), name)
  const r = await fetch(base + '/qicai/mobile/upload', { method: 'POST', headers: { 'X-Qicai-Token': courierToken }, body: form })
  return { status: r.status, ...await r.json() }
}
const truncated = await upload(Buffer.from([137, 80, 78, 71, 0, 0, 0, 0]), 'truncated.png')
check('仅伪造文件头的损坏图片被拒绝', truncated.status === 415, truncated.status)
const proof = await upload(bytes)
check('真实图片上传', proof.status === 201 && proof.data?.fileName)
await mobile('/courier/tasks/' + task.id + '/status', 'PUT', { status: 'PICKED_UP', pickupProofUrl: proof.data.fileName }, courierToken)
await mobile('/courier/tasks/' + task.id + '/status', 'PUT', { status: 'IN_DELIVERY' }, courierToken)
const missingDelivery = await mobile('/courier/tasks/' + task.id + '/status', 'PUT', { status: 'DELIVERED' }, courierToken)
check('后端拦截缺少签收凭证及姓名的完成请求', missingDelivery.status === 400, missingDelivery.status)

const resetPassword = await a(`/couriers/${courier.id}`, 'PUT', { ...courier, available: Boolean(courier.available), password: 'Changed123!' })
check('后台修改配送员密码成功', resetPassword.status === 200 && resetPassword.data?.id === courier.id, resetPassword.status)
const oldSession = await mobile('/courier/tasks', 'GET', undefined, courierToken)
check('修改配送员密码后旧Token失效', [401, 403].includes(oldSession.status), oldSession.status)

const pagination = await mobile('/orders?pageNum=2147483647&pageSize=100')
check('极大分页参数不导致500', pagination.status < 500, pagination.status)
const wrongMethod = await mobile('/products', 'PATCH', {})
check('错误HTTP Method返回405而非500', wrongMethod.status === 405, wrongMethod.status)
const missingFileForm = new FormData(); missingFileForm.append('wrong', new Blob(['x']), 'x.png')
const missingFile = await fetch(base + '/qicai/mobile/upload', { method: 'POST', headers: { 'X-Qicai-Token': c.token }, body: missingFileForm })
check('上传缺少file字段返回400', missingFile.status === 400, missingFile.status)

const removal = await a('/couriers/' + courier.id, 'DELETE')
check('未完成任务的配送员不可直接删除', removal.status === 409, removal.status)
const updateB = await mobile('/profile', 'PUT', { ...b, contactName: '仅修改联系人', version: (await mobile('/profile', 'GET', undefined, b.token)).data.version }, b.token)
check('企业仅改联系人不重复触发资质审核', updateB.status === 200 && updateB.data.status === 'ACTIVE', updateB.data?.status)
const forged = await mobile('/orders', 'POST', { productId: product.id, quantity: 1, price: 0.01, payableAmount: 0.01, audience: 'business', customerId: b.id, idempotencyKey: 'tamper-' + stamp })
check('篡改身份价格及用户ID不能影响计价归属', forged.status === 201 && Number(forged.data.payableAmount) === 23.45 && forged.data.contactName === c.contactName, forged.data?.payableAmount)
const adminBigPage = await a('/customers?pageNum=2147483647&pageSize=100')
check('后台超大分页不会整数溢出', adminBigPage.status === 200, adminBigPage.status)
const finishedWithoutProof = await mobile('/orders/' + order.id + '/receipt', 'POST', { signedName: 'QA' })
check('客户签收也必须提供真实凭证', finishedWithoutProof.status === 400, finishedWithoutProof.status)
let blockedLogin
for (let i = 0; i < 6; i++) blockedLogin = await mobile('/auth/login', 'POST', { audience: 'consumer', phone: c.phone, password: 'Incorrect123!' })
check('移动端密码连续错误五次后限流', blockedLogin.status === 429, blockedLogin.status)

await mkdir(output, { recursive: true })
await writeFile(new URL('edge-results.json', output), JSON.stringify({ at: new Date().toISOString(), results }, null, 2))
await writeFile(new URL(`edge-results-${stamp}.json`, output), JSON.stringify({ at: new Date().toISOString(), results }, null, 2))
console.log(`SUMMARY ${results.filter(r => r.passed).length}/${results.length}`)
if (results.some(r => !r.passed)) process.exitCode = 1
