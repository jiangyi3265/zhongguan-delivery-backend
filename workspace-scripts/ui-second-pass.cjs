const qa = require('./acceptance-context.cjs')()
// Second-pass interactions against the isolated QA database only.
const {chromium,expect}=require('./playwright-runtime.cjs').test
const fs=require('node:fs/promises'),path=require('node:path'),assert=require('node:assert/strict')
const OUT=qa.out,BASE=qa.base,MINI=process.env.QICAI_H5_URL,ADMIN=process.env.QICAI_ADMIN_URL
const results=[],errors=[],endpoints=[],stamp=String(Date.now()).slice(-8),createdOrders=[],pages=[]
let browser,adminToken,driver,product
async function api(url,method='GET',body,token,admin=false){const r=await fetch(BASE+url,{method,headers:{'Content-Type':'application/json',...(token?{[admin?'Authorization':'X-Qicai-Token']:admin?'Bearer '+token:token}:{})},body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(15000)});const data=await r.json();assert(r.ok,JSON.stringify(data));return data}
const a=(url,method,body)=>api('/qicai/admin'+url,method,body,adminToken,true)
const mf=(p,t)=>p.locator('uni-input,uni-textarea').filter({hasText:t}).locator('input,textarea').first()
const tap=(p,t)=>p.getByText(t,{exact:true}).click()
const pass=name=>{results.push({name,passed:true});console.log('PASS '+name)}
async function login(p,who,phone){await p.goto(MINI+'/#/pages/auth/login?audience='+who,{waitUntil:'networkidle'});await mf(p,'请输入手机号').fill(phone);await mf(p,'请输入密码').fill('QcTest123!');await p.locator('uni-button').filter({hasText:/^登录$/}).click();await p.waitForURL(/pages\/(buyer|courier)\/home/);await p.waitForLoadState('networkidle')}
;(async()=>{
 const fixture=JSON.parse(await fs.readFile(path.join(OUT,'ui-fixture.json')))
 adminToken=(await api('/login','POST',{username:'admin',password:qa.password,code:'',uuid:''})).token
 const ct=(await api('/qicai/mobile/auth/login','POST',{audience:'consumer',phone:fixture.c.phone,password:'QcTest123!'})).data.token
 const previousTotal=(await api('/qicai/mobile/orders', 'GET', undefined, ct)).data.total
 product=(await a('/products','POST',{sku:'PAGE-'+stamp,name:'分页验收商品'+stamp,category:'食品采购',unit:'件',stock:100,businessPrice:1,consumerPrice:2,status:'ACTIVE'})).data
 for(let i=0;i<21;i++)createdOrders.push((await api('/qicai/mobile/orders','POST',{productId:product.id,quantity:1,idempotencyKey:'page-'+stamp+'-'+i},ct)).data)
 driver=(await a('/couriers','POST',{phone:'175'+stamp,name:'开关验收'+stamp,password:'QcTest123!',vehicleNo:'沪QA003',available:true,status:'ACTIVE'})).data
 browser=await chromium.launch({headless:true,args:['--no-proxy-server']})
 const c=await browser.newPage({viewport:{width:390,height:844}}),d=await browser.newPage({viewport:{width:390,height:844}}),b=await browser.newPage({viewport:{width:390,height:844}}),admin=await browser.newPage({viewport:{width:1440,height:960}})
 pages.push(['second-consumer',c],['second-courier',d],['second-business',b],['second-admin',admin])
 for(const p of [c,d,b,admin])p.on('response',r=>{const pathname=new URL(r.url()).pathname.replace(/^\/prod-api/,'');if(/^\/qicai\/(mobile|admin)\//.test(pathname))endpoints.push({method:r.request().method(),path:pathname,status:r.status()})})
 b.on('response',async r=>{if(r.request().method()==='PUT'&&r.url().endsWith('/profile'))console.log('PROFILE UPDATE '+r.status()+' '+await r.text())})
 for(const p of [c,d,b,admin]){p.setDefaultTimeout(12000);p.on('pageerror',e=>errors.push(e.message));p.on('console',m=>{if(m.type()==='error'&&!m.text().startsWith('Failed to load resource:'))errors.push(m.text())})}
 await login(c,'consumer',fixture.c.phone)
 await c.goto(MINI+'/#/pages/buyer/orders',{waitUntil:'networkidle'})
 await expect(c.locator('.order')).toHaveCount(20)
 await tap(c,'加载更多订单')
 await expect(c.locator('.order')).toHaveCount(Math.min(40,previousTotal+21))
 const rendered=await c.locator('.order__top').allTextContents()
 assert.equal(new Set(rendered).size,rendered.length)
 pass('超过20订单加载下一页无重复遗漏')
 await c.locator('.tabs__item').filter({hasText:'待发货'}).click()
 await expect(c.locator('.order')).toHaveCount(20)
 await tap(c,'加载更多订单')
 await expect(c.locator('.order')).toHaveCount(21)
 await c.locator('.tabs__item').filter({hasText:'已签收'}).click()
 await expect(c.locator('.order')).toHaveCount(1)
 pass('订单状态筛选独立分页并重置页码')
 await tap(c,'再次购买');await c.waitForURL(/buyer\/product/)
 await c.screenshot({path:path.join(OUT,'product-production.png'),fullPage:true})
 pass('已签收订单再次购买到真实商品详情')
 await admin.goto(ADMIN+'/login',{waitUntil:'networkidle'});await admin.getByPlaceholder('账号',{exact:true}).fill('admin');await admin.getByPlaceholder('密码',{exact:true}).fill(qa.password);await admin.getByRole('button',{name:'登 录',exact:true}).click();await admin.waitForURL(u=>!u.pathname.includes('login'))
 await admin.goto(ADMIN+'/qicai/orders',{waitUntil:'networkidle'});await admin.getByPlaceholder('订单号、联系人或电话').fill(fixture.c.phone);await admin.getByRole('button',{name:'查询',exact:true}).click();await expect(admin.locator('.el-table__body tr')).toHaveCount(20)
 const first=await admin.locator('.el-table__body tr').first().innerText()
 const next=admin.waitForResponse(r=>r.url().includes('/qicai/admin/orders?')&&r.url().includes('pageNum=2'))
 await admin.locator('.btn-next').click();await next;await expect(admin.locator('.el-table__body tr').first()).not.toHaveText(first)
 await admin.getByRole('button',{name:'重置',exact:true}).click();await expect(admin.getByPlaceholder('订单号、联系人或电话')).toHaveValue('')
 pass('后台真实分页换页及搜索重置')
 await login(d,'courier',driver.phone)
 let changed=d.waitForResponse(r=>r.url().endsWith('/courier/availability')&&r.request().method()==='PUT')
 await d.locator('uni-switch').click();assert.equal((await changed).status(),200)
 await d.reload({waitUntil:'networkidle'});await d.getByText('暂停接单',{exact:true}).waitFor()
 await admin.goto(ADMIN+'/qicai/couriers',{waitUntil:'networkidle'});await admin.locator('.el-table__body tr').filter({hasText:driver.phone}).getByText('暂停接单',{exact:true}).waitFor()
 changed=d.waitForResponse(r=>r.url().endsWith('/courier/availability')&&r.request().method()==='PUT')
 await d.locator('uni-switch').click();assert.equal((await changed).status(),200)
 await admin.reload({waitUntil:'networkidle'});await admin.locator('.el-table__body tr').filter({hasText:driver.phone}).getByText('可接单',{exact:true}).waitFor()
 pass('配送员接单开关刷新保持并同步后台')
 await d.locator('.tabbar__item').filter({hasText:'配送中'}).click();await d.waitForURL(/status=IN_DELIVERY/)
 await d.locator('.tabbar__item').filter({hasText:'任务'}).click();await d.waitForURL(u=>!u.hash.includes('status='))
 await d.getByText('暂无配送任务',{exact:true}).waitFor()
 pass('配送中筛选可返回全部任务且空状态正常')
 await d.locator('.tabbar__item').filter({hasText:'退出'}).click();await d.waitForURL(u=>u.hash==='#/'||u.hash==='#/pages/index/index')
 pass('配送员主动退出登录')
 await login(b,'business',fixture.b.phone);await b.goto(MINI+'/#/pages/buyer/profile',{waitUntil:'networkidle'});await tap(b,'编辑资料');await mf(b,'请输入企业名称').fill('复审企业'+stamp);await b.locator('uni-button').filter({hasText:/^保存注册资料$/}).click();await b.getByText('审核中',{exact:true}).waitFor();await b.reload({waitUntil:'networkidle'});await b.getByText('复审企业'+stamp,{exact:true}).first().waitFor()
 await admin.goto(ADMIN+'/qicai/customers',{waitUntil:'networkidle'});await admin.getByPlaceholder('手机号、联系人、企业名称或税号').fill(fixture.b.phone);await admin.getByRole('button',{name:'查询',exact:true}).click();const row=admin.locator('.el-table__body tr').filter({hasText:fixture.b.phone});await row.getByText('复审企业'+stamp,{exact:true}).waitFor();await row.getByRole('button',{name:'通过/启用'}).click();await admin.locator('.el-message-box').getByRole('button',{name:'确定'}).click();await row.getByText('已启用',{exact:true}).waitFor();await b.reload({waitUntil:'networkidle'});await b.getByText('已启用',{exact:true}).waitFor()
 pass('企业修改名称重新审核与三端刷新同步')
 await b.screenshot({path:path.join(OUT,'business-profile-final.png'),fullPage:true});await admin.screenshot({path:path.join(OUT,'admin-customers-final.png'),fullPage:true})
})().catch(e=>{results.push({name:'二轮流程中断',passed:false,error:e.message});console.error(e)}).finally(async()=>{
 for(const [name,p] of pages){await p.screenshot({path:path.join(OUT,name+'.png'),fullPage:true});await fs.writeFile(path.join(OUT,name+'.txt'),await p.locator('body').innerText())}
 for(const o of createdOrders)await a('/orders/'+o.id+'/cancel','POST').catch(e=>errors.push('fixture cleanup '+e.message))
 if(driver)await a('/couriers/'+driver.id,'DELETE').catch(e=>errors.push(e.message))
 if(product)await a('/products/'+product.id,'DELETE').catch(e=>errors.push(e.message))
 await fs.writeFile(path.join(OUT,'ui-second-pass.json'),JSON.stringify({at:new Date().toISOString(),results,errors,endpoints},null,2));if(browser)await browser.close()
 console.log('SECOND PASS '+results.filter(r=>r.passed).length+'/'+results.length+' errors '+errors.length);if(results.some(r=>!r.passed)||errors.length)process.exitCode=1
})
