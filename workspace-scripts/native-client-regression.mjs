// Real client/component source with controlled transport/device doubles; not a real phone test.
import fs from 'node:fs/promises'
import path from 'node:path'
import vm from 'node:vm'
import assert from 'node:assert/strict'
const root=path.resolve(import.meta.dirname,'..'),out=path.resolve(process.env.QICAI_EVIDENCE_DIR||'tmp/native-client-results')
const privacySource=(await fs.readFile(path.join(root,'三端配送小程序/common/privacy.js'),'utf8')).replace(/^import .*$/gm,'').replace(/\bexport\s+/g,'')
const source=(await fs.readFile(path.join(root,'三端配送小程序/common/api.js'),'utf8')).replace(/^import .*$/gm,'').replace(/import\.meta\.env/g,'environment').replace(/\bexport\s+/g,'')
const locationSource=(await fs.readFile(path.join(root,'三端配送小程序/common/location.js'),'utf8')).replace(/^import .*$/gm,'').replace(/\bexport\s+/g,'')
const results=[]
function client(){
 const storage=new Map([['qc_privacy_consent',{version:'2026-10-27.1',accepted:true}],['qc_customer_token','old-customer'],['qc_courier_token','old-courier'],['qc_profile','old-profile']]),uploads=[],downloads=[],routes=[],released=[]
 const uni={getStorageSync:k=>storage.get(k),setStorageSync:(k,v)=>storage.set(k,v),removeStorageSync:k=>storage.delete(k),uploadFile:r=>uploads.push(r),downloadFile:r=>downloads.push(r),showToast(){},reLaunch:r=>{routes.push(r.url);r.complete?.()}}
 const context=vm.createContext({reactive:v=>v,environment:{PROD:true,VITE_QICAI_API_BASE:'https://dingdan.uuok.top/qicai/mobile'},uni,URL:{revokeObjectURL:u=>released.push(u)},setTimeout:f=>f()})
 vm.runInContext(privacySource,context);vm.runInContext(source+'\nglobalThis.actual={api,setToken,releaseProof}',context)
 return {...context.actual,storage,uploads,downloads,routes,released}
}
async function test(name,run){try{await run();results.push({name,passed:true})}catch(e){results.push({name,passed:false,error:e.stack})}}
await test('current upload 401 clears profile and returns to correct login',async()=>{
 for(const role of ['customer','courier']){const c=client(),p=c.api.uploadImage('fixture.png',role);c.uploads[0].success({statusCode:401,data:'{"code":401}'});await assert.rejects(p);assert.equal(c.storage.has(role==='courier'?'qc_courier_token':'qc_customer_token'),false);assert.match(c.routes[0],role==='courier'?/audience=courier/:/audience=consumer/)}
})
await test('private image download sends role header with no token in URL',async()=>{
 const c=client(),p=c.api.downloadProof('/profile/qicai-proof/2026/10/16/proof.png','courier');const r=c.downloads[0];assert.equal(r.header['X-Qicai-Token'],'old-courier');assert.equal(r.url,'https://dingdan.uuok.top/profile/qicai-proof/2026/10/16/proof.png');assert(!r.url.includes('old-courier'));r.success({statusCode:200,tempFilePath:'blob:private-image'});assert.equal(await p,'blob:private-image');c.releaseProof('blob:private-image');assert.deepEqual(c.released,['blob:private-image'])
})
await test('untrusted attachment URL and traversal are rejected before network',async()=>{
 const c=client();for(const p of ['https://evil.example/proof.png','//evil.example/proof.png','/profile/qicai-proof/../avatar.png','/profile/qicai-proof/%2e%2e/avatar.png','/profile/qicai-proof/a.png?token=x'])await assert.rejects(c.api.downloadProof(p));assert.equal(c.downloads.length,0)
})
await test('late private download cannot display previous account image',async()=>{
 const c=client(),p=c.api.downloadProof('/profile/qicai-proof/proof.png');c.setToken('new-customer');c.downloads[0].success({statusCode:200,tempFilePath:'blob:old-private'});await assert.rejects(p);assert.deepEqual(c.released,['blob:old-private']);assert.equal(c.storage.get('qc_customer_token'),'new-customer')
})
await test('private image 401 returns to login and releases error blob',async()=>{
 const c=client(),p=c.api.downloadProof('/profile/qicai-proof/proof.png');c.downloads[0].success({statusCode:401,tempFilePath:'blob:error'});await assert.rejects(p);assert.equal(c.routes.length,1);assert.equal(c.storage.has('qc_profile'),false);assert.deepEqual(c.released,['blob:error'])
})
await test('gallery unmount discards delayed images and resets preview',async()=>{
 const script=(await fs.readFile(path.join(root,'三端配送小程序/components/ProofGallery.vue'),'utf8')).split('<script>')[1].split('</script>')[0].replace(/^import .*$/gm,'').replace('export default','globalThis.component=')
 let resolve;const released=[];const ctx=vm.createContext({api:{downloadProof:()=>new Promise(r=>resolve=r)},releaseProof:p=>released.push(p),uni:{$on(){},$off(){}}});vm.runInContext(script,ctx)
 const c={...ctx.component.data(),...ctx.component.methods,files:['/profile/qicai-proof/proof.png'],role:'customer'};const p=c.load();c.selected='old-preview';ctx.component.beforeUnmount.call(c);resolve('blob:late');await p;assert.equal(c.images.length,0);assert.equal(c.selected,'');assert.deepEqual(released,['blob:late'])
})
await test('leaving login invalidates delayed successful authentication',async()=>{
 const script=(await fs.readFile(path.join(root,'三端配送小程序/pages/auth/login.vue'),'utf8')).split('<script>')[1].split('</script>')[0].replace(/^import .*$/gm,'').replace('export default','globalThis.component=')
 let resolve;const writes=[],routes=[];const ctx=vm.createContext({AppHeader:{},StatusTag:{},getAudience:()=> 'consumer',getSessionRevision:()=>0,audiences:{consumer:{}},setAudience:v=>v,setToken:t=>writes.push(t),api:{login:()=>new Promise(r=>resolve=r)},uni:{showToast(){},setStorageSync:(...v)=>writes.push(v),reLaunch:r=>routes.push(r)}});vm.runInContext(script,ctx)
 const c={...ctx.component.data(),...ctx.component.methods,phone:'18800000123',password:'Synthetic123!'};const p=c.login();ctx.component.onHide.call(c);resolve({token:'late-old-token',profile:{audience:'consumer'}});await p;assert.equal(writes.length,0);assert.equal(routes.length,0);assert.equal(c.submitting,false)
})
await test('image selection cancellation preserves photos and denial has feedback',async()=>{
 const script=(await fs.readFile(path.join(root,'三端配送小程序/common/device.js'),'utf8')).replace(/^import .*$/gm,'').replace(/\bexport\s+/g,'');let selection;const messages=[];const ctx=vm.createContext({requireNativePrivacy:async()=>{},uni:{chooseImage:o=>selection=o,showToast:o=>messages.push(o.title)}});vm.runInContext(script+'\nglobalThis.choose=chooseProofImages',ctx)
 let photos=['existing'];await ctx.choose(photos,v=>photos=v);selection.fail({errMsg:'chooseImage:fail cancel'});assert.deepEqual(photos,['existing']);assert.equal(messages.length,0);selection.fail({errMsg:'chooseImage:fail auth deny'});assert.equal(messages.length,1);assert.match(messages[0],/权限/)
})
for(const [file,apiName,finished] of [['buyer/delivery','order','COMPLETED'],['courier/task','courierTask','DELIVERED']]) {
 await test(file+' ignores older successful and failed refreshes',async()=>{
  const script=(await fs.readFile(path.join(root,'三端配送小程序/pages/'+file+'.vue'),'utf8')).split('<script>')[1].split('</script>')[0].replace(/^import .*$/gm,'').replace('export default','globalThis.component=')
  // Stable identity for these refresh-order tests; location predicates use the real source.
  const pending=[],context=vm.createContext({AppHeader:{},StatusTag:{},ProductThumb:{},ProofGallery:{},getSessionRevision:()=>0,statusText:s=>s,paymentText:s=>s,paymentExpired:()=>false,productKind:()=>'',api:{[apiName]:()=>new Promise((resolve,reject)=>pending.push({resolve,reject}))}});vm.runInContext(locationSource,context);vm.runInContext(script,context)
  const page={...context.component.data(),...context.component.methods,id:7,privacyGranted:true};const first=page.load(),second=page.load();pending[1].resolve({id:7,status:finished,items:[],delivery:{status:'DELIVERED',events:[]}});await second;pending[0].resolve({id:7,status:'IN_DELIVERY',items:[],delivery:{status:'IN_DELIVERY',events:[]}});await first;assert.equal((page.order||page.task)[apiName==='order'?'status':'statusRaw'],finished)
  const older=page.load(),latest=page.load();pending[3].resolve({id:7,status:finished,items:[],delivery:{status:'DELIVERED',events:[]}});await latest;pending[2].reject({message:'late error',qicaiHandled:true});await older;assert.equal(page.error,'');assert.equal(page.showProofs,true)
  const hidden=page.load();context.component.onHide.call(page);pending[4].resolve({id:7,status:finished,items:[],delivery:{status:'DELIVERED',events:[]}});await hidden;assert.equal(page.showProofs,false)
 })
}
await fs.mkdir(out,{recursive:true});await fs.writeFile(path.join(out,'native-client-results.json'),JSON.stringify({kind:'Actual source with synthetic transport/device failures; not native or real phone evidence',results},null,2))
for(const r of results)console.log(`${r.passed?'PASS':'FAIL'} ${r.name}${r.error?' '+r.error:''}`)
if(results.some(r=>!r.passed))process.exitCode=1
