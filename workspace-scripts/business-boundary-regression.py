"""F22/F24 real isolated API + MySQL regressions; no production or mocked responses.

Uses the same isolation gate, HTTP conventions and DB connection as
coverage-gap-regression.py. That script executes on import, so helpers are kept
local here. Only newly created fixtures are mutated. The acceptance runner owns
the disposable database/upload directory and removes them on teardown.
"""
import datetime
from decimal import Decimal
import hashlib
import json
import os
from pathlib import Path
import struct
import sys
import time
import urllib.error
import urllib.request
import uuid
import zlib

ROOT = Path(__file__).resolve().parents[1]
BASE = 'http://127.0.0.1:15190'
if (os.environ.get('QICAI_ISOLATED_TEST') != '1'
        or os.environ.get('QICAI_BASE_URL') != BASE
        or not os.environ.get('QICAI_EVIDENCE_DIR')
        or not os.environ.get('QICAI_ADMIN_PASSWORD')):
    raise RuntimeError('Run via the isolated acceptance runner')
OUT = Path(os.environ['QICAI_EVIDENCE_DIR']).resolve()
if not OUT.is_relative_to(ROOT / 'tmp') or OUT==ROOT/'tmp':
    raise RuntimeError('Evidence must stay inside the isolated acceptance workspace')
OUT.mkdir(parents=True, exist_ok=True)
RESULT = OUT / 'business-boundary-results.json'
if RESULT.exists():
    raise RuntimeError('Refusing to overwrite existing business-boundary evidence')
sys.path.insert(0, str(ROOT / 'tmp/acceptance-20261004/python-deps'))
import pymysql

STARTED = datetime.datetime.now(datetime.timezone.utc).isoformat()
STAMP = str(time.time_ns())[-8:]
PASSWORD = 'BusinessQa123!'
results = []
fixtures = {}


def request(path, method='GET', body=None, token=None, admin=False, raw=None, mime=None, binary=False):
    if not path.startswith('/') or path.startswith('//'):
        raise RuntimeError('Only local absolute API/resource paths are allowed')
    headers = {'Content-Type': mime or 'application/json'}
    if token:
        headers['Authorization' if admin else 'X-Qicai-Token'] = 'Bearer ' + token if admin else token
    payload = raw if raw is not None else json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=payload, method=method, headers=headers)
    try:
        response = urllib.request.urlopen(req, timeout=25)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        content = response.read()
        if binary:
            return {'http': response.status, 'bytes': len(content), 'sha256': hashlib.sha256(content).hexdigest(),
                    'cacheControl': response.headers.get('Cache-Control')}
        return {'http': response.status, **json.loads(content)}


def data(reply):
    if reply['http'] >= 400 or reply.get('code') != 200 or reply.get('data') is None:
        raise RuntimeError('Fixture API failed: HTTP ' + str(reply['http']) + ', code ' + str(reply.get('code')))
    return reply['data']


def check(number, name, passed, detail):
    results.append({'id': number, 'name': name, 'passed': bool(passed), 'detail': detail})
    print(('PASS ' if passed else 'FAIL ') + number + ' ' + name, flush=True)


def png(red, green, blue):
    def chunk(kind, content):
        return struct.pack('>I', len(content)) + kind + content + struct.pack('>I', zlib.crc32(kind + content) & 0xffffffff)
    return (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', 1, 1, 8, 2, 0, 0, 0))
            + chunk(b'IDAT', zlib.compress(bytes([0, red, green, blue]))) + chunk(b'IEND', b''))


def upload(content, token):
    boundary = 'business-' + uuid.uuid4().hex
    raw = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="proof.png"\r\n'
           'Content-Type: image/png\r\n\r\n').encode() + content + f'\r\n--{boundary}--\r\n'.encode()
    return data(request('/qicai/mobile/upload', 'POST', token=token, raw=raw,
                        mime='multipart/form-data; boundary=' + boundary))['fileName']


