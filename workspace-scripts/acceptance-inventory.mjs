import {readFile,writeFile} from 'node:fs/promises'
import {pathToFileURL} from 'node:url'
const folder=pathToFileURL(process.env.QICAI_EVIDENCE_DIR + '/')
const reports=await Promise.all(['api-results','ui-results','ui-second-pass'].map(async n=>JSON.parse(await readFile(new URL(n+'.json',folder),'utf8'))))
const records=reports.flatMap(r=>r.endpoints||[]),endpoints=[]
try { records.push(...JSON.parse(await readFile(new URL('payment-api-endpoints.json',folder),'utf8')).endpoints) }
catch (error) { if (error.code !== 'ENOENT') throw error }
for(const file of ['QicaiAdminController','QicaiMobileController','QicaiPaymentController']){
 const source=await readFile(new URL('../RuoYi-Vue/ruoyi-admin/src/main/java/com/ruoyi/web/controller/qicai/'+file+'.java',import.meta.url),'utf8')
 const base=source.match(/@RequestMapping\("([^"]+)"\)/)?.[1] || ''
 for(const item of source.matchAll(/@(Get|Post|Put|Delete)Mapping\(\s*(?:value\s*=\s*)?"([^"]+)"/g)){
  const method=item[1].toUpperCase(),path=base+item[2],pattern=new RegExp('^'+path.replace(/\{[^}]+\}/g,'[^/]+')+'$')
  endpoints.push({method,path,observed2xx:records.some(r=>r.method===method&&r.status>=200&&r.status<300&&pattern.test(r.path))})
 }
}
const visited=reports.flatMap(r=>r.visited||[]),pages=[]
const mini=JSON.parse(await readFile(new URL('../三端配送小程序/pages.json',import.meta.url),'utf8')).pages
for(const {path} of mini){
 const source=await readFile(new URL('../三端配送小程序/'+path+'.vue',import.meta.url),'utf8')
 pages.push({path,kind:'小程序/H5',visited:visited.some(v=>v.endsWith('#/'+path))||(path==='pages/index/index'&&visited.some(v=>v.endsWith(':#/'))),actions:[...new Set([...source.matchAll(/@(?:tap|change|confirm|submit)="([^"]+)"/g)].map(m=>m[1]))],fields:[...new Set([...source.matchAll(/v-model="([^"]+)"/g)].map(m=>m[1]))]})
}
for(const section of ['dashboard','customers','products','couriers','orders','deliveries','coupons'])pages.push({path:'/qicai/'+section,kind:'运营业务页面',visited:visited.includes('admin:/qicai/'+section)})
const tableNames=new Set()
for(const file of ['qicai_delivery.sql','qicai_delivery_upgrade_20260926.sql','qicai_payment_upgrade_20261007.sql','qicai_workflow_upgrade_20261015.sql']){
 const source=await readFile(new URL('../RuoYi-Vue/sql/'+file,import.meta.url),'utf8')
 for(const m of source.matchAll(/CREATE TABLE(?: IF NOT EXISTS)?\s+`?(qc_[a-z_]+)/gi))tableNames.add(m[1])
}
const report={at:new Date().toISOString(),httpCoverageIsNotBusinessAcceptance:true,scope:'16个小程序业务页面和7个运营业务页面；不将未测的若依个人中心、其他模板模块或微信真机计入通过',pages,endpoints,tables:[...tableNames],apiObserved2xx:endpoints.filter(x=>x.observed2xx).length,apiTotal:endpoints.length,pagesVisited:pages.filter(x=>x.visited).length,pagesTotal:pages.length}
await writeFile(new URL('inventory.json',folder),JSON.stringify(report,null,2))
console.log(JSON.stringify({api:report.apiObserved2xx+'/'+report.apiTotal,pages:report.pagesVisited+'/'+report.pagesTotal,tables:report.tables.length,missing:endpoints.filter(x=>!x.observed2xx)}))
