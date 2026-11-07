"""Confirmed rule: only the assigned courier can resume an exception to its original phase."""
import json
import os
from pathlib import Path
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tmp/acceptance-20261004/python-deps'))
import pymysql
BASE = 'http://127.0.0.1:15190'
if os.environ.get('QICAI_ISOLATED_TEST') != '1' or os.environ.get('QICAI_BASE_URL') != BASE or not os.environ.get('QICAI_ADMIN_PASSWORD') or not os.environ.get('QICAI_EVIDENCE_DIR'):
    raise RuntimeError('Isolated local acceptance context required')
OUT = Path(os.environ['QICAI_EVIDENCE_DIR']) / 'exception-results.json'
started = datetime.now(timezone.utc).isoformat()
stamp = str(int(time.time() * 1000))[-8:]
evidence = {'startedAt': started, 'environment': 'isolated local MySQL/API, synthetic fixtures', 'steps': []}

def request(path, method='GET', body=None, token=None, admin=False, multipart=None):
    headers = {}
    if token:
        headers['Authorization' if admin else 'X-Qicai-Token'] = 'Bearer ' + token if admin else token
    if multipart:
        payload, boundary = multipart
        headers['Content-Type'] = 'multipart/form-data; boundary=' + boundary
    else:
        headers['Content-Type'] = 'application/json'
        payload = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=payload, method=method, headers=headers)
    try:
        response = urllib.request.urlopen(req, timeout=30)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        return {'http': response.status, **json.load(response)}

def require_data(response, name):
    if response['http'] >= 400 or not response.get('data'):
        raise RuntimeError(name + ' fixture failed: ' + str(response['http']) + ' ' + str(response.get('msg')))
    return response['data']

def step(name, result):
    evidence['steps'].append({'name': name, 'http': result['http'], 'message': result.get('msg'), 'status': result.get('data', {}).get('status')})

