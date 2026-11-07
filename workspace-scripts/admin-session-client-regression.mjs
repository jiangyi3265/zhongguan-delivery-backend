// Executes the real interceptor and store against controlled transport/dialogs.
// This is a client unit regression, not a browser or provider integration test.
import fs from 'node:fs/promises'
import path from 'node:path'
import vm from 'node:vm'
import assert from 'node:assert/strict'
const root=path.resolve(import.meta.dirname,'..'),out=path.resolve(process.env.QICAI_EVIDENCE_DIR||'tmp/admin-session')
const clean=s=>s.replace(/^import .*$/gm,'').replace(/import\.meta\.env/g,'environment').replace(/export default service/,'').replace(/export default useUserStore/,'').replace(/\bexport\s+/g,'')
const requestSource=clean(await fs.readFile(path.join(root,'RuoYi-Vue3/src/utils/request.js'),'utf8'))
const storeSource=clean(await fs.readFile(path.join(root,'RuoYi-Vue3/src/store/modules/user.js'),'utf8'))
const results=[],tick=()=>new Promise(r=>setImmediate(r))
function fixture(){
 let token='A',dialog,logoutResolve,logoutReject,storeOptions,store
 const hooks={},messages=[],logoutCalls=[],logins=[],infos=[],location={href:'unchanged',pathname:'/qicai/orders',search:'?page=2',hash:'',replace(url){this.href=url;this.replacements=(this.replacements||0)+1}}
 const service={interceptors:{request:{use:(ok,fail)=>Object.assign(hooks,{request:ok,requestError:fail})},response:{use:(ok,fail)=>Object.assign(hooks,{response:ok,responseError:fail})}}}
 const ctx=vm.createContext({console,environment:{VITE_APP_BASE_API:'/prod-api'},getToken:()=>token,removeToken:()=>token='',setToken:t=>token=t,
  axios:{defaults:{headers:{}},create:()=>service},ElNotification:{error:m=>messages.push(m)},ElMessage:Object.assign(m=>messages.push(m),{error:m=>messages.push(m)}),ElMessageBox:{confirm:()=>new Promise(r=>dialog=r)},
  cache:{session:{getJSON:()=>null,setJSON(){}}},errorCode:{default:'error'},tansParams:()=>'',location,
  useUserStore:()=>store})
 vm.runInContext(requestSource,ctx)
 const sctx=vm.createContext({getToken:()=>token,removeToken:()=>token='',setToken:t=>token=t,defineStore:(_name,options)=>{storeOptions=options;return{}},
  logout:t=>{logoutCalls.push(t);return new Promise((resolve,reject)=>{logoutResolve=resolve;logoutReject=reject})}})
 sctx.login=()=>new Promise(r=>logins.push(r));sctx.getInfo=()=>new Promise(r=>infos.push(r))
 vm.runInContext(storeSource,sctx)
 store={...storeOptions.state(),...storeOptions.actions,roles:['admin'],permissions:['*:*:*']}
 return {hooks,messages,location,logoutCalls,logins,infos,store,get token(){return token},switchCookie:()=>token='B',switch:()=>{token='B';store.token='B';store.roles=['new-role'];store.permissions=['new-permission']},confirm:()=>dialog(),finishLogout:()=>logoutResolve(),failLogout:e=>logoutReject(e)}
}
function config(f){return f.hooks.request({method:'get',url:'/qicai/admin/orders',headers:{}})}
function response(c,code=200){return {config:c,data:{code,data:{private:'A'}},request:{responseType:'json'}}}
async function test(name,run){try{await run();results.push({name,passed:true})}catch(e){results.push({name,passed:false,error:e.stack})}}
await test('request-binds-token-and-rejects-stale-expected-session-before-dispatch',async()=>{const f=fixture();assert.equal(config(f).headers.Authorization,'Bearer A');f.switch();await assert.rejects(f.hooks.request({method:'post',url:'/refund',headers:{},expectedSessionToken:'A'}),e=>e.qicaiSessionChanged)})
for(const code of [200,401,500])await test('late-'+code+'-cannot-render-or-end-new-session',async()=>{const f=fixture(),c=config(f);f.switch();await assert.rejects(f.hooks.response(response(c,code)),e=>e.qicaiSessionChanged);assert.equal(f.token,'B');assert.equal(f.messages.length,0);assert.equal(f.logoutCalls.length,0);assert.equal(f.location.href,'unchanged')})
await test('late-network-failure-does-not-show-old-session-error',async()=>{const f=fixture(),c=config(f);f.switch();await assert.rejects(f.hooks.responseError({message:'Network Error',config:c}),e=>e.qicaiSessionChanged);assert.equal(f.messages.length,0)})
await test('current-200-and-JSON-401-clear-expired-session-without-logout-call',async()=>{const f=fixture();assert.equal((await f.hooks.response(response(config(f)))).data.private,'A');await assert.rejects(f.hooks.response(response(config(f),401)),e=>e.status===401&&e.qicaiAuthExpired);assert.equal(f.token,'');assert.equal(f.store.roles.length,0);assert.equal(f.logoutCalls.length,0);assert.match(f.location.href,/^\/login\?expired=1&redirect=/)})
await test('HTTP-401-and-silent-polling-share-single-expiry-redirect',async()=>{const f=fixture(),a=config(f),b={...config(f),qicaiSilent:true};await assert.rejects(f.hooks.responseError({message:'Request failed with status code 401',config:b,response:{status:401,data:{msg:'unauthorized'}}}),e=>e.status===401&&e.qicaiHandled);await assert.rejects(f.hooks.responseError({message:'old 401',config:a,response:{status:401}}),e=>e.qicaiSessionChanged);assert.equal(f.token,'');assert.equal(f.location.replacements,1);assert.equal(f.messages.length,0)})
await test('late-HTTP-401-cannot-clear-new-login',async()=>{const f=fixture(),c=config(f);f.switch();await assert.rejects(f.hooks.responseError({message:'Request failed with status code 401',config:c,response:{status:401}}),e=>e.qicaiSessionChanged);assert.equal(f.token,'B');assert.equal(f.location.href,'unchanged')})
await test('anonymous-login-401-does-not-expire-another-session',async()=>{const f=fixture(),c=f.hooks.request({method:'post',url:'/login',headers:{isToken:false,repeatSubmit:false}});await assert.rejects(f.hooks.responseError({config:c,response:{status:401,data:{msg:'登录信息无效'}}}),e=>e.status===401&&!e.qicaiAuthExpired);assert.equal(f.token,'A');assert.equal(f.messages.length,1);assert.equal(f.location.href,'unchanged')})
await test('old-401-during-new-login-is-discarded-and-first-login-succeeds',async()=>{const f=fixture(),c=config(f),p=f.store.login({username:'A'});assert.equal(f.token,'');await assert.rejects(f.hooks.responseError({config:c,response:{status:401}}),e=>e.qicaiSessionChanged);f.logins[0]({token:'fresh'});await p;assert.equal(f.token,'fresh');assert.equal(f.location.href,'unchanged')})
await test('logout-network-failure-still-clears-local-expired-session',async()=>{const f=fixture(),p=f.store.logOut('A');f.failLogout(new Error('network'));assert.equal(await p,true);assert.equal(f.token,'');assert.equal(f.store.roles.length,0)})
await test('late-logout-success-preserves-new-token-and-permissions',async()=>{const f=fixture(),p=f.store.logOut('A');f.switch();f.finishLogout();assert.equal(await p,false);assert.equal(f.token,'B');assert.equal(f.store.roles[0],'new-role');assert.equal(f.store.permissions[0],'new-permission')})
await test('late-logout-error-preserves-new-session',async()=>{const f=fixture(),p=f.store.logOut('A');f.switch();f.failLogout(new Error('network'));assert.equal(await p,false);assert.equal(f.token,'B')})
await test('current-logout-clears-own-session-and-stale-logout-never-dispatches',async()=>{const f=fixture(),p=f.store.logOut('A');f.finishLogout();assert.equal(await p,true);assert.equal(f.token,'');assert.equal(f.store.roles.length,0);assert.equal(f.store.permissions.length,0);f.switch();assert.equal(await f.store.logOut('A'),false);assert.equal(f.logoutCalls.length,1)})
await test('old-loaded-identity-cannot-submit-using-another-tab-cookie',async()=>{const f=fixture();f.switchCookie();await assert.rejects(f.hooks.request({method:'post',url:'/refund',headers:{}}),e=>e.qicaiSessionChanged);assert.equal(f.store.token,'A');assert.equal(f.token,'B');assert.equal(f.messages.length,1)})
await test('late-login-cannot-overwrite-another-tab-account',async()=>{const f=fixture(),p=f.store.login({username:'A',password:'synthetic'});f.switch();f.logins[0]({token:'late-A'});await assert.rejects(p,e=>e.qicaiSessionChanged);assert.equal(f.token,'B');assert.equal(f.store.token,'B')})
await test('only-latest-login-intent-can-set-token',async()=>{const f=fixture(),a=f.store.login({username:'A'}),b=f.store.login({username:'B'});f.logins[0]({token:'stale'});await assert.rejects(a,e=>e.qicaiSessionChanged);assert.equal(f.token,'');f.logins[1]({token:'latest'});await b;assert.equal(f.token,'latest')})
await test('late-identity-cannot-overwrite-new-roles',async()=>{const f=fixture(),p=f.store.getInfo();f.switch();f.infos[0]({user:{userName:'A'},roles:['admin'],permissions:['*:*:*']});await assert.rejects(p,e=>e.qicaiSessionChanged);assert.equal(f.store.roles[0],'new-role')})
await test('route-generation-propagates-network-and-session-errors',async()=>{
 const text=clean(await fs.readFile(path.join(root,'RuoYi-Vue3/src/store/modules/permission.js'),'utf8')).replace("default usePermissionStore",'').replace(/import\.meta\.glob\([^\n]+\)/,'{}')
 let token='A',resolve,reject,options;const added=[]
 const ctx=vm.createContext({getToken:()=>token,getRouters:()=>new Promise((a,b)=>{resolve=a;reject=b}),defineStore:(_n,o)=>{options=o;return{}},constantRoutes:[],dynamicRoutes:[],router:{addRoute:r=>added.push(r)}})
 vm.runInContext(text,ctx);const store={...options.state(),...options.actions};let p=store.generateRoutes();const rejection=assert.rejects(p,/network/);reject(new Error('network'));await rejection
 p=store.generateRoutes();token='B';resolve({data:[]});await assert.rejects(p,e=>e.qicaiSessionChanged);assert.equal(added.length,0);assert.equal(store.routes.length,0)
})
await test('navigation-catches-route-rejection-and-does-not-logout-new-cookie',async()=>{
 const source=clean(await fs.readFile(path.join(root,'RuoYi-Vue3/src/permission.js'),'utf8'))
 let token='A',guard,routeReject,infoResolve;const next=[],logout=[]
 const user={token:'A',roles:[],getInfo:()=>new Promise(r=>infoResolve=r),logOut:async t=>{logout.push(t);return true}}
 const ctx=vm.createContext({getToken:()=>token,router:{beforeEach:f=>guard=f,afterEach(){},addRoute(){}},NProgress:{start(){},done(){},configure(){}},isRelogin:{show:false},useUserStore:()=>user,usePermissionStore:()=>({generateRoutes:()=>new Promise((_r,j)=>routeReject=j)}),useSettingsStore:()=>({setTitle(){}}),isHttp:()=>false,isPathMatch:(a,b)=>a===b,ElMessage:{error(){}},location:{reload(){}}})
 vm.runInContext(source,ctx);guard({path:'/qicai/orders',meta:{}},{},x=>next.push(x));infoResolve();await tick();token='B';routeReject(new Error('network'));await tick();assert.equal(next[0],false);assert.equal(logout.length,0)
})
await fs.mkdir(out,{recursive:true});await fs.writeFile(path.join(out,'admin-session-client-results.json'),JSON.stringify({kind:'real JS source; transport/dialog/store doubles',results},null,2))
for(const r of results)console.log(`${r.passed?'PASS':'FAIL'} ${r.name}${r.passed?'':': '+r.error}`)
if(results.some(r=>!r.passed))process.exitCode=1
