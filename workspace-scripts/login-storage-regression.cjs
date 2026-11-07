// Real Chromium login storage checks. Legacy ciphertext is synthetic and never logged.
const qa = require('./acceptance-context.cjs')()
const { chromium, expect } = require('./playwright-runtime.cjs').test
const assert = require('node:assert/strict'), fs = require('node:fs'), path = require('node:path'), crypto = require('node:crypto'), vm = require('node:vm')
const ADMIN = process.env.QICAI_ADMIN_URL
if (process.env.QICAI_ISOLATED_TEST !== '1' || ADMIN !== 'http://127.0.0.1:25186') throw Error('Use isolated acceptance runner')
const results = [], errors = [], startedAt = new Date().toISOString(); let browser, page
const pass = (name, detail) => { results.push({ name, passed: true, detail }); console.log('PASS ' + name) }
async function login() {
  await page.getByPlaceholder('账号', { exact: true }).fill('admin'); await page.getByPlaceholder('密码', { exact: true }).fill(qa.password)
  await page.getByRole('checkbox', { name: /记住(账号|密码)/ }).check(); await page.getByRole('button', { name: '登 录', exact: true }).click(); await page.waitForURL(u => !u.pathname.includes('login')); await page.waitForLoadState('networkidle')
}
;(async () => { try {
  browser = await chromium.launch({ headless: true, args: ['--no-proxy-server'] }); const context = await browser.newContext(); page = await context.newPage(); page.setDefaultTimeout(12000)
  page.on('pageerror', e => errors.push(e.message))
  // Exercise migration of the previous release's password cookie, using its own public key.
  const utility = fs.readFileSync(path.resolve('RuoYi-Vue3/src/utils/jsencrypt.js'), 'utf8'), expression = utility.match(/const publicKey = ([\s\S]*?)(?:\r?\n\r?\n)/)[1]
  assert.equal(expression.replace(/'(?:\\.|[^'\\])*'|"(?:\\.|[^"\\])*"/g, '').replace(/[+\s;]/g, ''), '', 'Legacy public key must only be literal concatenation')
  const publicKey = vm.runInNewContext(expression, {}, { timeout: 1000 })
  const cipher = crypto.publicEncrypt({ key: publicKey.startsWith('-----') ? publicKey : '-----BEGIN PUBLIC KEY-----\n' + publicKey + '\n-----END PUBLIC KEY-----', padding: crypto.constants.RSA_PKCS1_PADDING }, Buffer.from('LegacySynthetic123!')).toString('base64')
  await context.addCookies([{ name: 'password', value: encodeURIComponent(cipher), url: ADMIN }, { name: 'username', value: 'admin', url: ADMIN }, { name: 'rememberMe', value: 'true', url: ADMIN }])
  await page.goto(ADMIN + '/login', { waitUntil: 'networkidle' }); await expect(page.getByPlaceholder('账号', { exact: true })).toHaveValue('admin')
  const legacyPresent = (await context.cookies()).some(c => c.name === 'password'), autoFilled = Boolean(await page.getByPlaceholder('密码', { exact: true }).inputValue())
  results.push({ name: '旧版password cookie进入登录页即清除，密码不被解密预填', passed: !legacyPresent && !autoFilled, detail: { legacyCookiePresent: legacyPresent, passwordAutofilled: autoFilled } })
  assert(!legacyPresent && !autoFilled, 'Legacy recoverable password must be removed without autofill')
  await login(); assert(!(await context.cookies()).some(c => c.name === 'password')); pass('勾选记住账号实际登录后不保存可恢复password cookie', { passwordCookiePresent: false })
  await context.addCookies([{ name: 'password', value: encodeURIComponent(cipher), url: ADMIN }]); await page.goto(ADMIN + '/qicai/dashboard', { waitUntil: 'networkidle' }); await page.getByRole('heading', { name: '经营概览', exact: true }).waitFor()
  assert(!(await context.cookies()).some(c => c.name === 'password')); pass('已有有效JWT直接进入后台也清理旧password cookie并保留有效身份', { passwordCookiePresent: false, authenticatedPage: true })
  await page.locator('.avatar-wrapper').hover(); await page.getByText('退出登录', { exact: true }).click(); await page.locator('.el-message-box').getByRole('button', { name: '确定', exact: true }).click(); await page.waitForURL(/\/login/); await page.waitForLoadState('networkidle')
  await page.reload({ waitUntil: 'networkidle' }); await expect(page.getByPlaceholder('账号', { exact: true })).toHaveValue('admin'); await expect(page.getByPlaceholder('密码', { exact: true })).toHaveValue(''); assert(!(await context.cookies()).some(c => c.name === 'password'))
  pass('注销重新进入和刷新后只保留账号，密码为空且无password cookie', { rememberedUsername: 'admin', passwordAutofilled: false })
  assert.equal(errors.length, 0, JSON.stringify(errors)); await page.screenshot({ path: path.join(qa.out, 'login-account-only.png'), fullPage: true }); pass('登录存储回归无未处理浏览器异常', { errors: 0 })
} catch (e) { if (!results.some(r => !r.passed)) results.push({ name: 'Login storage validation', passed: false, error: e.stack }); process.exitCode = 1
} finally {
  if (page) await page.screenshot({ path: path.join(qa.out, 'login-storage-last.png'), fullPage: true }).catch(() => {}); if (browser) await browser.close()
  fs.writeFileSync(path.join(qa.out, 'login-storage-results.json'), JSON.stringify({ startedAt, endedAt: new Date().toISOString(), kind: 'real Chromium; legacy cookie and credentials synthetic', results, errors, exitCode: process.exitCode || 0 }, null, 2)); console.log(JSON.stringify(results, null, 2))
} })()
