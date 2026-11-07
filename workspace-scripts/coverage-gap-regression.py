"""Additional real isolated API/DB acceptance based on independent review gaps."""
import concurrent.futures, datetime, json, pathlib, sys, time, urllib.request, urllib.error, urllib.parse, uuid, struct, zlib, os
root=pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0,str(root/'tmp/acceptance-20261004/python-deps'))
import pymysql
if os.environ.get('QICAI_ISOLATED_TEST')!='1' or os.environ.get('QICAI_BASE_URL')!='http://127.0.0.1:15190' or not os.environ.get('QICAI_EVIDENCE_DIR') or not os.environ.get('QICAI_ADMIN_PASSWORD'):
    raise RuntimeError('Run via the isolated acceptance runner')
out=pathlib.Path(os.environ['QICAI_EVIDENCE_DIR']).resolve()
base='http://127.0.0.1:15190'
assert out.is_relative_to(root/'tmp') and out!=root/'tmp'
stamp=str(time.time_ns())[-8:]; results=[]; admin_token=None
db=pymysql.connect(host='127.0.0.1',port=13319,user='root',password='',database='qicai_acceptance',autocommit=True,cursorclass=pymysql.cursors.DictCursor)
def sql(query,args=()):
    with db.cursor() as cur:
        cur.execute(query,args);return cur.fetchall() if cur.description else cur.rowcount
assert sql('SELECT @@port AS port,DATABASE() AS db')[0]=={'port':13319,'db':'qicai_acceptance'}
def req(path,method='GET',body=None,token=None,admin=False,raw=None,mime=None):
    headers={'Content-Type':mime or 'application/json'}
    if token:headers['Authorization' if admin else 'X-Qicai-Token']='Bearer '+token if admin else token
    request=urllib.request.Request(base+path,data=raw if raw is not None else json.dumps(body).encode() if body is not None else None,method=method,headers=headers)
    try:r=urllib.request.urlopen(request,timeout=25)
    except urllib.error.HTTPError as error:r=error
    with r:return {'http':r.status,**json.load(r)}
def a(path,method='GET',body=None):return req('/qicai/admin'+path,method,body,admin_token,True)
def m(path,method='GET',body=None,token=None):return req('/qicai/mobile'+path,method,body,token)
def data(r):
    if r['http']>=400 or 'data' not in r or r['data'] is None:raise RuntimeError('Fixture failed: '+str(r['http'])+' '+str(r.get('msg')))
    return r['data']
def check(name,passed,detail=None):
    results.append({'name':name,'passed':bool(passed),'detail':detail});print(('PASS ' if passed else 'FAIL ')+name,flush=True)
def reg(prefix,audience='consumer',tax=None):
    body={'audience':audience,'phone':prefix+stamp,'password':'GapTest123!','contactName':'补漏验收','address':'隔离补漏路1号'}
    if audience=='business':body.update(companyName='补漏企业'+prefix,taxId=tax or '91310000'+stamp+prefix)
    code=data(m('/auth/sms','POST',body))['devCode'];customer=data(m('/auth/register','POST',{**body,'smsCode':code}))
    token=data(m('/auth/login','POST',body))['token'];return body,customer,token
def driver(prefix):
    body={'phone':prefix+stamp,'password':'GapTest123!','name':'补漏配送员','vehicleNo':'QA','status':'ACTIVE','available':True}
    c=data(a('/couriers','POST',body));t=data(m('/courier/login','POST',{'phone':c['phone'],'password':body['password']}))['token'];return body,c,t
