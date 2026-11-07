// Real H5/HTTP/persisted order. Only the browser clock is held to expose the
// Uni input 100ms throttle; this is not a native-device or payment test.
const qa=require('./acceptance-context.cjs')(),{chromium,test:{expect}}=require('./playwright-runtime.cjs');
const fs=require('fs'),path=require('path'),assert=require('assert/strict');
const report={startedAt:new Date().toISOString(),kind:'Real isolated Chromium/H5/API orders; browser clock holds existing 100ms input throttle, no response mocks or real payments',checks:[],snapshots:[],requests:[],errors:[]};
let browser,admin,product,customer;const password='CheckoutInput2026!',stamp=Date.now().toString().slice(-8),phone='184'+stamp;
async function api(url,method='GET',body,token,role='admin'){const r=await fetch(qa.base+url,{method,headers:{'content-type':'application/json',...(token?{[role==='admin'?'Authorization':'X-Qicai-Token']:role==='admin'?'Bearer '+token:token}:{})},body:body===undefined?undefined:JSON.stringify(body)});const b=await r.json();assert(r.ok&&b.code===200,JSON.stringify(b));return b.data??b}
const field=(p,name)=>p.locator('uni-input,uni-textarea').filter({hasText:name}).locator('input,textarea').first();
async function snapshot(page,label){return page.evaluate(label=>{let c=document.querySelector('.checkout-page')?.__vueParentComponent;while(c&&!('contactName'in c.proxy))c=c.parent;return{label,time:performance.now(),inputs:[...document.querySelectorAll('input,textarea')].map(x=>x.value),component:c?Object.fromEntries(['contactName','contactPhone','deliveryAddress','remark','nativeLocation','loading','error','submitting'].map(k=>[k,c.proxy[k]])):null} },label)}
;(async()=>{try{
 admin=(await api('/login','POST',{username:'admin',password:qa.password})).token;
 product=await api('/qicai/admin/products','POST',{sku:'INPUT-'+stamp,name:'收货输入回归'+stamp,category:'办公耗材',unit:'件',stock:10,businessPrice:1,consumerPrice:2,status:'ACTIVE'},admin);
 const body={audience:'consumer',phone,password,contactName:'原收货人',address:'原收货地址'},sms=await api('/qicai/mobile/auth/sms','POST',body);await api('/qicai/mobile/auth/register','POST',{...body,smsCode:sms.devCode});customer=(await api('/qicai/mobile/auth/login','POST',body)).token;
 browser=await chromium.launch({headless:true,args:['--no-proxy-server']});
 for(const [key,placeholder,first,last]of [['contactName','请输入收货人姓名','','最新收货人'],['contactPhone','请输入收货联系电话','123','18912345678'],['deliveryAddress','请输入详细收货地址（含门牌号）','','最新收货地址99号'],['remark','选填，如有特殊要求请留言','原备注','最新备注']]){
  const page=await browser.newPage({viewport:{width:390,height:844}});page.setDefaultTimeout(5000);page.on('pageerror',e=>report.errors.push({key,error:e.message}));page.on('request',r=>{if(r.method()==='POST'&&new URL(r.url()).pathname==='/qicai/mobile/orders')report.requests.push({key,body:r.postDataJSON()})});
  try{
   await page.goto((process.env.QICAI_H5_URL||'http://127.0.0.1:25187')+'/#/pages/auth/login?audience=consumer&productId='+product.id,{waitUntil:'networkidle'});await field(page,'请输入手机号').fill(phone);await field(page,'请输入密码').fill(password);await page.locator('.login__button').click();await expect(page.locator('.goods__name')).toHaveText(product.name);
   const start=new Date('2026-10-18T12:00:00Z');await page.clock.install({time:start});await page.clock.pauseAt(new Date(start.getTime()+1000));
   await field(page,placeholder).fill(first);await field(page,placeholder).fill(last);report.snapshots.push({key,...await snapshot(page,'before click while model update pending')});
   const pending=page.waitForResponse(r=>new URL(r.url()).pathname==='/qicai/mobile/orders'&&r.request().method()==='POST',{timeout:1800});const observed=pending.catch(error=>({waitError:error.message}));await page.locator('uni-button').filter({hasText:/^提交订单$/}).click({force:true});const response=await observed;report.snapshots.push({key,...await snapshot(page,'after immediate click')});assert(!response.waitError,response.waitError);
   const created=(await response.json()).data;assert.equal(response.status(),201);assert.equal(created[key],last);assert.equal(created.paymentStatus,'NOT_REQUIRED');assert.equal(created.payableAmount,2);const persisted=await api('/qicai/mobile/orders/'+created.id,'GET',undefined,customer,'customer');assert.equal(persisted[key],last);assert.equal(persisted.status,'PENDING_SHIPMENT');
   await page.clock.resume();await expect(page).toHaveURL(/pages\/buyer\/orders/);await page.reload({waitUntil:'networkidle'});await expect(page.locator('.order').filter({hasText:product.name})).toHaveCount(report.checks.filter(x=>x.passed).length+1);
   report.checks.push({key,name:'immediate '+key+' submission uses latest visible input and persists across reload',passed:true,orderId:created.id});
  }catch(error){report.checks.push({key,name:'immediate '+key+' submission uses latest visible input and persists across reload',passed:false,error:error.stack});await page.screenshot({path:path.join(qa.out,'checkout-input-'+key+'-failure.png'),fullPage:true,timeout:3000}).catch(()=>{})}
  finally{await page.close()}
 }
 report.checks.push({name:'no unhandled browser runtime errors',passed:report.errors.length===0});
}catch(error){report.checks.push({name:'checkout fixture or browser setup',passed:false,error:error.stack})}finally{await browser?.close();report.endedAt=new Date().toISOString();report.exitCode=report.checks.every(x=>x.passed)?0:1;fs.writeFileSync(path.join(qa.out,'checkout-input-browser-results.json'),JSON.stringify(report,null,2));console.log(JSON.stringify({exitCode:report.exitCode,checks:report.checks.map(x=>({name:x.name,passed:x.passed,error:x.error}))}));process.exitCode=report.exitCode}})();
