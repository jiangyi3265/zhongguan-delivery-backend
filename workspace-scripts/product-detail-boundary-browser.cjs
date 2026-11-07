// Real Chromium interactions with controlled successful GET fixtures. This verifies UI lifecycle/layout, not live auth or pricing.
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {chromium,expect}=require('./playwright-runtime.cjs').test;
const out=path.resolve(process.env.QICAI_EVIDENCE_DIR||'tmp/product-detail-boundary'),origin=process.env.QICAI_H5_URL||'http://127.0.0.1:25187';fs.mkdirSync(out,{recursive:true});
const report={startedAt:new Date().toISOString(),scope:'Real Chromium with synthetic successful read fixtures; no business writes or proof of backend auth',checks:[],errors:[]};let browser;
(async()=>{try{
 browser=await chromium.launch({headless:true});const context=await browser.newContext({viewport:{width:320,height:844}});const page=await context.newPage();
 page.on('pageerror',e=>report.errors.push(e.message));page.on('console',m=>{if(m.type()==='error')report.errors.push(m.text())});page.on('requestfailed',r=>report.errors.push(r.failure()?.errorText));
 await page.addInitScript(()=>{localStorage.setItem('qc_customer_token',JSON.stringify({type:'string',data:'synthetic-layout-only'}));localStorage.setItem('qc_audience',JSON.stringify({type:'string',data:'business'}));localStorage.setItem('qc_profile',JSON.stringify({type:'object',data:{id:1,audience:'business',status:'ACTIVE'}}))});
 let hold=false,held=[],stock=10;const product=()=>({id:1,sku:'SKU-'+('A'.repeat(60)),name:'合成商品布局检查',subtitle:'Synthetic read-only fixture',category:'食品采购',unit:'袋',stock,price:29.9,audience:'business'});
 const fulfill=(route,data)=>route.fulfill({status:200,contentType:'application/json',body:JSON.stringify({code:200,data})});
 await page.route('**/qicai/mobile/products/1',route=>{if(hold){held.push(route);return;}return fulfill(route,product())});
 await page.route('**/qicai/mobile/profile',route=>fulfill(route,{id:1,audience:'business',status:'ACTIVE',companyName:'Synthetic B',contactName:'Fixture',phone:'13800000001',address:'Synthetic address',version:1}));
 await page.route('**/qicai/mobile/notices/summary',route=>fulfill(route,{unread:0,latestId:0}));
 await page.route('**/qicai/mobile/coupons',route=>fulfill(route,[]));
 await page.goto(origin+'/#/pages/buyer/product?id=1',{waitUntil:'networkidle'});await expect(page.locator('.product-name')).toHaveText('合成商品布局检查');
 assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'64 character SKU overflows 320px viewport');report.checks.push('Maximum-length SKU stays within a 320px viewport');
 await page.locator('.product-actions uni-button').click();await expect(page.locator('.checkout-page .checkout-body')).toBeVisible();await page.waitForLoadState('networkidle');hold=true;
 await page.locator('.checkout-page .header__back').click();await expect.poll(()=>held.length).toBeGreaterThanOrEqual(1);await expect(page.locator('.product-actions uni-button')).toHaveAttribute('disabled','true');await expect(page.locator('.product-content .price')).toHaveCount(0);await expect(page.getByText('正在更新商品信息…',{exact:true})).toBeVisible();report.checks.push('Return refresh hides stale price and prevents purchase while its GET is held');
 hold=false;for(const route of held)await fulfill(route,{...product(),price:39.9});await expect(page.locator('.product-content .price')).toContainText('39.90');await expect(page.locator('.product-actions uni-button')).not.toHaveAttribute('disabled','true');report.checks.push('Fresh response restores purchase with the current visible price');
 stock=0;await page.reload({waitUntil:'networkidle'});await expect(page.locator('.info-row').filter({hasText:'库存'})).toContainText('暂时缺货');await expect(page.locator('.product-actions uni-button')).toHaveAttribute('disabled','true');assert(!(await page.locator('.info-row').filter({hasText:'库存'}).innerText()).includes('充足'));report.checks.push('Zero stock is consistently unavailable');
 await page.screenshot({path:path.join(out,'product-boundary-320.png'),fullPage:true});
 await page.evaluate(()=>localStorage.setItem('qc_audience',JSON.stringify({type:'string',data:'legacy-invalid'})));await page.goto(origin+'/#/pages/auth/login?audience=__proto__',{waitUntil:'networkidle'});await expect(page.locator('.login__copy')).toContainText('品质生活馆');report.checks.push('Invalid cached and query audiences recover to a working consumer login');
 assert.deepEqual(report.errors,[]);report.passed=true;
}catch(error){report.passed=false;report.error=error.stack;process.exitCode=1}finally{await browser?.close();report.endedAt=new Date().toISOString();fs.writeFileSync(path.join(out,'product-detail-boundary-browser-results.json'),JSON.stringify(report,null,2));console.log(JSON.stringify({passed:report.passed,checks:report.checks.length,error:report.error}));}})();
