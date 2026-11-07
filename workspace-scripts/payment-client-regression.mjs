// Real client orchestration with explicitly synthetic uni/WeChat/API doubles.
import fs from 'node:fs/promises'
import path from 'node:path'
import vm from 'node:vm'
import assert from 'node:assert/strict'
const root=path.resolve(import.meta.dirname,'..')
const out=path.resolve(process.env.QICAI_EVIDENCE_DIR||path.join(root,'tmp/payment-20261007'))
const privacySource=(await fs.readFile(path.join(root,'三端配送小程序/common/privacy.js'),'utf8')).replace(/^import .*$/gm,'').replace(/\bexport\s+/g,'')
const raw=await fs.readFile(path.join(root,'三端配送小程序/common/payment.js'),'utf8')
const results=[]
function client({platform=true,states=['UNPAID','PAID'],paymentFailure,gateLogin,gatePrepay,accepted=true}={}) {
  let token='synthetic-original', index=0, loginResolve, prepayResolve
  const requests=[],toasts=[]
  const source=raw.replace(/^import .*$/gm,'').replace('export async function','async function')
    .replace(/\s*\/\/ #ifdef MP-WEIXIN\s*available = true\s*\/\/ #endif/,platform?'\n available = true':'')
  const context=vm.createContext({reactive:v=>v,getToken:()=>token,setTimeout:r=>r(),
    api:{paymentStatus:async id=>{requests.push(['query',id]);return {paymentStatus:states[Math.min(index++,states.length-1)]}},
      bindWechatIdentity:async b=>requests.push(['identity',b.code]),
      wechatPrepay:async id=>{requests.push(['prepay',id]);if(gatePrepay)await new Promise(r=>prepayResolve=r);return {timeStamp:'123',nonceStr:'synthetic',package:'prepay_id=synthetic',signType:'RSA',paySign:'synthetic'}}},
    uni:{getStorageSync:()=>({version:'2026-10-27.1',accepted}),showToast:body=>toasts.push(body.title),login:body=>{requests.push(['login']);if(gateLogin)loginResolve=()=>body.success({code:'synthetic-code'});else body.success({code:'synthetic-code'})},
      requestPayment:body=>{requests.push(['payment',body.package]);if(paymentFailure)body.fail({errMsg:paymentFailure});else body.success({})}}})
  vm.runInContext(privacySource,context);vm.runInContext(source+'\nglobalThis.pay = payForOrder',context)
  return {pay:context.pay,requests,toasts,switchAccount:()=>token='synthetic-other',releaseLogin:()=>loginResolve(),releasePrepay:()=>prepayResolve()}
}
async function test(name,run){try{await run();results.push({name,status:'已实测通过'})}catch(e){results.push({name,status:'失败',error:e.message})}}
await test('payment-requires-consent-before-any-provider-action',async()=>{const c=client({accepted:false});await assert.rejects(c.pay(7),e=>e.qicaiHandled);assert.equal(c.requests.length,0)})
await test('client-success-only-verified-server-state-confirms-payment',async()=>{const c=client();assert.equal((await c.pay(7)).paymentStatus,'PAID');assert.deepEqual(c.requests.map(x=>x[0]),['query','login','identity','prepay','payment','query'])})
await test('client-success-with-server-unpaid-does-not-report-paid',async()=>{const c=client({states:['UNPAID']});assert.equal((await c.pay(7)).paymentStatus,'UNPAID');assert.equal(c.requests.filter(x=>x[0]==='query').length,4);assert(c.toasts.some(x=>x.includes('确认中')))})
await test('cancelled-sheet-retains-unpaid-and-allows-later-retry',async()=>{const c=client({states:['UNPAID'],paymentFailure:'requestPayment:fail cancel'});await assert.rejects(c.pay(7),e=>e.qicaiHandled&&e.message.includes('已取消'));assert.equal(c.requests.filter(x=>x[0]==='query').length,2)})
await test('lost-payment-response-reconciles-real-paid-result',async()=>{const c=client({paymentFailure:'requestPayment:fail network'});assert.equal((await c.pay(7)).paymentStatus,'PAID')})
await test('H5-explicitly-refuses-native-payment-without-provider-calls',async()=>{const c=client({platform:false});await assert.rejects(c.pay(7),e=>e.qicaiHandled&&e.message.includes('小程序'));assert.equal(c.requests.length,0)})
await test('account-change-during-login-cannot-bind-new-account',async()=>{const c=client({gateLogin:true});const p=c.pay(7);await new Promise(r=>setImmediate(r));c.switchAccount();c.releaseLogin();await assert.rejects(p,e=>e.qicaiHandled&&e.message.includes('账号'));assert.equal(c.requests.filter(x=>['identity','prepay','payment'].includes(x[0])).length,0)})
await test('account-change-during-prepay-cannot-open-old-payment-sheet',async()=>{const c=client({gatePrepay:true});const p=c.pay(7);await new Promise(r=>setImmediate(r));c.switchAccount();c.releasePrepay();await assert.rejects(p,e=>e.qicaiHandled&&e.message.includes('账号'));assert.equal(c.requests.filter(x=>x[0]==='payment').length,0)})
await fs.mkdir(out,{recursive:true});await fs.writeFile(path.join(out,'payment-client-results.json'),JSON.stringify({kind:'component orchestration; uni/payment/provider doubles; not real WeChat',results},null,2))
for(const r of results)console.log(`${r.status}: ${r.name}`)
if(results.some(x=>x.status==='失败'))process.exitCode=1
