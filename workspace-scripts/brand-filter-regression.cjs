// Real SQL + browser regression for brand pagination with nested audience-unit queries.
const qa=require('./acceptance-context.cjs')(),{chromium,expect}=require('./playwright-runtime.cjs').test;
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const report={checks:[],errors:[],scope:'Harness-owned MySQL and HTTP; synthetic brands never touch production'};
const consent={'X-Qicai-Privacy-Accepted':'1','X-Qicai-Privacy-Version':'2026-10-27.1'};
let browser,adminToken;
async function request(route,{method='GET',body,token,admin=false,status=200}={}) {
 const r=await fetch(qa.base+route,{method,headers:{'Content-Type':'application/json',...consent,...(token?{'X-Qicai-Token':token}:{}),...(admin?{Authorization:'Bearer '+adminToken}:{})},body:body&&JSON.stringify(body)});
 const j=await r.json();assert.equal(r.status,status,route+' '+JSON.stringify(j));if(status===200)assert.equal(j.code,200);return j.data??j;
}
const a=(route,method,body)=>request('/qicai/admin'+route,{method,body,admin:true});
const visit=async(p,route)=>{await p.goto('about:blank');await p.goto(process.env.QICAI_H5_URL+'/#'+route,{waitUntil:'networkidle'});};
(async()=>{try{
 adminToken=(await request('/login',{method:'POST',body:{username:'admin',password:qa.password}})).token;assert(adminToken);
 const brands=[];
 for(const name of ['BFREG甲品牌','BFREG乙品牌','BFREG空品牌','BFREG停用品牌'])brands.push(await a('/brands','POST',{name,description:'长文字不应展示',logoUrl:'',slides:[{imageUrl:'/static/brands/pps-intro.jpg',caption:''}],sortOrder:100,status:'ACTIVE'}));
 const products=[];
 for(let i=0;i<6;i++)products.push(await a('/products','POST',{sku:'BFREG-'+i,name:'BFREG商品'+i,category:i%2?'奇数分类':'偶数分类',subtitle:'独立规格',unit:'件',...(i!==0?{businessUnit:'箱',consumerUnit:'袋'}:{}),stock:30,businessPrice:11.23,consumerPrice:19.87,status:i===5?'INACTIVE':'ACTIVE',brandId:i===4?brands[1].id:brands[0].id}));
 await a('/products','POST',{sku:'BFREG-HIDDEN-BRAND',name:'停用品牌商品',category:'停用分类',unit:'件',stock:10,businessPrice:10,consumerPrice:20,status:'ACTIVE',brandId:brands[3].id});
 await a('/brands/'+brands[3].id,'PUT',{...brands[3],status:'INACTIVE'});
 const consumer=await request('/qicai/mobile/auth/login',{method:'POST',body:{audience:'consumer',phone:'13900002701',password:qa.password}});
 const business=await request('/qicai/mobile/auth/login',{method:'POST',body:{audience:'business',phone:'13900002702',password:qa.password}});
 const available=products.filter(p=>p.status==='ACTIVE');let count=0;
 for(const who of [{name:'public',route:'/qicai/mobile/catalog/products'},{name:'consumer',route:'/qicai/mobile/products',token:consumer.token},{name:'business',route:'/qicai/mobile/products',token:business.token}]){
  for(const brandId of [null,...brands.map(b=>b.id),999999999])for(const category of ['', '偶数分类','不存在分类'])for(const keyword of ['BFREG商品','BFREG商品1',"' OR 1=1 --"]){
   const expected=available.filter(p=>(!brandId||p.brandId===brandId)&&(!category||p.category===category)&&(p.name.includes(keyword)||p.sku.includes(keyword)));
   for(const pageNum of [1,2]){
    const q=new URLSearchParams({pageNum:String(pageNum),pageSize:'2',keyword,category,...(brandId?{brandId:String(brandId)}:{})});
    const result=await request(who.route+'?'+q,{token:who.token});assert.equal(result.total,expected.length,who.name+' '+q);
    assert.deepEqual(result.rows.map(p=>p.id),expected.slice((pageNum-1)*2,pageNum*2).map(p=>p.id));
    for(const p of result.rows){const legacy=p.id===products[0].id;assert.equal(p.unit,legacy?'件':who.name==='business'?'箱':'袋');assert.equal(Number(p.price),who.name==='business'?11.23:19.87);assert(!('businessPrice'in p));}
    count++;
   }
  }
  const legacy=await request(who.route+'?brandId='+brands[0].id,{token:who.token});assert.deepEqual(legacy.map(p=>p.id),products.slice(0,4).map(p=>p.id));
  await request(who.route+'?brandId=0&pageNum=1',{token:who.token,status:400});
 }
 report.checks.push({name:'Brand, category, keyword, missing/disabled brand and pagination combinations return correct rows/counts for public, C and B audiences',requests:count});
 assert.deepEqual(await request('/qicai/mobile/catalog/categories?brandId='+brands[0].id),['偶数分类','奇数分类']);
 browser=await chromium.launch({headless:true});const p=await browser.newPage({viewport:{width:390,height:844},isMobile:true,hasTouch:true});p.on('pageerror',e=>report.errors.push(e.message));
 await visit(p,'/pages/index/index');
 for(const [brand,n] of [[brands[0],4],[brands[1],1],[brands[2],0],[brands[0],4]]){
  await p.locator('.brand-option').filter({hasText:brand.name}).click();await expect(p.locator('.sku-heading__count')).toHaveText(n+' 款');await expect(p.locator('.catalog-product')).toHaveCount(n);await expect(p.locator('[role="alert"]')).toHaveCount(0);
  if(n===0)await expect(p.locator('.catalog-message')).toContainText('商品尚未上架');
 }
 await p.locator('.catalog-categories uni-button').filter({hasText:'偶数分类'}).click();await expect(p.locator('.catalog-product')).toHaveCount(2);
 await p.locator('.catalog-search input').fill('BFREG-2');await p.locator('.catalog-search uni-button').click();await expect(p.locator('.catalog-product__sku')).toHaveText('SKU：BFREG-2');
 await p.locator('.catalog-product').click();await expect(p.locator('.catalog-sku')).toHaveText('BFREG-2');
 await visit(p,'/pages/index/index');await p.locator('.brand-option').filter({hasText:brands[0].name}).click();await expect(p.locator('.catalog-product')).toHaveCount(4);
 const first=await p.locator('.catalog-product').first().boundingBox(),bar=await p.locator('.tabbar').boundingBox();assert(first.y+first.height<bar.y);await expect(p.locator('.brand-description')).toHaveCount(0);await expect(p.locator('.brand-carousel')).toBeVisible();await p.screenshot({path:path.join(qa.out,'brand-selected-products.png')});
 // Hold a genuine old response and verify rapid switching cannot replace the chosen brand.
 let release,ready,once=false;const held=new Promise(r=>release=r),waiting=new Promise(r=>ready=r);
 await p.route('**/qicai/mobile/catalog/products?**',async route=>{if(!once&&new URL(route.request().url()).searchParams.get('brandId')===String(brands[1].id)){once=true;const real=await route.fetch();ready();await held;return route.fulfill({response:real});}return route.continue();});
 await p.locator('.brand-option').filter({hasText:brands[1].name}).click();await waiting;await p.locator('.brand-option').filter({hasText:brands[0].name}).click();await expect(p.locator('.catalog-product')).toHaveCount(4);release();await p.waitForLoadState('networkidle');await expect(p.locator('.catalog-product')).toHaveCount(4);await expect(p.locator('.brand-option--active')).toContainText(brands[0].name);await p.unroute('**/qicai/mobile/catalog/products?**');
 report.checks.push({name:'Real mobile clicks: brand switching, empty brand, category, search, product detail and delayed-response race; product above bottom navigation'});
 await visit(p,'/pages/auth/login?audience=business');await p.locator('.privacy-check').click();await p.getByText('同意并继续',{exact:true}).click();await p.locator('input').nth(0).fill('13900002702');await p.locator('input').nth(1).fill(qa.password);await p.locator('.login__button').click();await p.waitForURL(/buyer\/home/);
 for(const [brand,n] of [[brands[0],4],[brands[1],1]]){await p.locator('.brand-option').filter({hasText:brand.name}).click();await expect(p.locator('.catalog-product')).toHaveCount(n);await expect(p.locator('.product__price').first()).toContainText('¥11.23');await expect(p.locator('[role="alert"]')).toHaveCount(0);}
 report.checks.push({name:'Business home brand switches display B pricing and units'});assert.deepEqual(report.errors,[]);report.exitCode=0;
 }catch(e){report.error=e.stack;report.exitCode=1;}finally{await browser?.close();fs.writeFileSync(path.join(qa.out,'brand-filter-results.json'),JSON.stringify(report,null,2));console.log(JSON.stringify(report));process.exitCode=report.exitCode;}})();
