import {readFile, mkdir, writeFile} from 'node:fs/promises'
import path from 'node:path'
import {createHash} from 'node:crypto'
import {execFileSync} from 'node:child_process'
import acceptanceContext from './acceptance-context.cjs'
const qa = acceptanceContext(), results = [], startedAt = new Date().toISOString()
const stamp = String(Date.now()).slice(-8)
const bytes = await readFile(new URL('../三端配送小程序/static/logo.png', import.meta.url))
const hash = value => createHash('sha256').update(value).digest('hex')
const headers = (token, admin = false) => token ? {[admin ? 'Authorization' : 'X-Qicai-Token']: admin ? `Bearer ${token}` : token} : {}
async function api(url, method = 'GET', body, token, admin = false) {
  const response = await fetch(qa.base + url, {method, headers: {'Content-Type':'application/json', ...headers(token,admin)}, body: body === undefined ? undefined : JSON.stringify(body), signal:AbortSignal.timeout(20000)})
  return {http:response.status, ...await response.json()}
}
function check(name, passed, detail) {results.push({name, passed:Boolean(passed), detail}); console.log(`${passed?'PASS':'FAIL'} ${name} ${JSON.stringify(detail??'')}`)}
async function image(url, token, admin = false, method = 'GET') {
  const response = await fetch(qa.base + url,{method,headers:headers(token,admin),signal:AbortSignal.timeout(20000)})
  return {http:response.status,hash:hash(Buffer.from(await response.arrayBuffer())),cache:response.headers.get('cache-control')}
}
async function run() {
  const login = await api('/login','POST',{username:'admin',password:qa.password})
  if (!login.token) throw new Error('Isolated administrator login failed')
  const admin = (url,method,body) => api('/qicai/admin'+url,method,body,login.token,true)
  async function customer(prefix) {
    const body = {audience:'consumer',phone:prefix+stamp,password:'QcTest123!',contactName:'凭证安全验收',address:'隔离验收路'}
    const sms = await api('/qicai/mobile/auth/sms','POST',body)
    const registered = await api('/qicai/mobile/auth/register','POST',{...body,smsCode:sms.data?.devCode})
    if (!registered.data?.id) throw new Error('Isolated customer fixture failed')
    const logged = await api('/qicai/mobile/auth/login','POST',body)
    return {id:registered.data.id,token:logged.data.token}
  }
  const owner = await customer('166'), other = await customer('165')
  const form = new FormData(); form.append('file',new Blob([bytes],{type:'image/png'}),'security-proof.png')
  const upload = await fetch(qa.base+'/qicai/mobile/upload',{method:'POST',headers:headers(owner.token),body:form})
  const file = (await upload.json()).data?.fileName
  if (!file) throw new Error('Real image upload failed')
  const guest = await image(file), stranger = await image(file,other.token), owned = await image(file,owner.token), managed = await image(file,login.token,true)
  check('匿名GET不能读取已登记凭证',guest.http===401,{http:guest.http,file})
  check('其他客户不能读取已登记凭证',stranger.http===404,{http:stranger.http})
  check('上传者仍可用header读取原图',owned.http===200&&owned.hash===hash(bytes),{http:owned.http,hashMatches:owned.hash===hash(bytes)})
  check('管理员仍可用Bearer读取原图',managed.http===200&&managed.hash===hash(bytes),{http:managed.http})
  const head = await image(file,undefined,false,'HEAD')
  check('匿名HEAD不能绕过凭证授权',head.http===401,{http:head.http})
  const varied = await image(file.replace('/profile/','/profile/').replace(/[^/]+$/,name=>name.toUpperCase()))
  check('变更文件名大小写不能绕过Windows凭证授权',[401,404].includes(varied.http),{http:varied.http})
  check('私有凭证响应禁止共享缓存',owned.cache?.includes('no-store'),{cache:owned.cache})
  const userName='proof'+stamp
  const createdUser=await api('/system/user','POST',{userName,nickName:'普通权限凭证验收',password:'QcTest123!',sex:'0',status:'0',roleIds:[2],postIds:[1],deptId:103},login.token,true)
  if(createdUser.code!==200)throw new Error('Ordinary system-user fixture failed: '+createdUser.msg)
  const userLogin=await api('/login','POST',{username:userName,password:'QcTest123!'})
  if(!userLogin.token)throw new Error('Ordinary system-user login failed')
  const ordinary=await image(file,userLogin.token,true)
  const alias=await image('/common/download/resource?resource='+encodeURIComponent(file),userLogin.token,true)
  const slashAlias=await image('/common/download/resource/?resource='+encodeURIComponent(file),userLogin.token,true)
  check('普通后台角色不能读取业务凭证或借通用下载绕过',[401,403,404].includes(ordinary.http)&&[401,403,404].includes(alias.http)&&[401,403,404].includes(slashAlias.http),{direct:ordinary.http,alias:alias.http,trailingSlash:slashAlias.http})
  // The previous release used /profile/upload. Seed one registered legacy file
  // in the runner's disposable database to verify upgrades keep old evidence private.
  const legacyForm=new FormData();legacyForm.append('file',new Blob([bytes],{type:'image/png'}),'legacy-proof.png')
  const legacyUpload=await fetch(qa.base+'/common/upload',{method:'POST',headers:headers(login.token,true),body:legacyForm})
  const legacyFile=(await legacyUpload.json()).fileName
  if(!/^\/profile\/upload\/[0-9/]+\/[A-Za-z0-9_.-]+\.png$/.test(legacyFile)||!Number.isSafeInteger(owner.id))throw new Error('Unexpected legacy fixture path')
  execFileSync('mysql',['--no-defaults','--protocol=TCP','--host=127.0.0.1','--port=13319','--user=root','qicai_acceptance'],{input:`INSERT INTO qc_upload(file_name,principal_type,principal_id) VALUES('${legacyFile}','customer',${owner.id});`,windowsHide:true})
  const legacyGuest=await image(legacyFile),legacyOwner=await image(legacyFile,owner.token)
  const legacyAlias=await image(legacyFile.replace(/[^/]+$/,name=>name.toUpperCase()))
  check('升级前upload目录凭证仍按登记归属保护',legacyGuest.http===401&&legacyOwner.http===200&&legacyOwner.hash===hash(bytes),{guest:legacyGuest.http,owner:legacyOwner.http})
  check('旧凭证大小写别名不能退回公开读取',[401,404].includes(legacyAlias.http),{http:legacyAlias.http})
  const stream=await image(legacyFile+'::$DATA')
  const shortName=await image(legacyFile.replace(/([^/]{6})[^/]+\.png$/i,(_,prefix)=>prefix.toUpperCase()+'~1.PNG'))
  const shortDirectory=await image(file.replace('/qicai-proof/','/QICAI-~1/'))
  check('Windows数据流及短文件名别名在旧路径也被明确拒绝',stream.http===400&&shortName.http===400&&shortDirectory.http===400,{stream:stream.http,shortName:shortName.http,shortDirectory:shortDirectory.http})
  const adminAlias=await image('/common/download/resource?resource='+encodeURIComponent(file),login.token,true)
  check('管理员授权的通用下载仍能返回原图',adminAlias.http===200&&adminAlias.hash===hash(bytes),{http:adminAlias.http})
  const courier = (await admin('/couriers','POST',{phone:'164'+stamp,password:'QcTest123!',name:'凭证读取配送员',status:'ACTIVE',available:true})).data
  const driver = (await api('/qicai/mobile/courier/login','POST',{phone:courier.phone,password:'QcTest123!'})).data.token
  const product = (await admin('/products','POST',{sku:'SEC-'+stamp,name:'凭证权限商品',category:'验收',unit:'件',stock:10,businessPrice:1,consumerPrice:2,status:'ACTIVE'})).data
  const order = (await api('/qicai/mobile/orders','POST',{productId:product.id,quantity:1,idempotencyKey:'sec-'+stamp},owner.token)).data
  const task = (await admin('/deliveries/assign','POST',{orderId:order.id,courierId:courier.id,warehouseName:'真实仓库',warehouseAddress:'隔离验收路',distanceKm:1})).data
  const courierForm = new FormData(); courierForm.append('file',new Blob([bytes],{type:'image/png'}),'driver-proof.png')
  const courierUpload = await fetch(qa.base+'/qicai/mobile/upload',{method:'POST',headers:headers(driver),body:courierForm})
  const driverFile = (await courierUpload.json()).data.fileName
  await api('/qicai/mobile/courier/tasks/'+task.id+'/status','PUT',{status:'PICKED_UP',pickupProofUrl:driverFile},driver)
  const relatedCustomer = await image(driverFile,owner.token), unrelatedCustomer = await image(driverFile,other.token)
  check('订单客户可读取所属任务装车凭证',relatedCustomer.http===200&&relatedCustomer.hash===hash(bytes),{http:relatedCustomer.http})
  check('不相关客户不可读取任务装车凭证',unrelatedCustomer.http===404,{http:unrelatedCustomer.http})
  await api('/qicai/mobile/courier/tasks/'+task.id+'/status','PUT',{status:'IN_DELIVERY'},driver)
  await api('/qicai/mobile/orders/'+order.id+'/receipt','POST',{signedName:'验收收货人',proofUrl:file},owner.token)
  const relatedCourier = await image(file,driver)
  check('所属配送员可读取客户签收凭证',relatedCourier.http===200&&relatedCourier.hash===hash(bytes),{http:relatedCourier.http})
  const avatarForm = new FormData(); avatarForm.append('avatarfile',new Blob([bytes],{type:'image/png'}),'avatar.png')
  const avatarUpload = await fetch(qa.base+'/system/user/profile/avatar',{method:'POST',headers:headers(login.token,true),body:avatarForm})
  const avatar = (await avatarUpload.json()).imgUrl
  const publicAvatar = avatar ? await image(avatar) : {http:0}
  check('普通管理员头像兼容原有公开读取',publicAvatar.http===200&&publicAvatar.hash===hash(bytes),{http:publicAvatar.http})
  const loggedOut = await api('/qicai/mobile/auth/session','DELETE',undefined,owner.token)
  const revoked = await image(file,owner.token)
  check('退出后原Token无法继续下载凭证',loggedOut.http===200&&revoked.http===401,{http:revoked.http})
  const health = await api('/qicai/mobile/health')
  check('就绪检查真实报告数据库和Redis探测结果',health.http===200&&health.data?.status==='UP'&&health.data?.database==='UP'&&health.data?.redis==='UP',{http:health.http,details:health.data})
}
let failure
try {await run()} catch(error) {failure=error.message;console.error(error)}
await mkdir(qa.out,{recursive:true})
await writeFile(path.join(qa.out,'security-results.json'),JSON.stringify({startedAt,endedAt:new Date().toISOString(),results,failure,passed:!failure&&results.length===18&&results.every(r=>r.passed)},null,2))
if(failure||results.some(r=>!r.passed)||results.length!==18)process.exitCode=1
