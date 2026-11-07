// Executes the real page script methods with deterministic API doubles.
// These are component-logic regressions, not browser or real integration tests.
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import path from 'node:path'
import vm from 'node:vm'
import { spawnSync } from 'node:child_process'

const root = path.resolve(import.meta.dirname, '..')
const out = path.resolve(process.env.QICAI_EVIDENCE_DIR || path.join(root, 'tmp/acceptance-20261004'))
const results = []
const noop = () => {}
async function script(file) {
  return (await fs.readFile(path.join(root, file), 'utf8')).match(/<script(?: setup)?>([\s\S]*?)<\/script>/)[1]
    .replace(/^import .*$/gm, '').replaceAll('import.meta.env.VITE_APP_BASE_API', '"/prod-api"')
}
async function page(file, api, extra = {}) {
  const context = vm.createContext({ api: { catalogBrands: async () => [], ...api }, uni: { getStorageSync: noop, setStorageSync: noop, showToast: noop, redirectTo: noop },
    BrandShelf: {}, AppHeader: {}, BuyerTabbar: {}, CourierTabbar: {}, ProductThumb: {}, StatusTag: {},
    getSessionRevision: () => 0, getToken: () => 'synthetic-session', getAudience: () => 'consumer', getAudienceMeta: () => ({}), setAudience: value => value,
    audiences: { consumer: {} }, productKind: () => 'box', statusText: value => value, ...extra })
  vm.runInContext((await script(file)).replace('export default', 'globalThis.component ='), context)
  const component = context.component, instance = component.data()
  for (const [name, method] of Object.entries(component.methods || {})) instance[name] = method.bind(instance)
  for (const [name, method] of Object.entries(component.computed || {})) Object.defineProperty(instance, name, { get: method.bind(instance) })
  return { component, instance }
}
async function admin(api, warnings = []) {
  const context = vm.createContext({ api, ref: value => ({ value }), reactive: value => value,
    computed: get => ({ get value() { return get() } }), watch: noop, onBeforeUnmount: noop, URL,
    useRoute: () => ({ meta: { section: 'orders' } }),
    ElMessage: { warning: message => warnings.push(message), success: noop }, ElMessageBox: { confirm: async () => {} } })
  vm.runInContext(await script('RuoYi-Vue3/src/views/qicai/index.vue') + '\nglobalThis.test = { load, rows, filters, couponDates, couponForm, saveCoupon, changeCustomer }', context)
  return context.test
}
async function test(name, run) {
  try { await run(); results.push({ name, status: '已实测通过' }); console.log('PASS ' + name) }
  catch (error) { results.push({ name, status: '失败', error: error.message }); console.error('FAIL ' + name + ': ' + error.message) }
}

await test('F-001 首页状态统计包含第 100 条以后的订单及已取货状态', async () => {
  const orders = [...Array.from({ length: 100 }, () => ({ status: 'COMPLETED' })),
    { status: 'IN_DELIVERY' }, { status: 'ASSIGNED' }, { status: 'PICKED_UP' }]
  const { instance } = await page('三端配送小程序/pages/buyer/home.vue', {
    profile: async () => ({ audience: 'consumer', status: 'ACTIVE' }), products: async () => ({ rows: [], total: 0, pageNum: 1, pageSize: 20 }), coupons: async () => [],
    orders: async ({ status, pageSize = 20, pageNum = 1 }) => { const filtered = orders.filter(order => !status || order.status === status); return { total: filtered.length, rows: filtered.slice((pageNum - 1) * pageSize, pageNum * pageSize) } }
  })
  await instance.load()
  assert.deepEqual(Array.from(instance.summary, item => item.value), [1, 3, 103, 0])
})

for (const lostStatus of ['PICKED_UP', 'IN_DELIVERY']) await test(`F-002 ${lostStatus} 已落库但响应丢失后在原页重试恢复且不重复上传`, async () => {
  let status = 'ASSIGNED', lose = true, uploads = 0, redirected = ''
  const transitions = []
  const { instance } = await page('三端配送小程序/pages/courier/check.vue', {
    courierTask: async () => ({ id: 7, status }),
    uploadImages: async () => { uploads++; return ['/profile/proof.png'] },
    updateTask: async (id, payload) => {
      if (payload.status !== status) {
        assert(['ASSIGNED:PICKED_UP', 'PICKED_UP:IN_DELIVERY'].includes(`${status}:${payload.status}`), `invalid transition ${status}:${payload.status}`)
        status = payload.status; transitions.push(status)
      }
      if (lose && status === lostStatus) { lose = false; throw Object.assign(new Error('response lost'), { qicaiHandled: true }) }
      return { id, status }
    }
  }, { uni: { showToast: noop, redirectTo: ({ url }) => { redirected = url } } })
  instance.id = 7; instance.task = { status }; instance.goods = [{ checked: true }]; instance.photos = ['local.png']
  await instance.start().catch(error => { if (!error.qicaiHandled) throw error })
  await instance.start()
  assert.equal(status, 'IN_DELIVERY')
  assert.equal(redirected, '/pages/courier/navigation?id=7')
  assert.equal(uploads, 1, 'persisted pickup proof must be reused')
  assert.deepEqual(transitions, ['PICKED_UP', 'IN_DELIVERY'])
})

await test('F-003 后台清空优惠券日期后显示校验错误且不发送保存请求', async () => {
  const warnings = []; let writes = 0
  const model = await admin({ createCoupon: async () => { writes++ } }, warnings)
  model.couponForm.name = 'test coupon'; model.couponDates.value = null
  await model.saveCoupon()
  assert.equal(writes, 0); assert.equal(warnings.length, 1)
})

