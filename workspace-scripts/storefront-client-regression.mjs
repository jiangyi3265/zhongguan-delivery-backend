// Execute actual client code with controlled uni/wx transport. Not a real WeChat login.
import fs from 'node:fs/promises'
import path from 'node:path'
import vm from 'node:vm'
import assert from 'node:assert/strict'
const root=path.resolve(import.meta.dirname,'..'),out=path.resolve(process.env.QICAI_EVIDENCE_DIR||'tmp/storefront-client-results')
const apiSource=await fs.readFile(path.join(root,'三端配送小程序/common/api.js'),'utf8')
const loginSource=await fs.readFile(path.join(root,'三端配送小程序/common/wechat-session.js'),'utf8')
const privacySource=await fs.readFile(path.join(root,'三端配送小程序/common/privacy.js'),'utf8')
function preprocess(source,platform){return source.replace(/\/\/ #ifdef (MP-WEIXIN|H5)\r?\n([\s\S]*?)\/\/ #endif/g,(_,tag,body)=>tag===platform?body:'').replace(/^import .*$/gm,'').replace(/import\.meta\.env/g,'environment').replace(/\bexport\s+/g,'')}
function client(platform='MP-WEIXIN',accepted=true){
 const storage=new Map(accepted?[['qc_privacy_consent',{version:'2026-10-27.1',accepted:true}]]:[]),requests=[],logins=[],events=[],toasts=[]
 const uni={getStorageSync:k=>storage.get(k),setStorageSync:(k,v)=>storage.set(k,v),removeStorageSync:k=>storage.delete(k),$emit:(...e)=>events.push(e),login:o=>logins.push(o),request:o=>requests.push(o),showToast:o=>toasts.push(o.title)}
 const context=vm.createContext({uni,reactive:v=>v,environment:{PROD:true},setTimeout,URL,setAudience:v=>{storage.set('qc_audience',v);return v}})
 vm.runInContext(preprocess(privacySource,platform)+'\n'+preprocess(apiSource,platform)+'\n'+preprocess(loginSource,platform)+'\nglobalThis.actual={api,setToken,clearToken,getSessionRevision,ensureWechatSession,hasCustomerSession}',context)
 const result={...context.actual,storage,requests,logins,events,toasts}
 result.respond=(index,{audience='consumer',token='trusted-session',status=200,profile={id:7,status:'ACTIVE'},msg='rejected'}={})=>requests[index].success({statusCode:status,data:status===200?{code:200,data:{token,profile:{...profile,audience}}}:{code:status,msg}})
 return result
}
const flush=()=>new Promise(r=>setImmediate(r)),results=[]
async function test(name,run){try{await run();results.push({name,passed:true})}catch(e){results.push({name,passed:false,error:e.stack})}}
await test('no consent blocks WeChat login and personal-information requests',async()=>{
 const c=client('MP-WEIXIN',false);await assert.rejects(c.ensureWechatSession({owner:{}}),/同意/);assert.equal(c.logins.length,0);assert.equal(c.requests.length,0);assert.equal([...c.storage.keys()].filter(k=>k!=='qc_privacy_consent').length,0)
})
await test('C purchase creates only one wx.login and one anonymous backend request for repeated click',async()=>{
 const c=client(),owner={},a=c.ensureWechatSession({owner}),b=c.ensureWechatSession({owner});assert.equal(a,b);assert.equal(c.logins.length,1)
 c.logins[0].success({code:'single-use-code'});await flush();assert.equal(c.requests.length,1);assert.equal(c.requests[0].url,'https://dingdan.uuok.top/qicai/mobile/auth/wechat/login');assert.equal(c.requests[0].header['X-Qicai-Token'],undefined);assert.equal(c.requests[0].data.audience,'consumer');c.respond(0);await a
 assert.equal(c.storage.get('qc_customer_token'),'trusted-session');assert.equal(c.storage.get('qc_profile').id,7)
})
await test('leaving before wx.login resolves prevents API call and session write',async()=>{
 const c=client();let active=true;const p=c.ensureWechatSession({owner:{},isCurrent:()=>active}),rejected=assert.rejects(p,e=>e.code==='STALE_LOGIN');active=false;c.logins[0].success({code:'late'});await rejected;assert.equal(c.requests.length,0);assert.equal([...c.storage.keys()].filter(k=>k!=='qc_privacy_consent').length,0)
})
await test('leaving after backend dispatch discards delayed valid session',async()=>{
 const c=client();let active=true;const p=c.ensureWechatSession({owner:{},isCurrent:()=>active}),rejected=assert.rejects(p,e=>e.code==='STALE_LOGIN');c.logins[0].success({code:'late-server'});await flush();active=false;c.respond(0);await rejected;assert.equal([...c.storage.keys()].filter(k=>k!=='qc_privacy_consent').length,0)
})
await test('account switch and ABA token restoration invalidate pending anonymous auth',async()=>{
 const c=client();c.setToken('old');const p=c.ensureWechatSession({owner:{}}),rejected=assert.rejects(p,e=>e.code==='STALE_LOGIN');c.logins[0].success({code:'aba'});await flush();c.setToken('other');c.setToken('old');c.respond(0);await rejected;assert.equal(c.storage.get('qc_customer_token'),'old');assert.equal(c.storage.has('qc_profile'),false)
})
await test('cross-role login cannot share an in-flight C login',async()=>{
 const c=client(),p=c.ensureWechatSession({owner:'c'});await assert.rejects(c.ensureWechatSession({audience:'business',owner:'b'}),e=>e.code==='LOGIN_BUSY');c.logins[0].success({code:'c'});await flush();c.respond(0);await p;assert.equal(c.logins.length,1)
})
await test('existing B session is not reused for retail checkout',async()=>{
 const c=client();c.setToken('b');c.storage.set('qc_profile',{id:3,audience:'business'});const p=c.ensureWechatSession({audience:'consumer',owner:{}});assert.equal(c.logins.length,1);c.logins[0].success({code:'c'});await flush();c.respond(0);await p;assert.equal(c.storage.get('qc_profile').audience,'consumer')
})
await test('wrong audience response cannot persist token/profile',async()=>{
 const c=client(),p=c.ensureWechatSession({owner:{}}),rejected=assert.rejects(p,e=>e.code==='INVALID_SESSION');c.logins[0].success({code:'wrong'});await flush();c.respond(0,{audience:'business'});await rejected;assert.equal([...c.storage.keys()].filter(k=>k!=='qc_privacy_consent').length,0)
})
await test('enterprise not registered then application gets a fresh code and pending B identity',async()=>{
 const c=client(),p=c.ensureWechatSession({owner:'b',audience:'business'}),rejected=assert.rejects(p,e=>e.status===404);c.logins[0].success({code:'lookup'});await flush();c.respond(0,{status:404});await rejected
 const q=c.ensureWechatSession({owner:'b',audience:'business',registration:{companyName:'隔离企业',contactName:'验收',phone:'18800001234',taxId:'SYNTHETIC',address:'隔离地址'}});assert.equal(c.logins.length,2);c.logins[1].success({code:'application'});await flush();assert.match(c.requests[1].url,/business-register$/);assert.equal(c.requests[1].data.code,'application');c.respond(1,{audience:'business',profile:{id:8,status:'PENDING'}});await q;assert.equal(c.storage.get('qc_profile').status,'PENDING')
})
await test('provider error clears flight and retry always requests a new code',async()=>{
 const c=client(),p=c.ensureWechatSession({owner:'c'}),rejected=assert.rejects(p,e=>e.status===503);c.logins[0].success({code:'failed-provider'});await flush();c.respond(0,{status:503});await rejected
 const q=c.ensureWechatSession({owner:'c'});assert.equal(c.logins.length,2);c.logins[1].success({code:'fresh-retry'});await flush();assert.equal(c.requests[1].data.code,'fresh-retry');c.respond(1);await q
})
await test('device failure or missing code never calls backend or writes account',async()=>{
 for(const mode of ['fail','empty']){const c=client(),p=c.ensureWechatSession({owner:{}}),rejected=assert.rejects(p,e=>e.code==='WECHAT_LOGIN_FAILED');if(mode==='fail')c.logins[0].fail({errMsg:'cancel'});else c.logins[0].success({});await rejected;assert.equal(c.requests.length,0);assert.equal([...c.storage.keys()].filter(k=>k!=='qc_privacy_consent').length,0)}
})
await test('H5 gives explicit unsupported state and never fabricates WeChat session',async()=>{
 const c=client('H5');await assert.rejects(c.ensureWechatSession({owner:{}}),e=>e.code==='WECHAT_UNAVAILABLE');assert.equal(c.logins.length,0);assert.equal(c.requests.length,0);assert.equal([...c.storage.keys()].filter(k=>k!=='qc_privacy_consent').length,0)
})
await test('valid existing C password session keeps compatibility without wx.login',async()=>{
 const c=client('H5');c.setToken('password-c');c.storage.set('qc_profile',{id:9,audience:'consumer',authMethod:'PASSWORD'});assert.equal((await c.ensureWechatSession({owner:{}})).id,9);assert.equal(c.logins.length,0);assert.equal(c.storage.get('qc_customer_token'),'password-c')
})
const registrationSource=(await fs.readFile(path.join(root,'三端配送小程序/pages/auth/register.vue'),'utf8')).split('<script>')[1].split('</script>')[0].replace(/^import .*$/gm,'').replace('export default','globalThis.component=')
function registration(nativeWechat,options){
 const routes=[],context=vm.createContext({AppHeader:{},isWechatMiniProgram:()=>nativeWechat,uni:{redirectTo:r=>routes.push(r.url),reLaunch:r=>routes.push(r.url)}})
 vm.runInContext(registrationSource,context);const page={...context.component.data(),...context.component.methods};context.component.onLoad.call(page,options);return {page,routes}
}
await test('legacy consumer registration returns to selected product without login or SMS collection',async()=>{
 const {routes}=registration(true,{audience:'consumer',productId:'3'});assert.deepEqual(routes,['/pages/catalog/product?id=3'])
 const invalid=registration(true,{audience:'unknown',productId:'3&audience=business'});assert.deepEqual(invalid.routes,['/pages/index/index'])
})
await test('legacy enterprise registration preserves invitation into the WeChat certification form',async()=>{
 const code='1234567890abcdef1234567890abcdef',r=registration(true,{audience:'business',salesInviteCode:code});assert.deepEqual(r.routes,['/pages/auth/business?salesInviteCode='+code])
})
await test('browser registration remains a guide and existing account login keeps product context',async()=>{
 const r=registration(false,{audience:'consumer',productId:'3'});assert.deepEqual(r.routes,[]);r.page.continueRegistration();assert.deepEqual(r.routes,[]);r.page.existing();assert.deepEqual(r.routes,['/pages/auth/login?audience=consumer&productId=3'])
})
await test('courier cannot enter a self-service customer registration flow',async()=>{
 assert.deepEqual(registration(true,{audience:'courier'}).routes,['/pages/auth/login?audience=courier'])
})
await fs.mkdir(out,{recursive:true});await fs.writeFile(path.join(out,'storefront-client-results.json'),JSON.stringify({at:new Date().toISOString(),kind:'Actual source VM with synthetic transport/device doubles; not browser, native or real provider evidence',results},null,2))
for(const r of results)console.log(`${r.passed?'PASS':'FAIL'} ${r.name}${r.error?' '+r.error:''}`)
if(results.some(r=>!r.passed))process.exitCode=1