failure = None
try:
    admin_login = request('/login', 'POST', {'username': 'admin', 'password': os.environ['QICAI_ADMIN_PASSWORD']})
    admin_token = admin_login.get('token')
    if not admin_token:
        raise RuntimeError('Admin login failed')
    def admin(path, method='GET', body=None):
        return request('/qicai/admin' + path, method, body, admin_token, True)
    body = {'audience': 'consumer', 'phone': '169' + stamp, 'password': 'QcTest123!', 'contactName': '异常流程验收', 'address': '隔离验收路'}
    sms = require_data(request('/qicai/mobile/auth/sms', 'POST', body), 'sms')
    customer = require_data(request('/qicai/mobile/auth/register', 'POST', {**body, 'smsCode': sms['devCode']}), 'register')
    token = require_data(request('/qicai/mobile/auth/login', 'POST', body), 'customer login')['token']
    courier = require_data(admin('/couriers', 'POST', {'phone': '168' + stamp, 'password': 'QcTest123!', 'name': '异常任务配送员', 'status': 'ACTIVE', 'available': True}), 'courier')
    driver = require_data(request('/qicai/mobile/courier/login', 'POST', {'phone': courier['phone'], 'password': 'QcTest123!'}), 'courier login')['token']
    product = require_data(admin('/products', 'POST', {'sku': 'EXC-' + stamp, 'name': '异常恢复验收商品', 'category': '验收', 'unit': '件', 'stock': 10, 'businessPrice': 50, 'consumerPrice': 100, 'status': 'ACTIVE'}), 'product')
    order = require_data(request('/qicai/mobile/orders', 'POST', {'productId': product['id'], 'quantity': 1, 'idempotencyKey': 'exception-' + stamp}, token), 'order')
    assignment = {'orderId': order['id'], 'courierId': courier['id'], 'warehouseName': '异常测试仓库', 'warehouseAddress': '隔离仓库路', 'distanceKm': 1}
    task = require_data(admin('/deliveries/assign', 'POST', assignment), 'task')
    evidence['fixtures'] = {'customerId': customer['id'], 'courierId': courier['id'], 'productId': product['id'], 'orderId': order['id'], 'taskId': task['id']}
    boundary = 'qicai-exception-' + stamp
    image = (ROOT / '三端配送小程序/static/logo.png').read_bytes()
    payload = ('--' + boundary + '\r\nContent-Disposition: form-data; name="file"; filename="proof.png"\r\nContent-Type: image/png\r\n\r\n').encode() + image + ('\r\n--' + boundary + '--\r\n').encode()
    proof = require_data(request('/qicai/mobile/upload', 'POST', token=driver, multipart=(payload, boundary)), 'upload')['fileName']

    other = require_data(admin('/couriers', 'POST', {'phone':'167'+stamp,'password':'QcTest123!','name':'其他配送员','status':'ACTIVE','available':True}), 'other courier')
    other_token = require_data(request('/qicai/mobile/courier/login','POST',{'phone':other['phone'],'password':'QcTest123!'}),'other login')['token']
    def expect(name, result, code):
        step(name,result)
        assert result['http']==code, name+': '+str(result)
    with pymysql.connect(host='127.0.0.1', port=13319, user='root', password='', database='qicai_acceptance', autocommit=True, cursorclass=pymysql.cursors.DictCursor) as db:
        with db.cursor() as c:
            c.execute('SELECT @@port AS port,DATABASE() AS db');identity=c.fetchone();assert identity=={'port':13319,'db':'qicai_acceptance'}
            def state():
                c.execute('SELECT o.status AS orderStatus,o.version AS orderVersion,d.status AS taskStatus,d.version AS taskVersion,d.exception_from AS exceptionFrom,p.stock FROM qc_order o JOIN qc_delivery_task d ON d.order_id=o.id JOIN qc_order_item i ON i.order_id=o.id JOIN qc_product p ON p.id=i.product_id WHERE o.id=%s',(order['id'],));return c.fetchone()
            for phase in ['ASSIGNED','PICKED_UP','IN_DELIVERY']:
                before=state();assert before['orderStatus']==before['taskStatus']==phase
                exc=request(f"/qicai/mobile/courier/tasks/{task['id']}/status",'PUT',{'status':'EXCEPTION','note':'隔离验收：车辆异常'},driver);expect(phase+'上报异常',exc,200)
                assert state()['exceptionFrom']==phase
                version=exc['data']['version']
                route=f"/qicai/mobile/courier/tasks/{task['id']}/resume"
                expect(phase+'未登录不能恢复',request(route,'POST',{'version':version}),401)
                expect(phase+'其他配送员不能恢复',request(route,'POST',{'version':version},other_token),404)
                expect(phase+'不能伪造恢复阶段',request(route,'POST',{'version':version,'status':'IN_DELIVERY'},driver),400)
                expect(phase+'缺少版本不能恢复',request(route,'POST',{},driver),400)
                expect(phase+'不能从旧状态接口绕过',request(f"/qicai/mobile/courier/tasks/{task['id']}/status",'PUT',{'status':phase},driver),409)
                expect(phase+'管理员不能重新指派绕过',admin('/deliveries/assign','POST',assignment),409)
                expect(phase+'管理员不能取消已指派订单',admin(f"/orders/{order['id']}/cancel",'POST'),409)
                expect(phase+'买方不能签收异常任务',request(f"/qicai/mobile/orders/{order['id']}/receipt",'POST',{'signedName':'验收','proofUrl':proof},token),409)
                expect(phase+'原配送员恢复',request(route,'POST',{'version':version},driver),200)
                recovered=state();assert recovered['orderStatus']==recovered['taskStatus']==phase and recovered['stock']==9 and recovered['exceptionFrom'] is None
                assert recovered['orderVersion']==before['orderVersion']
                expect(phase+'重复旧请求不能再次恢复',request(route,'POST',{'version':version},driver),409)
                assert state()==recovered
                # A delayed request from a prior exception cannot clear a new exception.
                second=require_data(request(f"/qicai/mobile/courier/tasks/{task['id']}/status",'PUT',{'status':'EXCEPTION','note':'新异常'},driver),'second exception')
                expect(phase+'旧版本不能解除新异常',request(route,'POST',{'version':version},driver),409)
                expect(phase+'新版本恢复',request(route,'POST',{'version':second['version']},driver),200)
                fresh=require_data(request(f"/qicai/mobile/orders/{order['id']}",token=token),'buyer reread');assert fresh['status']==fresh['delivery']['status']==phase
                if phase!='IN_DELIVERY':
                    next_phase='PICKED_UP' if phase=='ASSIGNED' else 'IN_DELIVERY'
                    data={'status':next_phase}
                    if next_phase=='PICKED_UP':data['pickupProofUrl']=proof
                    expect('恢复后继续'+next_phase,request(f"/qicai/mobile/courier/tasks/{task['id']}/status",'PUT',data,driver),200)
            customer_proof=require_data(request('/qicai/mobile/upload','POST',token=token,multipart=(payload,boundary)),'customer upload')['fileName']
            expect('恢复后买方签收完成闭环',request(f"/qicai/mobile/orders/{order['id']}/receipt",'POST',{'signedName':'验收签收','proofUrl':customer_proof},token),200)
            final=state();assert final['orderStatus']=='COMPLETED' and final['taskStatus']=='DELIVERED' and final['stock']==9
            c.execute('SELECT id,status,note FROM qc_delivery_event WHERE delivery_id=%s ORDER BY id',(task['id'],));events=c.fetchall()
            assert len([e for e in events if '恢复原阶段' in (e['note'] or '')])==6
            assert len(set(e['id'] for e in events))==len(events)
            evidence['database']=final;evidence['events']=events
            fresh_token=require_data(request('/qicai/mobile/auth/login','POST',body),'relogin')['token']
            fresh=require_data(request(f"/qicai/mobile/orders/{order['id']}",token=fresh_token),'buyer relogin reread');assert fresh['status']=='COMPLETED' and fresh['delivery']['status']=='DELIVERED'
    evidence.update(passed=True,status='已实测通过',expected='本任务配送员按原阶段恢复；跨用户、旧版本、重新指派及异常签收拒绝；恢复后继续至真实签收且库存不重复变动',businessRule='用户2026-10-15确认原阶段恢复；替换原未定规则的任意出口断言，保留历史失败证据')
except Exception as error:
    failure = str(error)
    evidence.update(passed=False, status='失败' if evidence['steps'] else '环境阻塞', failure=failure)
evidence['finishedAt'] = datetime.now(timezone.utc).isoformat()
OUT.parent.mkdir(parents=True, exist_ok=True)
OUT.write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(evidence, ensure_ascii=False))
sys.exit(0 if evidence.get('passed') else 1)
