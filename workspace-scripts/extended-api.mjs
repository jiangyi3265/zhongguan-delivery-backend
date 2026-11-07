import fs from 'node:fs/promises'
import path from 'node:path'
import qaContext from './acceptance-context.cjs'
const qa=qaContext(), results=[], stamp=String(Date.now()).slice(-8)
const check=(name,passed,detail)=>{results.push({name,passed:!!passed,detail});console.log(`${passed?'PASS':'FAIL'} ${name}`)}
async function req(url,method='GET',body,token,admin=false) {
 const r=await fetch(qa.base+url,{method,headers:{'Content-Type':'application/json',...(token?{[admin?'Authorization':'X-Qicai-Token']:admin?'Bearer '+token:token}:{})},body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(20000)})
 return {http:r.status,...await r.json()}
}
try {
 const admin=(await req('/login','POST',{username:'admin',password:qa.password,code:'',uuid:''})).token
 if(!admin)throw Error('Admin fixture login failed')
 const a=(url,method,body)=>req('/qicai/admin'+url,method,body,admin,true)
 async function register(prefix){
  const body={audience:'consumer',phone:prefix+stamp,password:'Concurrency123!',contactName:'并发验收',address:'隔离验收路88号'}
  const sms=await req('/qicai/mobile/auth/sms','POST',body)
  const c=await req('/qicai/mobile/auth/register','POST',{...body,smsCode:sms.data?.devCode})
  const login=await req('/qicai/mobile/auth/login','POST',body)
  if(!c.data?.id||!login.data?.token)throw Error(`Customer fixture failed: SMS ${sms.http}, register ${c.http} ${c.msg}, login ${login.http} ${login.msg}`)
  return {id:c.data.id,token:login.data.token}
 }
 const [c,d]=await Promise.all([register('156'),register('155')])
 const product=(await a('/products','POST',{sku:'CONC-'+stamp,name:'有限库存并发验收',category:'验收',unit:'件',stock:2,businessPrice:1,consumerPrice:2,status:'ACTIVE'})).data
 if(!product?.id)throw Error('Product fixture failed')
 const requests=await Promise.all(Array.from({length:6},(_,i)=>req('/qicai/mobile/orders','POST',{productId:product.id,quantity:1,idempotencyKey:'oversell-'+stamp+'-'+i},i%2?c.token:d.token)))
 const success=requests.filter(r=>r.http===201&&r.data?.id)
 check('不同客户并发抢两件库存只生成两个成功订单',success.length===2&&requests.filter(r=>r.http===409).length===4,{statuses:requests.map(r=>r.http)})
 const stock=(await req('/qicai/mobile/products/'+product.id,'GET',undefined,c.token)).data.stock
 check('并发后库存精确为零',stock===0,{stock})
 const all=(await a('/orders?keyword='+encodeURIComponent('并发验收'))).data
 check('成功订单ID互不重复',new Set(success.map(r=>r.data.id)).size===2)
 const cancelled=await Promise.all(success.map(r=>a('/orders/'+r.data.id+'/cancel','POST')))
 check('两个订单均可取消',cancelled.every(r=>r.http===200&&r.data.status==='CANCELLED'))
 const repeated=await Promise.all(Array.from({length:4},()=>a('/orders/'+success[0].data.id+'/cancel','POST')))
 check('同一订单并发重复取消保持幂等',repeated.every(r=>r.http===200&&r.data.status==='CANCELLED'))
 const restored=(await req('/qicai/mobile/products/'+product.id,'GET',undefined,c.token)).data.stock
 check('并发取消库存只恢复至原有两件',restored===2,{stock:restored})
 const before=(await req('/qicai/mobile/orders','GET',undefined,c.token)).data.total
 const invalid=await Promise.all([0,-1,1.5,2147483648].map((quantity,i)=>req('/qicai/mobile/orders','POST',{productId:product.id,quantity,idempotencyKey:'invalid-'+stamp+'-'+i},c.token)))
 check('零负数小数与超大数量全部拒绝',invalid.every(r=>r.http===400),invalid.map(r=>r.http))
 const after=(await req('/qicai/mobile/orders','GET',undefined,c.token)).data.total
 check('非法数量不落订单也不扣库存',before===after&&(await req('/qicai/mobile/products/'+product.id,'GET',undefined,c.token)).data.stock===2)
 const noAdmin=await req('/qicai/admin/orders','GET',undefined,c.token)
 check('客户会话不能读取后台订单',noAdmin.http===401||noAdmin.http===403||noAdmin.code===401||noAdmin.code===403)
 const coupon=(await a('/coupons','POST',{name:'并发券'+stamp,audience:'consumer',thresholdAmount:0,discountAmount:1,validFrom:'2026-01-01 00:00:00',validTo:'2030-12-31 23:59:59',status:'ACTIVE'})).data
 const claim=(await req('/qicai/mobile/coupons/'+coupon.id+'/claim','POST',undefined,c.token)).data
 const use=await Promise.all([0,1].map(i=>req('/qicai/mobile/orders','POST',{productId:product.id,quantity:1,customerCouponId:claim.customerCouponId,idempotencyKey:'coupon-race-'+stamp+'-'+i},c.token)))
 check('同券并发两次下单只能抵扣一次',use.filter(r=>r.http===201).length===1&&use.filter(r=>r.http===409).length===1,use.map(r=>r.http))
 const racedOrder=use.find(r=>r.http===201)?.data
 check('同券竞争只扣一件库存且金额真实抵扣',racedOrder?.payableAmount===1&&(await req('/qicai/mobile/products/'+product.id,'GET',undefined,c.token)).data.stock===1)
 const totalBefore=(await req('/qicai/mobile/orders','GET',undefined,c.token)).data.total
 const partial=await req('/qicai/mobile/orders','POST',{productId:product.id,quantity:1,remark:'R'.repeat(256),idempotencyKey:'rollback-'+stamp},c.token)
 check('扣库存之后字段校验失败整笔事务回滚',partial.http===400&&(await req('/qicai/mobile/products/'+product.id,'GET',undefined,c.token)).data.stock===1&&(await req('/qicai/mobile/orders','GET',undefined,c.token)).data.total===totalBefore,{http:partial.http})
 const driver=(await a('/couriers','POST',{phone:'154'+stamp,password:'Concurrency123!',name:'双方签收回归',vehicleNo:'验收',status:'ACTIVE',available:true})).data
 const dt=(await req('/qicai/mobile/courier/login','POST',{phone:driver.phone,password:'Concurrency123!'})).data.token
 const assigned=await a('/deliveries/assign','POST',{orderId:racedOrder.id,courierId:driver.id,warehouseName:'验收仓',warehouseAddress:'验收仓1号',distanceKm:1})
 const task=assigned.data
 if(!task?.id)throw Error(`Delivery fixture failed: HTTP ${assigned.http} ${assigned.msg}`)
 async function upload(token){
  const form=new FormData();form.append('file',new Blob([await fs.readFile(new URL('../三端配送小程序/static/logo.png',import.meta.url))],{type:'image/png'}),'proof.png')
  const r=await fetch(qa.base+'/qicai/mobile/upload',{method:'POST',headers:{'X-Qicai-Token':token},body:form,signal:AbortSignal.timeout(15000)})
  if(r.status!==201)throw Error('Proof fixture upload failed')
  return (await r.json()).data.fileName
 }
 const [dp,cp]=await Promise.all([upload(dt),upload(c.token)])
 const status=(body)=>req('/qicai/mobile/courier/tasks/'+task.id+'/status','PUT',body,dt)
 if((await status({status:'PICKED_UP',pickupProofUrls:[dp]})).http!==200||(await status({status:'IN_DELIVERY'})).http!==200)throw Error('Delivery transition fixture failed')
 const receipt=await Promise.all([status({status:'DELIVERED',deliveryProofUrls:[dp],signedName:'并发配送签收'}),req('/qicai/mobile/orders/'+racedOrder.id+'/receipt','POST',{proofUrls:[cp],signedName:'并发客户签收'},c.token)])
 const finalTask=(await req('/qicai/mobile/courier/tasks/'+task.id,'GET',undefined,dt)).data
 const finalOrder=(await req('/qicai/mobile/orders/'+racedOrder.id,'GET',undefined,c.token)).data
 check('客户与配送员同时签收状态一致且仅一个完成事件',receipt.every(r=>r.http===200)&&finalOrder.status==='COMPLETED'&&finalTask.status==='DELIVERED'&&finalTask.events.filter(e=>e.status==='DELIVERED').length===1,{statuses:receipt.map(r=>r.http),events:finalTask.events.filter(e=>e.status==='DELIVERED').length})
 const again=await req('/qicai/mobile/orders/'+racedOrder.id+'/receipt','POST',{proofUrls:[cp],signedName:'重复'},c.token)
 check('完成后重复签收不覆盖原签收人与凭证',again.http===200&&(await req('/qicai/mobile/courier/tasks/'+task.id,'GET',undefined,dt)).data.signedName===finalTask.signedName)
} catch(error){check('扩展并发流程完成',false,error.message)}
await fs.writeFile(path.join(qa.out,'extended-api-results.json'),JSON.stringify({at:new Date().toISOString(),results},null,2))
if(results.some(r=>!r.passed))process.exitCode=1