def upload(payload,token,filename='proof.png',content='image/png'):
    boundary='qa'+uuid.uuid4().hex
    raw=(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{filename}"\r\nContent-Type: {content}\r\n\r\n').encode()+payload+f'\r\n--{boundary}--\r\n'.encode()
    return req('/qicai/mobile/upload','POST',token=token,raw=raw,mime='multipart/form-data; boundary='+boundary)
def run():
    global admin_token
    admin_token=req('/login','POST',{'username':'admin','password':os.environ['QICAI_ADMIN_PASSWORD']})['token']
    before=sql('SELECT COUNT(*) n FROM qc_sms_code')[0]['n']
    invalid=[m('/auth/sms','POST',body) for body in [{'audience':'consumer','phone':''},{'audience':'courier','phone':'141'+stamp},{'audience':'consumer','phone':'abc'}]]
    check('短信空值/非法身份/非法电话400且无验证码落库',all(r['http']==400 for r in invalid) and sql('SELECT COUNT(*) n FROM qc_sms_code')[0]['n']==before,[r['http'] for r in invalid])
    cb,c,ct=reg('141');bb,b,bt=reg('142','business')
    sql('UPDATE qc_sms_code SET created_at=DATE_SUB(NOW(),INTERVAL 2 MINUTE) WHERE phone=%s',(cb['phone'],))
    code=data(m('/auth/sms','POST',cb))['devCode'];duplicate=m('/auth/register','POST',{**cb,'smsCode':code})
    check('新验证码不能绕过重复手机号约束',duplicate['http']==409 and sql('SELECT COUNT(*) n FROM qc_customer WHERE phone=%s',(cb['phone'],))[0]['n']==1,duplicate['http'])
    duplicate_body={**bb,'phone':'143'+stamp};code=data(m('/auth/sms','POST',duplicate_body))['devCode'];dup=m('/auth/register','POST',{**duplicate_body,'smsCode':code})
    check('重复企业税号409且无第二企业落库',dup['http']==409 and sql('SELECT COUNT(*) n FROM qc_customer WHERE tax_id=%s',(bb['taxId'],))[0]['n']==1,dup['http'])
    rejected=a('/customers/'+str(b['id'])+'/review','PUT',{'status':'REJECTED','version':b['version']})
    relog=m('/auth/login','POST',bb)
    check('企业驳回保存后新登录403且旧会话拒绝',rejected['http']==200 and relog['http']==403 and m('/profile',token=bt)['http']==401,{'login':relog['http']})
    template={'sku':'GAP-'+stamp,'name':'原始快照商品','category':'独立分类'+stamp,'unit':'件','stock':10,'businessPrice':3,'consumerPrice':4,'status':'ACTIVE'}
    p=data(a('/products','POST',template));dup=a('/products','POST',template)
    check('重复SKU409且仅一个商品',dup['http']==409 and sql('SELECT COUNT(*) n FROM qc_product WHERE sku=%s',(template['sku'],))[0]['n']==1,dup['http'])
    category=data(m('/products?category='+urllib.parse.quote(template['category']),token=ct))
    check('商品分类筛选仅返回匹配商品',len(category)==1 and category[0]['id']==p['id'])
    empty=[data(m('/products?'+query,token=ct)) for query in ['keyword=NO-SUCH-'+stamp,'category=NO-SUCH-'+stamp]]
    check('无匹配商品搜索与分类真实返回空数组',empty==[[],[]])
    inactive=data(a('/products','POST',{**template,'sku':'OFF-'+stamp,'status':'INACTIVE'}))
    invalid_details=[m('/products/'+str(i),token=ct)['http'] for i in [inactive['id'],999999999]]
    check('下架及不存在商品详情404',invalid_details==[404,404],invalid_details)
    for value in [-1,0,'NaN','10000000000']:
        r=a('/products','POST',{**template,'sku':'BAD-'+str(value)+'-'+stamp,'consumerPrice':value})
        check('非法或超限商品金额拒绝 '+str(value),r['http']==400,r['http'])
    order=data(m('/orders','POST',{'productId':p['id'],'quantity':1,'idempotencyKey':'snapshot-'+stamp},ct))
    detail=data(m('/orders/'+str(order['id']),token=ct));current=data(a('/products?keyword='+template['sku']))['rows'][0]
    changed=data(a('/products/'+str(p['id']),'PUT',{**current,'name':'修改后商品','consumerPrice':5}))
    stale=a('/products/'+str(p['id']),'PUT',{**current,'name':'不应覆盖'})
    check('商品旧版本编辑409且不覆盖最新值',stale['http']==409 and data(a('/products?keyword='+template['sku']))['rows'][0]['name']=='修改后商品',stale['http'])
    second=data(m('/orders','POST',{'productId':p['id'],'quantity':1,'idempotencyKey':'assign-'+stamp},ct))
    third=data(m('/orders','POST',{'productId':p['id'],'quantity':1,'idempotencyKey':'history-'+stamp},ct))
    deleted=a('/products/'+str(p['id']),'DELETE');after=data(m('/orders/'+str(order['id']),token=ct))
    check('商品归档后历史订单快照和金额完整保留',deleted['http']==200 and after['items']==detail['items'] and after['payableAmount']==detail['payableAmount'])
    check('已归档商品不能再次下单',m('/orders','POST',{'productId':p['id'],'quantity':1,'idempotencyKey':'deleted-'+stamp},ct)['http']==404)
    d1,c1,t1=driver('144');d2,c2,t2=driver('145');d3,c3,t3=driver('146')
    duplicate=a('/couriers','POST',d1)
    check('重复配送手机号409且账号唯一',duplicate['http']==409 and sql('SELECT COUNT(*) n FROM qc_courier WHERE phone=%s',(c1['phone'],))[0]['n']==1)
    updated=data(a('/couriers/'+str(c1['id']),'PUT',{**c1,'available':True,'vehicleNo':'新车'}))
    stale=a('/couriers/'+str(c1['id']),'PUT',{**c1,'available':True,'vehicleNo':'不应覆盖'})
    check('配送员旧版本编辑409且保存车辆不被覆盖',stale['http']==409 and sql('SELECT vehicle_no FROM qc_courier WHERE id=%s',(c1['id'],))[0]['vehicle_no']=='新车')
    disabled=data(a('/couriers/'+str(c3['id']),'PUT',{**c3,'available':False,'status':'DISABLED'}))
    login=m('/courier/login','POST',{'phone':c3['phone'],'password':'GapTest123!'})
    check('停用配送员新登录403且旧Token401',login['http']==403 and m('/courier/tasks',token=t3)['http']==401)
    base_assignment={'orderId':order['id'],'courierId':c3['id'],'warehouseName':'负例仓库','warehouseAddress':'隔离仓库路','distanceKm':1}
    denied=a('/deliveries/assign','POST',base_assignment)
    paused=data(a('/couriers/'+str(c3['id']),'PUT',{**disabled,'status':'ACTIVE','available':False}))
    paused_denied=a('/deliveries/assign','POST',base_assignment)
    check('停用或暂停接单配送员指派404且不落任务/不改订单',denied['http']==404 and paused_denied['http']==404 and not sql('SELECT id FROM qc_delivery_task WHERE order_id=%s',(order['id'],)) and sql('SELECT status FROM qc_order WHERE id=%s',(order['id'],))[0]['status']=='PENDING_SHIPMENT',[denied['http'],paused_denied['http']])
    c3=data(a('/couriers/'+str(c3['id']),'PUT',{**paused,'status':'ACTIVE','available':True}));t3=data(m('/courier/login','POST',{'phone':c3['phone'],'password':'GapTest123!'}))['token']
    assignment={'orderId':second['id'],'warehouseName':'补漏仓库','warehouseAddress':'隔离仓库路','distanceKm':1}
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        replies=list(pool.map(lambda c:a('/deliveries/assign','POST',{**assignment,'courierId':c['id']}),[c1,c2]))
    tasks=sql('SELECT id,courier_id FROM qc_delivery_task WHERE order_id=%s',(second['id'],));task=tasks[0]
    check('同一订单并行指派仅保存一条真实任务',all(r['http']==200 for r in replies) and len(tasks)==1,[r['http'] for r in replies])
    stranger=t2 if task['courier_id']==c1['id'] else t1
    check('跨配送员读取/推进任务404且列表不包含他人任务',m('/courier/tasks/'+str(task['id']),token=stranger)['http']==404 and m('/courier/tasks/'+str(task['id'])+'/status','PUT',{'status':'PICKED_UP'},stranger)['http']==404 and all(r['id']!=task['id'] for r in data(m('/courier/tasks',token=stranger))))
    hist=data(a('/deliveries/assign','POST',{**assignment,'orderId':third['id'],'courierId':c3['id']}))
    png=(root/'三端配送小程序/static/logo.png').read_bytes();proof=data(upload(png,t3))['fileName']
    for status,extra in [('PICKED_UP',{'pickupProofUrls':[proof]}),('IN_DELIVERY',{}),('DELIVERED',{'deliveryProofUrls':[proof],'signedName':'真实历史签收'})]:
        data(m('/courier/tasks/'+str(hist['id'])+'/status','PUT',{'status':status,**extra},t3))
    retired=a('/couriers/'+str(c3['id']),'DELETE');historical=data(a('/orders/'+str(third['id'])))
    check('完成任务后删除配送员保留历史任务和订单凭证',retired['http']==200 and historical['delivery'][0]['id']==hist['id'] and sql('SELECT status FROM qc_delivery_task WHERE id=%s',(hist['id'],))[0]['status']=='DELIVERED')
    coupon_template={'name':'补漏券','audience':'consumer','thresholdAmount':0,'discountAmount':1,'validFrom':'2026-01-01 00:00:00','validTo':'2030-12-31 23:59:59','status':'ACTIVE'}
    coupon=data(a('/coupons','POST',coupon_template))
    grant=a(f"/coupons/{coupon['id']}/grant/{c['id']}",'POST');duplicate=a(f"/coupons/{coupon['id']}/grant/{c['id']}",'POST');missing=a(f"/coupons/{coupon['id']}/grant/999999999",'POST')
    check('重复发券409/不存在客户404且仅一张领券记录',grant['http']==200 and duplicate['http']==409 and missing['http']==404 and sql('SELECT COUNT(*) n FROM qc_customer_coupon WHERE customer_id=%s AND coupon_id=%s',(c['id'],coupon['id']))[0]['n']==1)
    cb2,c4,ct4=reg('147');data(a('/customers/'+str(c4['id'])+'/review','PUT',{'version':c4['version'],'status':'DISABLED'}));disabled_grant=a(f"/coupons/{coupon['id']}/grant/{c4['id']}",'POST')
    check('禁用客户发券409且无领券记录',disabled_grant['http']==409 and sql('SELECT COUNT(*) n FROM qc_customer_coupon WHERE customer_id=%s',(c4['id'],))[0]['n']==0)
    cb5,c5,ct5=reg('148')
    boundary_product=data(a('/products','POST',{**template,'sku':'BOUNDARY-'+stamp,'name':'优惠券边界商品','stock':10}))
    threshold=data(a('/coupons','POST',{**coupon_template,'name':'门槛边界券','thresholdAmount':8}))
    claimed=data(m('/coupons/'+str(threshold['id'])+'/claim','POST',token=ct))['customerCouponId']
    def snapshot():
        return sql('SELECT stock FROM qc_product WHERE id=%s',(boundary_product['id'],)),sql('SELECT COUNT(*) n FROM qc_order WHERE customer_id IN (%s,%s)',(c['id'],c5['id'])),sql('SELECT status,used_order_id FROM qc_customer_coupon WHERE id=%s',(claimed,))
    before=snapshot()
    insufficient=m('/orders','POST',{'productId':boundary_product['id'],'quantity':1,'customerCouponId':claimed,'idempotencyKey':'below-'+stamp},ct)
    stolen=m('/orders','POST',{'productId':boundary_product['id'],'quantity':2,'customerCouponId':claimed,'idempotencyKey':'stolen-'+stamp},ct5)
    check('券门槛不足和跨用户盗用409且订单/库存/券完全不变',insufficient['http']==409 and stolen['http']==409 and snapshot()==before,[insufficient['http'],stolen['http']])
    equal=data(m('/orders','POST',{'productId':boundary_product['id'],'quantity':2,'customerCouponId':claimed,'idempotencyKey':'equal-'+stamp},ct))
    check('金额恰等门槛抵扣真实金额与领券归属保存',float(equal['subtotalAmount'])==8 and float(equal['discountAmount'])==1 and float(equal['payableAmount'])==7 and sql('SELECT status,used_order_id FROM qc_customer_coupon WHERE id=%s',(claimed,))[0]=={'status':'USED','used_order_id':equal['id']})
    cancelled=a('/orders/'+str(equal['id'])+'/cancel','POST')
    check('门槛订单取消恢复原库存和同一领券可用状态',cancelled['http']==200 and sql('SELECT stock FROM qc_product WHERE id=%s',(boundary_product['id'],))[0]['stock']==10 and sql('SELECT status,used_order_id FROM qc_customer_coupon WHERE id=%s',(claimed,))[0]=={'status':'AVAILABLE','used_order_id':None})
    expensive=data(a('/products','POST',{**template,'sku':'MAX-'+stamp,'stock':2,'consumerPrice':'9999999999.99'}))
    count=sql('SELECT COUNT(*) n FROM qc_order WHERE customer_id=%s',(c['id'],))[0]['n']
    overflow=m('/orders','POST',{'productId':expensive['id'],'quantity':2,'idempotencyKey':'overflow-'+stamp},ct)
    check('合法单价但订单总额超上限400且零订单零扣库',overflow['http']==400 and sql('SELECT stock FROM qc_product WHERE id=%s',(expensive['id'],))[0]['stock']==2 and sql('SELECT COUNT(*) n FROM qc_order WHERE customer_id=%s',(c['id'],))[0]['n']==count,overflow['http'])
    bad_dates=[{'validFrom':'bad'},{'validTo':'2020-01-01 00:00:00'},{'discountAmount':0},{'discountAmount':-1},{'discountAmount':'10000000000'}]
    count=sql('SELECT COUNT(*) n FROM qc_coupon')[0]['n'];invalid=[a('/coupons','POST',{**coupon_template,**change})['http'] for change in bad_dates]
    check('非法日期/反向日期/零负及超限券金额400且无模板落库',invalid==[400]*5 and sql('SELECT COUNT(*) n FROM qc_coupon')[0]['n']==count,invalid)
    dashboard=data(a('/dashboard'))
    values={'customers':'SELECT COUNT(*) n FROM qc_customer WHERE deleted=0','pendingEnterprises':"SELECT COUNT(*) n FROM qc_customer WHERE audience='business' AND status='PENDING' AND deleted=0",'products':'SELECT COUNT(*) n FROM qc_product WHERE deleted=0','orders':'SELECT COUNT(*) n FROM qc_order WHERE deleted=0','pendingOrders':"SELECT COUNT(*) n FROM qc_order WHERE status='PENDING_SHIPMENT' AND deleted=0",'activeDeliveries':"SELECT COUNT(*) n FROM qc_delivery_task WHERE status IN ('ASSIGNED','PICKED_UP','IN_DELIVERY')",'revenue':"SELECT COALESCE(SUM(payable_amount),0) n FROM qc_order WHERE status<>'CANCELLED' AND deleted=0"}
    expected={key:str(sql(query)[0]['n']) for key,query in values.items()}
    check('概览所有计数及金额与非空DB逐值一致',all(float(dashboard[key])==float(value) for key,value in expected.items()),expected)
    summary=sql('SELECT status,COUNT(*) count FROM qc_order WHERE deleted=0 GROUP BY status ORDER BY status')
    check('概览状态聚合及最近订单与DB一致',dashboard['statusSummary']==summary and [o['id'] for o in dashboard['recentOrders']]==[o['id'] for o in sql('SELECT id FROM qc_order WHERE deleted=0 ORDER BY id DESC LIMIT 8')])
    before=sql('SELECT COUNT(*) n FROM qc_upload')[0]['n']
    def chunk(kind,content):return struct.pack('>I',len(content))+kind+content+struct.pack('>I',zlib.crc32(kind+content)&0xffffffff)
    big=b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',5100,5000,8,2,0,0,0))+chunk(b'IDAT',zlib.compress((b'\0'+b'\0'*(5100*3))*5000))+chunk(b'IEND',b'')
    uploads=[upload(png,ct,content='text/plain'),upload(png,ct,filename='proof.txt'),upload(png+b'\0'*(5*1024*1024+1),ct),upload(big,ct),upload(b'',ct),upload(png,ct,filename='f'*101+'.png')]
    check('错MIME/错扩展/超5MB/超2500万像素/空文件/超长名受控拒绝', [r['http'] for r in uploads]==[415,415,413,415,400,400],[{'http':r['http'],'msg':r.get('msg')} for r in uploads])
    check('拒绝上传不新增登记记录',sql('SELECT COUNT(*) n FROM qc_upload')[0]['n']==before)
try:run()
except Exception as error:check('补漏API执行完成',False,str(error))
finally:
    db.close();out.mkdir(parents=True,exist_ok=True)
    (out/'gap-api-results.json').write_text(json.dumps({'at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'kind':'real isolated API + MySQL; no mocked responses','results':results},ensure_ascii=False,indent=2),encoding='utf-8')
raise SystemExit(1 if any(not r['passed'] for r in results) else 0)
