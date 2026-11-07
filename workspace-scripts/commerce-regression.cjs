// Real isolated Chromium -> HTTP -> MySQL. No official WeChat/payment calls.
const qa=require('./acceptance-context.cjs')(),{chromium}=require('./playwright-runtime.cjs');
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict'),cp=require('node:child_process');
const report={startedAt:new Date().toISOString(),scope:'isolated H5/admin HTTP/MySQL; payment disabled; synthetic customers',checks:[],pageErrors:[],consoleErrors:[],network:[],requestFailures:[]};
const stamp=Date.now().toString().slice(-8),password='CommerceVerify2026!',phone='186'+stamp;
let browser,admin,customer,product,page;
function sql(q){return cp.execFileSync('mysql',['--no-defaults','--protocol=TCP','--host=127.0.0.1','--port=13319','--user=root','--default-character-set=utf8mb4','--batch','--raw','--skip-column-names','qicai_acceptance'],{input:q,encoding:'utf8',windowsHide:true}).trim()}
async function api(route,method='GET',body,token,isAdmin=false){const r=await fetch(qa.base+route,{method,headers:{'Content-Type':'application/json',...(token?{[isAdmin?'Authorization':'X-Qicai-Token']:isAdmin?'Bearer '+token:token}:{})},body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(15000)});return {status:r.status,body:await r.json()}}
function field(text){return page.locator('uni-input,uni-textarea').filter({hasText:text}).locator('input,textarea').first()}
const submit=()=>page.locator('uni-button').filter({hasText:/^提交订单$/});
async function update(values){const r=await api('/qicai/admin/products/'+product.id,'PUT',{...product,...values},admin,true);assert.equal(r.status,200);assert.equal(r.body.code,200);product=r.body.data;return product}
async function freshProduct(){product=(await api('/qicai/admin/products?keyword='+encodeURIComponent(product.sku),'GET',undefined,admin,true)).body.data.rows[0];return product}
function monitor(){page.setDefaultTimeout(15000);page.on('pageerror',e=>report.pageErrors.push(e.message));page.on('console',m=>{if(m.type()==='error'&&!m.text().startsWith('Failed to load resource:'))report.consoleErrors.push(m.text())});page.on('requestfailed',r=>report.requestFailures.push({path:new URL(r.url()).pathname,method:r.method(),error:r.failure()?.errorText}));page.on('response',r=>{if(r.url().includes('/qicai/'))report.network.push({path:new URL(r.url()).pathname,status:r.status(),method:r.request().method()})})}
async function checkout(){const context=page.context();await page.close();page=await context.newPage();monitor();await page.goto(process.env.QICAI_H5_URL+'/#/pages/buyer/checkout?id='+product.id,{waitUntil:'networkidle'});await page.locator('.goods__name').waitFor();await submit().waitFor()}
async function scenario(name,fn){const row={name,status:'失败'};report.checks.push(row);try{row.evidence=await fn();row.status='已实测通过'}catch(e){row.error=e.message;await page.screenshot({path:path.join(qa.out,name+'.png'),fullPage:true}).catch(()=>{})}console.log(row.status+' '+name)}
(async()=>{try{
 assert.equal(sql("SELECT CONCAT(@@port,'/',DATABASE())"),'13319/qicai_acceptance');
 admin=(await api('/login','POST',{username:'admin',password:qa.password})).body.token;assert(admin);
 const sms=await api('/qicai/mobile/auth/sms','POST',{phone,audience:'consumer'});assert.equal(sms.status,201);
 const reg=await api('/qicai/mobile/auth/register','POST',{phone,audience:'consumer',password,contactName:'原收货人',address:'原收货地址1号',smsCode:sms.body.data.devCode});assert.equal(reg.status,201);customer=reg.body.data;
 const added=await api('/qicai/admin/products','POST',{sku:'CM-'+stamp,name:'跨端验收'+stamp,category:'验收',unit:'件',stock:30,businessPrice:9.87,consumerPrice:12.34,status:'ACTIVE'},admin,true);assert.equal(added.status,200);product=added.body.data;
 browser=await chromium.launch({headless:true});const context=await browser.newContext({viewport:{width:390,height:844}});page=await context.newPage();monitor();
 await page.goto(process.env.QICAI_H5_URL+'/#/pages/auth/login?audience=consumer',{waitUntil:'networkidle'});
 await field('请输入手机号').fill(phone);await field('请输入密码').fill(password);await page.locator('.login__button').click();await page.waitForURL(/pages\/buyer\/home/);await page.waitForLoadState('networkidle');
 await scenario('CM03-stale-price-rejected',async()=>{
   await checkout();const before=await page.locator('.checkout-action__price').innerText();assert(before.includes('12.34'));await update({consumerPrice:33.34});
   const next=page.waitForResponse(r=>r.request().method()==='POST'&&new URL(r.url()).pathname==='/qicai/mobile/orders');await submit().click();const r=await next,b=await r.json();
   report.priceObserved={displayedBefore:before,http:r.status(),returnedAmount:b.data?.payableAmount,createdOrderId:b.data?.id};
   assert.equal(r.status(),409,'Price changed while checkout open; stale quote must not create an order');
   assert.equal(sql('SELECT COUNT(*) FROM qc_order WHERE customer_id='+customer.id),'0');
   await page.locator('.checkout-action__price').filter({hasText:'33.34'}).waitFor();
   return {rejectedHttp:r.status(),updatedQuote:await page.locator('.checkout-action__price').innerText()};
 });
 await scenario('CM04-immediate-receipt-input',async()=>{
   await freshProduct();await checkout();const address='刚刚输入的新地址'+stamp;
   const next=page.waitForResponse(r=>r.request().method()==='POST'&&new URL(r.url()).pathname==='/qicai/mobile/orders');
   await field('请输入详细收货地址（含门牌号）').fill(address);await submit().click();const r=await next,b=await r.json();assert.equal(r.status(),201);
   const sent=r.request().postDataJSON(),saved=sql('SELECT delivery_address FROM qc_order WHERE id='+b.data.id);report.receiptObserved={expected:address,requested:sent.deliveryAddress,persisted:saved};
   assert.equal(sent.deliveryAddress,address,'Immediate submit must use the visible current address');assert.equal(saved,address);
   assert.equal(sent.expectedUnitPrice,'33.34');assert.equal(sent.expectedPayableAmount,'33.34');assert.equal(Number(b.data.payableAmount),33.34);
   assert.equal(Number(sql('SELECT stock FROM qc_product WHERE id='+product.id)),product.stock-1);
   await page.waitForURL(/pages\/buyer\/orders/);return {orderId:b.data.id,requestAndDatabaseMatch:true};
 });
 await scenario('CM05-unknown-response-reentry',async()=>{
   await freshProduct();await checkout();await page.locator('.quantity').getByText('＋',{exact:true}).click();assert.equal(await page.locator('.quantity uni-text').nth(1).innerText(),'2');
   let first;await page.route('**/qicai/mobile/orders',async route=>{if(route.request().method()!=='POST')return route.continue();const r=await route.fetch();assert.equal(r.status(),201);first=await r.json();await route.abort('failed')},{times:1});
   const countBefore=Number(sql('SELECT COUNT(*) FROM qc_order WHERE customer_id='+customer.id));const stockBefore=Number(sql('SELECT stock FROM qc_product WHERE id='+product.id));const failed=page.waitForEvent('requestfailed',{predicate:r=>r.method()==='POST'&&new URL(r.url()).pathname==='/qicai/mobile/orders'});await submit().click();await failed;await page.getByText('无法连接服务，请检查网络',{exact:true}).waitFor();assert(first?.data?.id);
   const original=first.data;await page.reload({waitUntil:'networkidle'});
   if(await page.locator('.goods__name').count()){const repeated=page.waitForResponse(r=>r.request().method()==='POST'&&new URL(r.url()).pathname==='/qicai/mobile/orders');await submit().click();await repeated;await page.waitForURL(/pages\/buyer\/orders/)}
   const countAfter=Number(sql('SELECT COUNT(*) FROM qc_order WHERE customer_id='+customer.id));
   report.unknownObserved={firstOrderId:original.id,countBefore,countAfter,firstQuantity:original.items?.[0]?.quantity,currentUrl:page.url()};
   assert.equal(countAfter,countBefore+1,'Reload after lost successful response must not create a second order');
   assert.equal(Number(sql('SELECT stock FROM qc_product WHERE id='+product.id)),stockBefore-2);assert.equal(Number(sql('SELECT quantity FROM qc_order_item WHERE order_id='+original.id)),2);
   await page.waitForURL(/pages\/buyer\/orders/);return {originalOrderId:original.id,countBefore,countAfter};
 });
 await scenario('CM05-rejected-unknown-can-reconfirm',async()=>{
   await freshProduct();await checkout();let originalBody;
   await page.route('**/qicai/mobile/orders',async route=>{if(route.request().method()!=='POST')return route.continue();originalBody=route.request().postDataJSON();await route.abort('failed')},{times:1});
   const before=Number(sql('SELECT COUNT(*) FROM qc_order WHERE customer_id='+customer.id)),stock=product.stock;
   const failure=page.waitForEvent('requestfailed',{predicate:r=>r.method()==='POST'&&new URL(r.url()).pathname==='/qicai/mobile/orders'});await submit().click();await failure;await page.getByText('上次订单提交结果待确认',{exact:true}).waitFor();
   assert.equal(Number(sql('SELECT COUNT(*) FROM qc_order WHERE customer_id='+customer.id)),before);
   await update({consumerPrice:44.34});await page.reload({waitUntil:'networkidle'});
   const rejection=page.waitForResponse(r=>r.request().method()==='POST'&&new URL(r.url()).pathname==='/qicai/mobile/orders');await page.getByText('重试原订单',{exact:true}).click();assert.equal((await rejection).status(),409);
   await page.locator('.checkout-action__price').filter({hasText:'44.34'}).waitFor();await page.reload({waitUntil:'networkidle'});await page.locator('.checkout-action__price').filter({hasText:'44.34'}).waitFor();
   const result=page.waitForResponse(r=>r.request().method()==='POST'&&new URL(r.url()).pathname==='/qicai/mobile/orders');await submit().click();const response=await result,body=await response.json();assert.equal(response.status(),201);
   assert.equal(response.request().postDataJSON().idempotencyKey,originalBody.idempotencyKey);assert.equal(Number(body.data.payableAmount),44.34);
   assert.equal(Number(sql('SELECT COUNT(*) FROM qc_order WHERE customer_id='+customer.id)),before+1);assert.equal(Number(sql('SELECT stock FROM qc_product WHERE id='+product.id)),stock-1);await page.waitForURL(/pages\/buyer\/orders/);
   return {orderId:body.data.id,originalKeyRetained:true,confirmedPrice:44.34,stockDecrement:1};
 });
 await scenario('CM05-legacy-cache-requires-quote',async()=>{
   await freshProduct();await checkout();const legacyKey='legacy-'+stamp,payload={productId:product.id,quantity:2,contactName:'旧缓存收货人',contactPhone:phone,deliveryAddress:'旧缓存验收地址'};
   const countBefore=Number(sql('SELECT COUNT(*) FROM qc_order WHERE customer_id='+customer.id)),stock=product.stock;
   await page.evaluate(({key,value})=>localStorage.setItem(key,JSON.stringify({type:'object',data:value})),{key:'qc_pending_order_'+customer.id+'_'+product.id,value:{idempotencyKey:legacyKey,fingerprint:JSON.stringify(payload)}});
   await update({consumerPrice:55.34});const postsBefore=report.network.filter(r=>r.path==='/qicai/mobile/orders'&&r.method==='POST').length;
   await page.reload({waitUntil:'networkidle'});await page.getByText('重试原订单',{exact:true}).click();await page.locator('.checkout-action__price').filter({hasText:'110.68'}).waitFor();
   assert.equal(report.network.filter(r=>r.path==='/qicai/mobile/orders'&&r.method==='POST').length,postsBefore,'Legacy cache cannot submit without displaying the new quote');
   assert.equal(Number(sql('SELECT COUNT(*) FROM qc_order WHERE customer_id='+customer.id)),countBefore);
   const result=page.waitForResponse(r=>r.request().method()==='POST'&&new URL(r.url()).pathname==='/qicai/mobile/orders');await submit().click();const response=await result,body=await response.json(),sent=response.request().postDataJSON();assert.equal(response.status(),201);
   assert.equal(sent.idempotencyKey,legacyKey);assert.equal(sent.expectedUnitPrice,'55.34');assert.equal(sent.expectedPayableAmount,'110.68');assert.equal(Number(body.data.payableAmount),110.68);
   assert.equal(Number(sql('SELECT COUNT(*) FROM qc_order WHERE customer_id='+customer.id)),countBefore+1);assert.equal(Number(sql('SELECT stock FROM qc_product WHERE id='+product.id)),stock-2);await page.waitForURL(/pages\/buyer\/orders/);
   return {syntheticLegacyStorageFixture:true,confirmedPrice:55.34,confirmedAmount:110.68,sameKey:true,exactlyOneOrder:true};
 });
 assert.equal(report.pageErrors.length,0,'Unexpected browser runtime error');
 assert.equal(report.consoleErrors.length,0,'Unexpected console error');
 assert.deepEqual(report.requestFailures,Array.from({length:2},()=>({path:'/qicai/mobile/orders',method:'POST',error:'net::ERR_FAILED'})),'Only the two injected network failures are expected');
 assert.deepEqual(report.network.filter(r=>r.status>=400),Array.from({length:2},()=>({path:'/qicai/mobile/orders',status:409,method:'POST'})),'Only the two stale-price rejections are expected');
 report.exitCode=report.checks.every(c=>c.status==='已实测通过')?0:1;
}catch(e){report.fatal=e.stack;report.exitCode=1}finally{if(browser)await browser.close();report.endedAt=new Date().toISOString();fs.writeFileSync(path.join(qa.out,'commerce-results.json'),JSON.stringify(report,null,2));console.log(JSON.stringify({exitCode:report.exitCode,checks:report.checks,fatal:report.fatal},null,2));process.exitCode=report.exitCode}})();
