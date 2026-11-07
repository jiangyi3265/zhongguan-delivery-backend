"""Real API/MySQL checks for one-time codes, expiring sessions and every admin route."""
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tmp/acceptance-20261004/python-deps'))
import pymysql
BASE = 'http://127.0.0.1:15190'
if os.environ.get('QICAI_ISOLATED_TEST') != '1' or os.environ.get('QICAI_BASE_URL') != BASE or not os.environ.get('QICAI_EVIDENCE_DIR') or not os.environ.get('QICAI_ADMIN_PASSWORD'):
    raise RuntimeError('Run via python scripts/acceptance.py: isolated acceptance environment required')
OUT = Path(os.environ['QICAI_EVIDENCE_DIR']) / 'session-results.json'
results, routes = [], []
started = datetime.now(timezone.utc).isoformat()
stamp = str(int(time.time() * 1000))[-8:]


def request(path, method='GET', body=None, token=None, admin=False):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization' if admin else 'X-Qicai-Token'] = 'Bearer ' + token if admin else token
    req = urllib.request.Request(BASE + path, data=json.dumps(body).encode() if body is not None else None, method=method, headers=headers)
    try:
        response = urllib.request.urlopen(req, timeout=30)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        return {'http': response.status, **json.load(response)}


def data(response, description):
    if response['http'] >= 400 or not response.get('data'):
        raise RuntimeError(description + ' failed: HTTP ' + str(response['http']) + ' ' + str(response.get('msg')))
    return response['data']


def check(name, passed, detail):
    results.append({'name': name, 'passed': bool(passed), 'detail': detail})
    print(('PASS ' if passed else 'FAIL ') + name + ' ' + json.dumps(detail, ensure_ascii=False))