def run():
    with pymysql.connect(host='127.0.0.1', port=13319, user='root', password='', database='qicai_acceptance',
                         autocommit=True, cursorclass=pymysql.cursors.DictCursor) as db:
        def sql(statement, args=()):
            with db.cursor() as cursor:
                cursor.execute(statement, args)
                return cursor.fetchall() if cursor.description else cursor.rowcount
        if sql('SELECT @@port AS port, DATABASE() AS db')[0] != {'port': 13319, 'db': 'qicai_acceptance'}:
            raise RuntimeError('Unexpected database target')
        login = request('/login', 'POST', {'username': 'admin', 'password': os.environ['QICAI_ADMIN_PASSWORD']})
        if login.get('code') != 200 or not login.get('token'):
            raise RuntimeError('Isolated administrator fixture login failed')
        admin = login['token']
        def a(path, method='GET', body=None):
            return request('/qicai/admin' + path, method, body, admin, True)
        def m(path, method='GET', body=None, token=None):
            return request('/qicai/mobile' + path, method, body, token)
        def customer(prefix):
            body = {'audience': 'consumer', 'phone': prefix + STAMP, 'password': PASSWORD,
                    'contactName': '组合验收客户', 'address': '隔离组合验收地址'}
            code = data(m('/auth/sms', 'POST', body))['devCode']
            row = data(m('/auth/register', 'POST', {**body, 'smsCode': code}))
            token = data(m('/auth/login', 'POST', body))['token']
            return body, row, token
        def courier(prefix):
            body = {'phone': prefix + STAMP, 'password': PASSWORD, 'name': '组合验收配送',
                    'vehicleNo': 'QA', 'available': True, 'status': 'ACTIVE'}
            row = data(a('/couriers', 'POST', body))
            token = data(m('/courier/login', 'POST', body))['token']
            return body, row, token

        customer_body, owner, ct = customer('171')
        _, stranger, stranger_ct = customer('172')
        courier_body, driver, dt = courier('173')
        _, other_driver, other_dt = courier('174')
        product = data(a('/products', 'POST', {'sku': 'BUSINESS-' + STAMP, 'name': '组合验收商品', 'category': '组合验收',
                       'unit': '件', 'stock': 100, 'businessPrice': '10.00', 'consumerPrice': '12.34', 'status': 'ACTIVE'}))
        fixtures.update(customerId=owner['id'], otherCustomerId=stranger['id'], courierId=driver['id'],
                        otherCourierId=other_driver['id'], productId=product['id'], couponIds=[])
        order = data(m('/orders', 'POST', {'productId': product['id'], 'quantity': 1, 'idempotencyKey': 'history-' + STAMP}, ct))
        task = data(a('/deliveries/assign', 'POST', {'orderId': order['id'], 'courierId': driver['id'],
                    'warehouseName': '组合验收仓库', 'warehouseAddress': '隔离仓库路', 'distanceKm': 1}))
        pickup_bytes, receipt_bytes = png(250, 30, 20), png(20, 30, 250)
        pickup, receipt = upload(pickup_bytes, dt), upload(receipt_bytes, ct)
        for status, extra in [('PICKED_UP', {'pickupProofUrls': [pickup]}), ('IN_DELIVERY', {})]:
            data(m('/courier/tasks/' + str(task['id']) + '/status', 'PUT', {'status': status, **extra}, dt))
        completed = data(m('/orders/' + str(order['id']) + '/receipt', 'POST',
                         {'proofUrls': [receipt], 'signedName': '组合验收签收人', 'remark': '重登历史验收'}, ct))
        fixtures.update(orderId=order['id'], taskId=task['id'])

        def history_state():
            return {'order': sql('SELECT id,customer_id,status,subtotal_amount,discount_amount,payable_amount,version FROM qc_order WHERE id=%s', (order['id'],)),
                    'task': sql('SELECT id,order_id,courier_id,status,pickup_proof_url,delivery_proof_url,signed_name,version FROM qc_delivery_task WHERE id=%s', (task['id'],)),
                    'attachments': sql('SELECT delivery_id,phase,file_name FROM qc_delivery_attachment WHERE delivery_id=%s ORDER BY id', (task['id'],)),
                    'events': sql('SELECT id,status,note FROM qc_delivery_event WHERE delivery_id=%s ORDER BY id', (task['id'],)),
                    'uploads': sql('SELECT file_name,principal_type,principal_id FROM qc_upload WHERE file_name IN (%s,%s) ORDER BY file_name', (pickup, receipt))}
        persisted = history_state()
        correct_owners = {row['file_name']: (row['principal_type'], row['principal_id']) for row in persisted['uploads']}
        expected_owners = {pickup: ('courier', driver['id']), receipt: ('customer', owner['id'])}
        phases = {(row['phase'], row['file_name']) for row in persisted['attachments']}
        check('F22-01', '真实客户签收完成订单与任务，双凭证附件及上传归属保存一致',
              completed['status'] == 'COMPLETED' and persisted['order'][0]['status'] == 'COMPLETED'
              and persisted['task'][0]['status'] == 'DELIVERED' and correct_owners == expected_owners
              and phases == {('PICKUP', pickup), ('DELIVERY', receipt)}
              and sum(row['status'] == 'DELIVERED' for row in persisted['events']) == 1,
              {'orderId': order['id'], 'taskId': task['id'], 'uploadOwnershipMatches': correct_owners == expected_owners,
               'attachmentCount': len(phases), 'orderStatus': persisted['order'][0]['status'], 'taskStatus': persisted['task'][0]['status']})

        old_customer, old_courier = ct, dt
        logout = [m('/auth/session', 'DELETE', token=ct), m('/auth/session', 'DELETE', token=dt)]
        old_reads = [m('/orders/' + str(order['id']), token=ct)['http'], m('/courier/tasks/' + str(task['id']), token=dt)['http'],
                     request(receipt, token=ct, binary=True)['http'], request(pickup, token=dt, binary=True)['http']]
        hashes = (hashlib.sha256(ct.encode()).hexdigest(), hashlib.sha256(dt.encode()).hexdigest())
        remaining = sql('SELECT COUNT(*) n FROM qc_session WHERE token_hash IN (%s,%s)', hashes)[0]['n']
        check('F22-02', '双方注销删除数据库会话且旧凭证访问历史与原图均401',
              all(r['http'] == 200 and r['code'] == 200 for r in logout) and old_reads == [401] * 4 and remaining == 0,
              {'oldRequestStatuses': old_reads, 'remainingOldSessions': remaining})
        ct = data(m('/auth/login', 'POST', customer_body))['token']
        dt = data(m('/courier/login', 'POST', courier_body))['token']
        refreshed = data(m('/orders/' + str(order['id']), token=ct))
        refreshed_again = data(m('/orders/' + str(order['id']), token=ct))
        driver_detail = data(m('/courier/tasks/' + str(task['id']), token=dt))
        customer_history = data(m('/orders?status=COMPLETED', token=ct))['rows']
        driver_history = data(m('/courier/tasks?status=DELIVERED', token=dt))
        expected_lists = lambda row: row.get('pickupProofUrls') == [pickup] and row.get('deliveryProofUrls') == [receipt]
        check('F22-03', '双方重新登录与重复读取历史保持状态金额凭证且数据库不变',
              ct != old_customer and dt != old_courier and refreshed == refreshed_again and refreshed == completed
              and refreshed['status'] == 'COMPLETED' and driver_detail['status'] == 'DELIVERED'
              and expected_lists(refreshed['delivery']) and expected_lists(driver_detail)
              and any(row['id'] == order['id'] for row in customer_history) and any(row['id'] == task['id'] for row in driver_history)
              and history_state() == persisted,
              {'customerHistoryIds': [row['id'] for row in customer_history], 'courierHistoryIds': [row['id'] for row in driver_history],
               'repeatedReadStable': refreshed == refreshed_again, 'databaseUnchanged': history_state() == persisted})
        images = []
        for role, token, is_admin in [('customer', ct, False), ('courier', dt, False), ('admin', admin, True)]:
            for phase, file_name, original in [('PICKUP', pickup, pickup_bytes), ('DELIVERY', receipt, receipt_bytes)]:
                got = request(file_name, token=token, admin=is_admin, binary=True)
                images.append({'role': role, 'phase': phase, **got,
                               'originalSha256': hashlib.sha256(original).hexdigest(), 'originalBytes': len(original)})
        check('F22-04', '重登后客户配送员与管理员均读取历史原图，逐张SHA256和字节数一致',
              all(row['http'] == 200 and row['sha256'] == row['originalSha256'] and row['bytes'] == row['originalBytes']
                  and 'no-store' in (row['cacheControl'] or '') for row in images), images)
        foreign_history = [m('/orders/' + str(order['id']), token=stranger_ct)['http'],
                           m('/courier/tasks/' + str(task['id']), token=other_dt)['http']]
        foreign_files = [request(file_name, token=token, binary=True)['http']
                         for token in [stranger_ct, other_dt] for file_name in [pickup, receipt]]
        anonymous = [request(file_name, binary=True)['http'] for file_name in [pickup, receipt]]
        check('F22-05', '完成后跨客户跨配送员仍无权读取历史和原图，匿名拒绝且记录不变',
              foreign_history == [404, 404] and foreign_files == [404] * 4 and anonymous == [401, 401]
              and history_state() == persisted,
              {'foreignHistory': foreign_history, 'foreignImages': foreign_files, 'anonymousImages': anonymous,
               'databaseUnchanged': history_state() == persisted})

        def coupon(label, discount='5.55'):
            dates = sql("SELECT DATE_FORMAT(DATE_SUB(NOW(),INTERVAL 2 HOUR),'%%Y-%%m-%%d %%H:%%i:%%s') AS starts, "
                        "DATE_FORMAT(DATE_ADD(NOW(),INTERVAL 2 HOUR),'%%Y-%%m-%%d %%H:%%i:%%s') AS ends")[0]
            row = data(a('/coupons', 'POST', {'name': '边界券-' + label + '-' + STAMP, 'audience': 'consumer',
                       'thresholdAmount': '10.00', 'discountAmount': discount, 'validFrom': dates['starts'],
                       'validTo': dates['ends'], 'status': 'ACTIVE'}))
            fixtures['couponIds'].append(row['id'])
            return row
        def shift(coupon_id, condition):
            if sql('UPDATE qc_coupon SET ' + condition + ' WHERE id=%s', (coupon_id,)) != 1:
                raise RuntimeError('Coupon boundary fixture was not updated')
        def coupon_row(coupon_id, token=ct):
            return next((row for row in data(m('/coupons', token=token)) if row['couponId'] == coupon_id), None)
        def claim(coupon_id):
            return data(m('/coupons/' + str(coupon_id) + '/claim', 'POST', token=ct))['customerCouponId']
        def state(coupon_id):
            return {'orders': sql('SELECT id,status,subtotal_amount,discount_amount,payable_amount,customer_coupon_id FROM qc_order WHERE customer_id=%s ORDER BY id', (owner['id'],)),
                    'items': sql('SELECT i.id,i.order_id,i.product_id,i.quantity,i.line_amount FROM qc_order_item i JOIN qc_order o ON o.id=i.order_id WHERE o.customer_id=%s ORDER BY i.id', (owner['id'],)),
                    'stock': sql('SELECT stock,version FROM qc_product WHERE id=%s', (product['id'],)),
                    'claims': sql('SELECT id,customer_id,coupon_id,status,used_order_id,used_at FROM qc_customer_coupon WHERE coupon_id=%s ORDER BY id', (coupon_id,))}
        def invalid_checkout(number, label, template, cc_id, expected_status, extra_ok=True, extra=None):
            row = coupon_row(template['id'])
            before = state(template['id'])
            reply = m('/orders', 'POST', {'productId': product['id'], 'quantity': 2, 'customerCouponId': cc_id,
                      'idempotencyKey': label + '-' + STAMP}, ct)
            after = state(template['id'])
            check(number, label + '券列表状态正确且结算409，订单明细库存领券零写入',
                  row is not None and row['claimStatus'] == expected_status and not bool(row['usable'])
                  and reply['http'] == 409 and before == after and extra_ok,
                  {'listStatus': row and row['claimStatus'], 'usable': row and row['usable'], 'checkoutHttp': reply['http'],
                   'allBusinessRowsUnchanged': before == after, **(extra or {})})
        def valid_checkout(number, label, template, cc_id):
            row = coupon_row(template['id'])
            stock_before = sql('SELECT stock FROM qc_product WHERE id=%s', (product['id'],))[0]['stock']
            reply = m('/orders', 'POST', {'productId': product['id'], 'quantity': 2, 'customerCouponId': cc_id,
                      'idempotencyKey': label + '-' + STAMP}, ct)
            saved = data(reply)
            record = sql('SELECT subtotal_amount,discount_amount,payable_amount,customer_coupon_id FROM qc_order WHERE id=%s', (saved['id'],))[0]
            claimed = sql('SELECT status,used_order_id,used_at FROM qc_customer_coupon WHERE id=%s', (cc_id,))[0]
            item = sql('SELECT quantity,unit_price,line_amount FROM qc_order_item WHERE order_id=%s', (saved['id'],))
            expected = {'subtotal_amount': Decimal('24.68'), 'discount_amount': Decimal(str(template['discountAmount'])),
                        'payable_amount': Decimal('24.68') - Decimal(str(template['discountAmount'])), 'customer_coupon_id': cc_id}
            check(number, label + '券可领取或使用，实际抵扣金额明细和领券消费关联保存',
                  row is not None and row['claimStatus'] == 'AVAILABLE' and bool(row['usable']) and reply['http'] == 201
                  and record == expected and all(Decimal(str(saved[key])) == expected[column] for key, column in
                                                [('subtotalAmount', 'subtotal_amount'), ('discountAmount', 'discount_amount'), ('payableAmount', 'payable_amount')])
                  and item == [{'quantity': 2, 'unit_price': Decimal('12.34'), 'line_amount': Decimal('24.68')}]
                  and claimed['status'] == 'USED' and claimed['used_order_id'] == saved['id'] and claimed['used_at'] is not None
                  and sql('SELECT stock FROM qc_product WHERE id=%s', (product['id'],))[0]['stock'] == stock_before - 2,
                  {'orderId': saved['id'], 'persistedAmounts': record, 'claimStatus': claimed['status'],
                   'usedOrderId': claimed['used_order_id'], 'quantity': 2})

        future_unclaimed = coupon('未领未来')
        shift(future_unclaimed['id'], 'valid_from=DATE_ADD(NOW(),INTERVAL 1 HOUR),valid_to=DATE_ADD(NOW(),INTERVAL 2 HOUR)')
        before = state(future_unclaimed['id'])
        future_claim = m('/coupons/' + str(future_unclaimed['id']) + '/claim', 'POST', token=ct)
        future_grant = a('/coupons/' + str(future_unclaimed['id']) + '/grant/' + str(owner['id']), 'POST')
        check('F24-01', '未来未领券列表不展示，客户领取及管理发放拒绝且无业务写入',
              coupon_row(future_unclaimed['id']) is None and future_claim['http'] == 404 and future_grant['http'] == 409
              and state(future_unclaimed['id']) == before,
              {'claimHttp': future_claim['http'], 'grantHttp': future_grant['http'], 'allBusinessRowsUnchanged': state(future_unclaimed['id']) == before})
        future_claimed = coupon('已领未来')
        cc_future = claim(future_claimed['id'])
        shift(future_claimed['id'], 'valid_from=DATE_ADD(NOW(),INTERVAL 1 HOUR),valid_to=DATE_ADD(NOW(),INTERVAL 2 HOUR)')
        invalid_checkout('F24-02', '未来已领INVALID', future_claimed, cc_future, 'INVALID')
        shift(future_claimed['id'], 'valid_from=DATE_SUB(NOW(),INTERVAL 1 SECOND),valid_to=DATE_ADD(NOW(),INTERVAL 2 HOUR)')
        valid_checkout('F24-03', '开始时间刚跨过1秒', future_claimed, cc_future)

        expired = coupon('刚到期')
        cc_expired = claim(expired['id'])
        shift(expired['id'], 'valid_from=DATE_SUB(NOW(),INTERVAL 2 HOUR),valid_to=DATE_SUB(NOW(),INTERVAL 1 SECOND)')
        before = state(expired['id'])
        expired_claim = m('/coupons/' + str(expired['id']) + '/claim', 'POST', token=stranger_ct)
        expired_grant = a('/coupons/' + str(expired['id']) + '/grant/' + str(stranger['id']), 'POST')
        invalid_checkout('F24-04', '结束时间刚跨过1秒EXPIRED', expired, cc_expired, 'EXPIRED',
                         expired_claim['http'] == 404 and expired_grant['http'] == 409 and before == state(expired['id']),
                         {'newClaimHttp': expired_claim['http'], 'newGrantHttp': expired_grant['http']})
        inactive = coupon('停用')
        cc_inactive = claim(inactive['id'])
        changed = data(a('/coupons/' + str(inactive['id']), 'PUT', {**inactive, 'status': 'INACTIVE'}))
        invalid_checkout('F24-05', '有效期内管理端停用INVALID', inactive, cc_inactive, 'INVALID',
                         changed['status'] == 'INACTIVE' and sql('SELECT status FROM qc_coupon WHERE id=%s', (inactive['id'],))[0]['status'] == 'INACTIVE')
        mismatch = coupon('受众不符')
        cc_mismatch = claim(mismatch['id'])
        changed = data(a('/coupons/' + str(mismatch['id']), 'PUT', {**mismatch, 'audience': 'business'}))
        invalid_checkout('F24-06', '受众变更INVALID', mismatch, cc_mismatch, 'INVALID', changed['audience'] == 'business')
        shift(future_unclaimed['id'], 'valid_from=DATE_SUB(NOW(),INTERVAL 1 SECOND),valid_to=DATE_ADD(NOW(),INTERVAL 2 HOUR)')
        just_claimed = claim(future_unclaimed['id'])
        valid_checkout('F24-07', '原未来未领券生效后真实领取', future_unclaimed, just_claimed)
        fixtures['couponClockWindows'] = sql('SELECT id,status,audience,valid_from,valid_to,NOW() AS database_now FROM qc_coupon WHERE id IN ('
                                             + ','.join(['%s'] * len(fixtures['couponIds'])) + ') ORDER BY id', tuple(fixtures['couponIds']))


try:
    run()
except Exception as error:
    check('EXECUTION', '组合业务脚本执行完整', False, {'type': type(error).__name__, 'message': str(error)})
expected = {'F22-' + str(i).zfill(2) for i in range(1, 6)} | {'F24-' + str(i).zfill(2) for i in range(1, 8)}
if {row['id'] for row in results} != expected:
    check('COMPLETENESS', '全部约定12项均实际执行', False, {'missing': sorted(expected - {row['id'] for row in results})})
passed = all(row['passed'] for row in results) and len(results) == 12
RESULT.write_text(json.dumps({'startedAt': STARTED, 'finishedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
                  'kind': 'real isolated API + MySQL + authenticated image bytes; no mocks or browser claims',
                  'clockMethod': 'Only this run coupon rows use MySQL NOW() offsets; start/end crossed by one second, no system clock change or exact timestamp equality claim',
                  'cleanup': 'Fixtures live only in acceptance runner disposable MySQL/upload instance; runner stops services and retains isolated data for evidence review',
                  'fixtures': fixtures, 'results': results, 'passed': passed}, ensure_ascii=False, indent=2, default=str), encoding='utf-8')
raise SystemExit(0 if passed else 1)