await test('F-004 后台旧搜索延迟返回不能覆盖最新筛选结果', async () => {
  const responses = []
  const model = await admin({ listOrders: parameters => new Promise(resolve => responses.push({ parameters, resolve })) })
  model.filters.keyword = 'old'; const first = model.load()
  model.filters.keyword = 'new'; const second = model.load()
  responses[1].resolve({ data: { rows: [{ orderNo: 'new' }], total: 1 } }); await second
  responses[0].resolve({ data: { rows: [{ orderNo: 'old' }], total: 1 } }); await first
  assert.equal(model.rows.value[0].orderNo, 'new')
})

await test('F-005 客户审核携带所见资料版本且冲突后刷新列表', async () => {
  let suppliedVersion, reloads = 0
  const warnings = []
  const model = await admin({
    reviewCustomer: async (id, status, version) => { suppliedVersion = version; throw Object.assign(new Error('资料已变化'), { qicaiHandled: true, response: { status: 409 } }) },
    listOrders: async () => { reloads++; return { data: { rows: [], total: 0 } } }
  }, warnings)
  await model.changeCustomer({ id: 7, version: 3, contactName: 'test' }, 'ACTIVE')
  assert.equal(suppliedVersion, 3); assert.equal(reloads, 1); assert.equal(warnings.length, 1)
})

await test('F-006 优惠券客群变化后INVALID券在已失效标签可见且不计入可领取', async () => {
  const { instance } = await page('三端配送小程序/pages/buyer/coupons.vue', {})
  instance.coupons = [{ couponId: 1, claimStatus: 'INVALID' }, { couponId: 2, claimStatus: 'EXPIRED' }, { couponId: 3, claimStatus: 'AVAILABLE' }]
  instance.active = '已失效'
  assert.deepEqual(Array.from(instance.filteredCoupons, coupon => coupon.couponId), [1, 2])
  assert.equal(instance.availableCount, 0)
})

await test('F-007 凭证下载兼容新旧受保护目录且拒绝外部URL', async () => {
  const requests = []
  const context = vm.createContext({ getToken: () => 'synthetic-session', http: async options => { requests.push(options); return { type: 'image/png' } } })
  const source = (await fs.readFile(path.join(root, 'RuoYi-Vue3/src/api/qicai/index.js'), 'utf8')).replace(/^import .*$/gm, '').replace(/^export /gm, '')
  vm.runInContext(source + '\nglobalThis.test = { getProofImage }', context)
  for (const file of ['/profile/upload/legacy.png', '/profile/qicai-proof/current.png']) {
    await context.test.getProofImage(file)
    assert.equal(requests.at(-1).url, file)
    assert.equal(requests.at(-1).method, 'get')
    assert.equal(requests.at(-1).responseType, 'blob')
    assert.equal(requests.at(-1).expectedSessionToken, 'synthetic-session')
  }
  await assert.rejects(context.test.getProofImage('https://outside.invalid/proof.png'), /凭证文件路径无效/)
  assert.equal(requests.length, 2)
})

await test('F-008 H5生产默认同源且忽略旧本地覆盖，显式构建配置及开发地址保持有效', async () => {
  const source = (await fs.readFile(path.join(root, '三端配送小程序/common/api.js'), 'utf8')).replace(/^export /gm, '').replaceAll('import.meta.env', 'ENV')
  for (const [environment, stored, expected] of [
    [{ PROD: true }, 'http://127.0.0.1:8082/qicai/mobile', '/qicai/mobile'],
    [{ PROD: true, VITE_QICAI_API_BASE: 'https://api.example.invalid/qicai/mobile' }, 'http://old.invalid', 'https://api.example.invalid/qicai/mobile'],
    [{ PROD: false }, '', 'http://127.0.0.1:8082/qicai/mobile'],
    [{ PROD: false }, 'http://127.0.0.1:15190/qicai/mobile', 'http://127.0.0.1:15190/qicai/mobile']
  ]) {
    const context = vm.createContext({ ENV: environment, uni: { getStorageSync: () => stored } })
    vm.runInContext(source + '\nglobalThis.test = { getApiBaseUrl }', context)
    assert.equal(context.test.getApiBaseUrl(), expected)
  }
})

await test('F-009 微信生产构建缺少或错误HTTPS配置明确退出1', async () => {
  for (const configured of ['', '/qicai/mobile', 'http://api.example.invalid/qicai/mobile']) {
    const result = spawnSync(process.execPath, ['scripts/uni.mjs', 'build', '-p', 'mp-weixin'], {
      cwd: path.join(root, '三端配送小程序'), env: { ...process.env, VITE_QICAI_API_BASE: configured }, encoding: 'utf8', timeout: 30000
    })
    assert.equal(result.status, 1, result.stderr)
    assert.match(result.stderr, /微信生产构建需要 VITE_QICAI_API_BASE=/)
  }
})

await fs.mkdir(out, { recursive: true })
await fs.writeFile(path.join(out, process.env.QICAI_REGRESSION_FILE || 'frontend-regression.json'), JSON.stringify({
  at: new Date().toISOString(), type: 'real component script with deterministic API doubles (not browser/integration)',
  results, exitCode: results.some(result => result.status === '失败') ? 1 : 0
}, null, 2))
process.exitCode = results.some(result => result.status === '失败') ? 1 : 0