def run():
    with pymysql.connect(host='127.0.0.1', port=13319, user='root', password='', database='qicai_acceptance', autocommit=True, cursorclass=pymysql.cursors.DictCursor) as db:
        def sql(statement, args=()):
            with db.cursor() as cursor:
                cursor.execute(statement, args)
                return cursor.fetchall() if cursor.description else cursor.rowcount
        def account(phone):
            return {'audience': 'consumer', 'phone': phone, 'password': 'QcTest123!', 'contactName': '会话验收', 'address': '隔离验收地址'}
        expired_body = account('159' + stamp)
        expired_code = data(request('/qicai/mobile/auth/sms', 'POST', expired_body), 'expired SMS')['devCode']
        if sql("UPDATE qc_sms_code SET expires_at=DATE_SUB(NOW(),INTERVAL 1 MINUTE) WHERE phone=%s AND audience='consumer'", (expired_body['phone'],)) != 1:
            raise RuntimeError('Unexpected expired SMS fixture row count')
        expired_registration = request('/qicai/mobile/auth/register', 'POST', {**expired_body, 'smsCode': expired_code})
        expired_rows = sql("SELECT COUNT(*) AS n FROM qc_customer WHERE audience='consumer' AND phone=%s", (expired_body['phone'],))[0]['n']
        check('过期短信验证码拒绝注册且无客户落库', expired_registration['http'] == 400 and expired_rows == 0, {'http': expired_registration['http'], 'customerRows': expired_rows})

        original_body = account('158' + stamp)
        original_code = data(request('/qicai/mobile/auth/sms', 'POST', original_body), 'one-time SMS')['devCode']
        customer = data(request('/qicai/mobile/auth/register', 'POST', {**original_body, 'smsCode': original_code}), 'register')
        customer_token = data(request('/qicai/mobile/auth/login', 'POST', original_body), 'customer login')['token']
        new_phone = '157' + stamp
        updated = data(request('/qicai/mobile/profile', 'PUT', {**customer, 'phone': new_phone}, customer_token), 'phone update')
        consumed_registration = request('/qicai/mobile/auth/register', 'POST', {**original_body, 'smsCode': original_code})
        original_phone_rows = sql("SELECT COUNT(*) AS n FROM qc_customer WHERE audience='consumer' AND phone=%s", (original_body['phone'],))[0]['n']
        consumed = sql("SELECT consumed FROM qc_sms_code WHERE phone=%s AND audience='consumer'", (original_body['phone'],))[0]['consumed']
        check('已消费短信码不可在释放原手机号后再次注册', consumed_registration['http'] == 400 and original_phone_rows == 0 and consumed == 1, {'http': consumed_registration['http'], 'oldPhoneCustomerRows': original_phone_rows, 'consumed': consumed})
        customer_session_hash = hashlib.sha256(customer_token.encode()).hexdigest()
        before_expiry = request('/qicai/mobile/profile', token=customer_token)
        if sql("UPDATE qc_session SET expires_at=DATE_SUB(NOW(),INTERVAL 1 MINUTE) WHERE token_hash=%s AND principal_type='customer' AND principal_id=%s", (customer_session_hash, customer['id'])) != 1:
            raise RuntimeError('Unexpected customer session fixture row count')
        expired_profile = request('/qicai/mobile/profile', token=customer_token)
        expired_orders = request('/qicai/mobile/orders', token=customer_token)
        check('客户数据库会话到期后资料和订单均401', before_expiry['http'] == 200 and expired_profile['http'] == 401 and expired_orders['http'] == 401, {'before': before_expiry['http'], 'profile': expired_profile['http'], 'orders': expired_orders['http']})
        fresh_login = data(request('/qicai/mobile/auth/login', 'POST', {**original_body, 'phone': new_phone}), 'fresh login')
        fresh_profile = request('/qicai/mobile/profile', token=fresh_login['token'])
        check('过期会话拒绝后客户重新登录恢复本人资料', fresh_profile['http'] == 200 and fresh_profile['data']['id'] == customer['id'], {'http': fresh_profile['http'], 'sameCustomer': fresh_profile.get('data', {}).get('id') == customer['id']})

        admin_token = request('/login', 'POST', {'username': 'admin', 'password': os.environ['QICAI_ADMIN_PASSWORD']}).get('token')
        if not admin_token:
            raise RuntimeError('Admin fixture login failed')
        def admin(path, method='GET', body=None):
            return request('/qicai/admin' + path, method, body, admin_token, True)
        courier_body = {'phone': '156' + stamp, 'password': 'QcTest123!', 'name': '会话配送员', 'status': 'ACTIVE', 'available': True}
        courier = data(admin('/couriers', 'POST', courier_body), 'courier create')
        driver = data(request('/qicai/mobile/courier/login', 'POST', courier_body), 'courier login')['token']
        before_driver_expiry = request('/qicai/mobile/courier/tasks', token=driver)
        driver_hash = hashlib.sha256(driver.encode()).hexdigest()
        if sql("UPDATE qc_session SET expires_at=DATE_SUB(NOW(),INTERVAL 1 MINUTE) WHERE token_hash=%s AND principal_type='courier' AND principal_id=%s", (driver_hash, courier['id'])) != 1:
            raise RuntimeError('Unexpected courier session fixture row count')
        driver_read = request('/qicai/mobile/courier/tasks', token=driver)
        driver_write = request('/qicai/mobile/courier/availability', 'PUT', {'available': False}, driver)
        availability = sql('SELECT available FROM qc_courier WHERE id=%s', (courier['id'],))[0]['available']
        check('配送员数据库会话到期后读写401且接单状态不变', before_driver_expiry['http'] == 200 and driver_read['http'] == 401 and driver_write['http'] == 401 and availability == 1, {'before': before_driver_expiry['http'], 'read': driver_read['http'], 'write': driver_write['http'], 'available': availability})

        product_body = {'sku': 'SESSION-' + stamp, 'name': '权限验收商品', 'category': '验收', 'unit': '件', 'stock': 20, 'businessPrice': 50, 'consumerPrice': 100, 'status': 'ACTIVE'}
        product = data(admin('/products', 'POST', product_body), 'product create')
        order = data(request('/qicai/mobile/orders', 'POST', {'productId': product['id'], 'quantity': 1, 'idempotencyKey': 'session-' + stamp}, fresh_login['token']), 'order create')
        coupon_body = {'name': '权限验收券', 'audience': 'consumer', 'thresholdAmount': 0, 'discountAmount': 10, 'validFrom': '2020-01-01 00:00:00', 'validTo': '2035-01-01 00:00:00', 'status': 'ACTIVE'}
        coupon = data(admin('/coupons', 'POST', coupon_body), 'coupon create')
        ordinary_name = 'session' + stamp
        role_key = 'session_' + stamp
        role_create = request('/system/role', 'POST', {'roleName': '业务权限验收' + stamp, 'roleKey': role_key, 'roleSort': 99, 'status': '0', 'menuIds': [], 'deptIds': [], 'dataScope': '1', 'menuCheckStrictly': True, 'deptCheckStrictly': True}, admin_token, True)
        if role_create.get('code') != 200:
            raise RuntimeError('Ordinary role fixture creation failed')
        role_rows = sql("SELECT role_id FROM sys_role WHERE role_key=%s AND del_flag='0'", (role_key,))
        if len(role_rows) != 1:
            raise RuntimeError('Ordinary role fixture was not persisted exactly once')
        ordinary_create = request('/system/user', 'POST', {'userName': ordinary_name, 'nickName': '普通角色权限验收', 'password': 'QcTest123!', 'sex': '0', 'status': '0', 'roleIds': [role_rows[0]['role_id']], 'postIds': [1], 'deptId': 103}, admin_token, True)
        if ordinary_create.get('code') != 200:
            raise RuntimeError('Ordinary user create failed')
        ordinary_token = request('/login', 'POST', {'username': ordinary_name, 'password': 'QcTest123!'}).get('token')
        if not ordinary_token:
            raise RuntimeError('Ordinary user login failed')
        ordinary_info = request('/getInfo', token=ordinary_token, admin=True)
        if ordinary_info.get('roles') != [role_key] or ordinary_info.get('permissions') != []:
            raise RuntimeError('Fixture must have a persisted ordinary role and no function permissions')
        controller = ROOT / 'RuoYi-Vue/ruoyi-admin/src/main/java/com/ruoyi/web/controller/qicai/QicaiAdminController.java'
        declared = re.findall(r'@(Get|Post|Put|Delete)Mapping\("([^"]+)"\)', controller.read_text(encoding='utf-8'))
        if not declared:
            raise RuntimeError('No admin routes discovered; cannot claim authorization coverage')
        for verb, pattern in declared:
            method = verb.upper()
            resource = pattern.split('/')[1]
            ids = {'customers': customer['id'], 'products': product['id'], 'orders': order['id'], 'couriers': courier['id'], 'coupons': coupon['id']}
            resolved = pattern.replace('{id}', str(ids.get(resource, 1))).replace('{couponId}', str(coupon['id'])).replace('{customerId}', str(customer['id']))
            if re.search(r'[{}]', resolved):
                raise RuntimeError('Unsupported admin path fixture: ' + pattern)
            body = None
            if method in ('PUT', 'POST'):
                body = {}
                if resource == 'customers':
                    body = {'status': 'ACTIVE', 'version': updated['version']}
                elif resource == 'products':
                    body = {**product_body, 'sku': 'UNAUTHORIZED-' + stamp, 'version': product['version']}
                elif resource == 'couriers':
                    body = {**courier_body, 'phone': '155' + stamp, 'version': courier['version']}
                elif resource == 'coupons':
                    body = {**coupon_body, 'version': coupon['version']}
                elif resource == 'deliveries':
                    body = {'orderId': order['id'], 'courierId': courier['id'], 'warehouseName': '禁止越权仓库', 'warehouseAddress': '验收路', 'distanceKm': 1}
            if method == 'DELETE' and resource == 'coupons':
                resolved += '?version=' + str(coupon['version'])
            reply = request('/qicai/admin' + resolved, method, body, ordinary_token, True)
            routes.append({'method': method, 'declaredPath': pattern, 'http': reply['http'], 'code': reply.get('code'), 'passed': reply['http'] == 403 and reply.get('code') == 403})
        check('普通后台角色拒绝Controller全部已声明接口', len(routes) == len(declared) and all(item['passed'] for item in routes), {'declared': len(declared), 'attempted': len(routes), 'denied': sum(item['passed'] for item in routes), 'persistedOrdinaryRole': True, 'roleCount': len(ordinary_info['roles']), 'permissionCount': len(ordinary_info['permissions'])})
        persisted = sql('SELECT o.status AS orderStatus,c.status AS customerStatus,p.deleted AS productDeleted,q.deleted AS courierDeleted,q.available,p.stock FROM qc_order o JOIN qc_customer c ON c.id=o.customer_id JOIN qc_order_item i ON i.order_id=o.id JOIN qc_product p ON p.id=i.product_id JOIN qc_courier q ON q.id=%s WHERE o.id=%s', (courier['id'], order['id']))[0]
        changed_rows = sql("SELECT (SELECT COUNT(*) FROM qc_product WHERE sku=%s)+(SELECT COUNT(*) FROM qc_courier WHERE phone=%s)+(SELECT COUNT(*) FROM qc_delivery_task WHERE order_id=%s)+(SELECT COUNT(*) FROM qc_customer_coupon WHERE customer_id=%s) AS n", ('UNAUTHORIZED-' + stamp, '155' + stamp, order['id'], customer['id']))[0]['n']
        check('权限拒绝后订单库存配送员及发券无副作用', persisted == {'orderStatus': 'PENDING_SHIPMENT', 'customerStatus': 'ACTIVE', 'productDeleted': 0, 'courierDeleted': 0, 'available': 1, 'stock': 19} and changed_rows == 0, {'state': persisted, 'unauthorizedRows': changed_rows})


failure = None
try:
    run()
except Exception as error:
    failure = str(error)
    print('ERROR ' + failure)
OUT.parent.mkdir(parents=True, exist_ok=True)
passed = not failure and len(results) == 7 and all(item['passed'] for item in results)
OUT.write_text(json.dumps({'startedAt': started, 'finishedAt': datetime.now(timezone.utc).isoformat(), 'results': results, 'adminRoutes': routes, 'failure': failure, 'passed': passed}, ensure_ascii=False, indent=2), encoding='utf-8')
sys.exit(0 if passed else 1)
