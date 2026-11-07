// Actual common/api.js with synthetic uni transport/storage; not browser or WeChat.
import fs from 'node:fs/promises'
import path from 'node:path'
import vm from 'node:vm'
import assert from 'node:assert/strict'
const root=path.resolve(import.meta.dirname,'..'),out=path.resolve(process.env.QICAI_EVIDENCE_DIR||'tmp/payment-20261007')
const privacySource=(await fs.readFile(path.join(root,'三端配送小程序/common/privacy.js'),'utf8')).replace(/^import .*$/gm,'').replace(/\bexport\s+/g,'')
const source=(await fs.readFile(path.join(root,'三端配送小程序/common/api.js'),'utf8')).replace(/^import .*$/gm,'').replace(/import\.meta\.env/g,'environment').replace(/\bexport\s+/g,'')
const results=[]
function client(){
 const storage=new Map([['qc_privacy_consent',{version:'2026-10-27.1',accepted:true}],['qc_customer_token','synthetic-A'],['qc_profile','profile-A'],['qc_courier_token','courier-A'],['qc_courier_profile','courier-profile-A']]),requests=[],uploads=[],routes=[],toasts=[]
 const context=vm.createContext({reactive:v=>v,environment:{PROD:false},setTimeout:r=>r(),uni:{getStorageSync:k=>storage.get(k),setStorageSync:(k,v)=>storage.set(k,v),removeStorageSync:k=>storage.delete(k),request:r=>requests.push(r),uploadFile:r=>uploads.push(r),showToast:r=>toasts.push(r.title),reLaunch:r=>{routes.push(r.url);r.complete?.()}}})
 vm.runInContext(privacySource,context);vm.runInContext(source+'\nglobalThis.actual={api,getToken,setToken,paymentText,paymentExpired}',context)
 return {...context.actual,storage,requests,uploads,routes,toasts,switchAccount(role='customer'){context.actual.setToken('synthetic-B',role);storage.set(role==='courier'?'qc_courier_profile':'qc_profile','profile-B')}}
}
async function test(name,run){try{await run();results.push({name,passed:true})}catch(e){results.push({name,passed:false,error:e.message})}}
await test('late-payment-query-401-cannot-clear-new-customer-session',async()=>{const c=client(),p=c.api.paymentStatus(7);assert.equal(c.requests[0].header['X-Qicai-Token'],'synthetic-A');c.switchAccount();c.requests[0].success({statusCode:401,data:{code:401,msg:'synthetic-expired'}});await assert.rejects(p,e=>e.qicaiHandled);assert.equal(c.getToken(),'synthetic-B');assert.equal(c.storage.get('qc_profile'),'profile-B');assert.equal(c.routes.length,0);assert.equal(c.toasts.length,0)})
await test('late-200-cannot-display-old-customer-payment-data',async()=>{const c=client(),p=c.api.paymentStatus(7);c.switchAccount();c.requests[0].success({statusCode:200,data:{code:200,data:{paymentStatus:'PAID',payableAmount:1}}});await assert.rejects(p,e=>e.qicaiHandled);assert.equal(c.getToken(),'synthetic-B');assert.equal(c.routes.length,0)})
await test('late-image-upload-401-cannot-clear-new-session',async()=>{const c=client(),p=c.api.uploadImage('synthetic.png');assert.equal(c.uploads[0].header['X-Qicai-Token'],'synthetic-A');c.switchAccount();c.uploads[0].success({statusCode:401,data:JSON.stringify({code:401,msg:'synthetic-expired'})});await assert.rejects(p,e=>e.qicaiHandled);assert.equal(c.getToken(),'synthetic-B');assert.equal(c.toasts.length,0)})
await test('late-logout-preserves-new-token-profile-and-prevents-navigation',async()=>{const c=client(),p=c.api.logout();c.switchAccount();c.requests[0].success({statusCode:200,data:{code:200}});assert.equal(await p,false);assert.equal(c.getToken(),'synthetic-B');assert.equal(c.storage.get('qc_profile'),'profile-B');assert.equal(c.routes.length,0)})
await test('current-401-still-clears-own-token-and-redirects',async()=>{const c=client(),p=c.api.paymentStatus(7);c.requests[0].success({statusCode:401,data:{code:401,msg:'synthetic-expired'}});await assert.rejects(p,e=>e.qicaiHandled);assert.equal(c.getToken(),'');assert.equal(c.routes.length,1);assert.match(c.routes[0],/pages\/auth\/login/)})
await test('current-logout-clears-own-profile-and-reports-navigation-allowed',async()=>{const c=client(),p=c.api.logout();c.requests[0].success({statusCode:200,data:{code:200}});assert.equal(await p,true);assert.equal(c.getToken(),'');assert.equal(c.storage.has('qc_profile'),false)})
await test('late-courier-401-preserves-both-role-sessions',async()=>{const c=client(),p=c.api.courierTasks();c.switchAccount('courier');c.requests[0].success({statusCode:401,data:{code:401}});await assert.rejects(p,e=>e.qicaiHandled);assert.equal(c.getToken('courier'),'synthetic-B');assert.equal(c.getToken(),'synthetic-A');assert.equal(c.routes.length,0)})
await test('payment-query-during-delayed-list-forces-final-reload',async()=>{
 const script=(await fs.readFile(path.join(root,'三端配送小程序/pages/buyer/orders.vue'),'utf8')).split('<script>')[1].split('</script>')[0].replace(/^import .*$/gm,'').replace('export default','globalThis.component =')
 let release,calls=0
 const row={id:7,status:'PENDING_SHIPMENT',paymentStatus:'PAID',payableAmount:49.36}
 const context=vm.createContext({AppHeader:{},BuyerTabbar:{},ProductThumb:{},StatusTag:{},CustomerAccess:{},getToken:()=> 'synthetic-session',getSessionRevision:()=>0,formatPrice:String,productKind:()=>'',statusText:s=>s,paymentText:client().paymentText,paymentExpired:client().paymentExpired,payForOrder:async()=>{},uni:{showToast(){}},api:{paymentStatus:async()=>({paymentStatus:'PAID'}),orders:()=>{calls++;return calls===1?new Promise(r=>release=r):Promise.resolve({rows:[row],total:1})}}})
 vm.runInContext(script,context);const page={...context.component.data(),...context.component.methods,pageActive:true}
 const old=page.load();await page.queryPayment({id:7});release({rows:[{...row,status:'PENDING_PAYMENT',paymentStatus:'UNPAID'}],total:1});await old
 assert.equal(page.orders[0].paymentStatus,'PAID');assert.equal(calls,2)
})
await test('real-deadline-helper-blocks-expired-unpaid-and-preserves-other-states',async()=>{const c=client();assert.equal(c.paymentExpired({paymentStatus:'UNPAID',paymentExpiresAt:Date.now()-1}),true);assert.equal(c.paymentExpired({paymentStatus:'UNPAID',paymentExpiresAt:Date.now()+60000}),false);assert.equal(c.paymentExpired({paymentStatus:'PAID',paymentExpiresAt:Date.now()-1}),false);assert.equal(c.paymentText('REFUNDING'),'退款处理中')})
await test('delivery-headline-matches-restored-phase-without-a-delivery-window',async()=>{
 const source=(await fs.readFile(path.join(root,'三端配送小程序/pages/buyer/delivery.vue'),'utf8')).split('<script>')[1].split('</script>')[0].replace(/^import .*$/gm,'').replace('export default','globalThis.component =')
 const c=client(),context=vm.createContext({AppHeader:{},StatusTag:{},ProofGallery:{},api:{},statusText:s=>s,paymentText:c.paymentText,paymentExpired:c.paymentExpired})
 vm.runInContext(source,context);const computed=context.component.computed
 for(const [phase,title] of [['ASSIGNED','配送员已接单'],['PICKED_UP','商品已取货'],['IN_DELIVERY','配送中，等待收货'],['DELIVERED','配送已完成'],['EXCEPTION','配送异常处理中']]){
  const data={order:{paymentStatus:'PAID',status:phase==='DELIVERED'?'COMPLETED':phase},delivery:{status:phase,courierName:'synthetic'}}
  assert.equal(computed.heroTitle.call(data),title);assert.equal(computed.canReceive.call(data),phase==='IN_DELIVERY');data.delivery.deliveryWindow='今日14:00-16:00';assert.equal(computed.heroTitle.call(data),title)
 }
 const data={order:{status:'IN_DELIVERY',paymentStatus:'PAID'},delivery:{status:'IN_DELIVERY'}}
 assert.equal(computed.heroCopy.call(data),'配送员正在配送，请保持电话畅通')
 data.delivery.deliveryWindow='今日14:00-16:00';assert.equal(computed.heroTitle.call(data),'配送中，等待收货');assert.match(computed.heroCopy.call(data),/预约时段：今日14:00-16:00/)
 data.order={status:'COMPLETED',paymentStatus:'PAID'};data.delivery={};assert.equal(computed.heroTitle.call(data),'订单已完成');assert.equal(computed.heroCopy.call(data),'本次配送已完成，签收信息已保存');data.order.paymentStatus='REFUNDING';assert.equal(computed.heroTitle.call(data),'退款处理中')
 data.order={paymentStatus:'UNPAID',status:'PENDING_PAYMENT',paymentExpiresAt:Date.now()-1000};assert.equal(computed.heroTitle.call(data),'付款期限已过')
})
await fs.mkdir(out,{recursive:true});await fs.writeFile(path.join(out,'session-client-results.json'),JSON.stringify({kind:'actual API client source; uni transport/storage doubles',results},null,2))
for(const r of results)console.log(`${r.passed?'PASS':'FAIL'} ${r.name}`)
if(results.some(r=>!r.passed))process.exitCode=1
