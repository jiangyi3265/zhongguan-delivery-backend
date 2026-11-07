"""Deterministic isolated MySQL/API regression for courier assignment races.

The local runner creates qicai_acceptance on 127.0.0.1:13319 with disposable
root credentials. No production URL/database override is accepted.
Prerequisite: PyMySQL 1.1.2 (also found in the acceptance runner's python-deps).
"""
import concurrent.futures
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
if os.environ.get('QICAI_ISOLATED_TEST') != '1' or os.environ.get('QICAI_BASE_URL') != BASE or not os.environ.get('QICAI_EVIDENCE_DIR') or not os.environ.get('QICAI_ADMIN_PASSWORD'):
    raise RuntimeError('Run via python scripts/acceptance.py: isolated acceptance environment and fixture credentials are required')
REPORT = Path(os.environ.get('QICAI_CONCURRENCY_REPORT', str(Path(os.environ['QICAI_EVIDENCE_DIR']) / 'backend-concurrency-results.json')))
results = []
started = datetime.now(timezone.utc).isoformat()
stamp = str(int(time.time() * 1000))[-8:]


def request(path, method='GET', body=None, token=None, admin=False):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization' if admin else 'X-Qicai-Token'] = 'Bearer ' + token if admin else token
    req = urllib.request.Request(BASE + path, data=json.dumps(body).encode() if body is not None else None, method=method, headers=headers)
    try:
        response = urllib.request.urlopen(req, timeout=20)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        return {'http': response.status, **json.load(response)}


def connect():
    return pymysql.connect(host='127.0.0.1', port=13319, user='root', password='', database='qicai_acceptance', autocommit=True, cursorclass=pymysql.cursors.DictCursor)


def wait_until(predicate, label):
    deadline = time.monotonic() + 12
    while time.monotonic() < deadline:
        if predicate():
            return
        time.sleep(0.05)
    raise RuntimeError('Timed out waiting for ' + label)


def run():
    admin_token = request('/login', 'POST', {'username': 'admin', 'password': os.environ['QICAI_ADMIN_PASSWORD']}).get('token')
    if not admin_token:
        raise RuntimeError('Isolated admin login failed')
    def admin(path, method='GET', body=None):
        return request('/qicai/admin' + path, method, body, admin_token, True)
    customer_body = {'audience': 'consumer', 'phone': '173' + stamp, 'password': 'QcTest123!', 'contactName': '锁顺序验收', 'address': '隔离测试路'}
    sms = request('/qicai/mobile/auth/sms', 'POST', customer_body)
    customer = request('/qicai/mobile/auth/register', 'POST', {**customer_body, 'smsCode': sms['data']['devCode']})['data']
    token = request('/qicai/mobile/auth/login', 'POST', customer_body)['data']['token']
    product = admin('/products', 'POST', {'sku': 'LOCK-' + stamp, 'name': '并发库存商品', 'category': '验收', 'unit': '件', 'stock': 10, 'businessPrice': 50, 'consumerPrice': 100, 'status': 'ACTIVE'})['data']
    for index, mode in enumerate(['delete', 'disable']):
        courier = admin('/couriers', 'POST', {'phone': ('172' if index == 0 else '171') + stamp, 'password': 'QcTest123!', 'name': '并发配送员', 'status': 'ACTIVE', 'available': True})['data']
        order = request('/qicai/mobile/orders', 'POST', {'productId': product['id'], 'quantity': 1, 'idempotencyKey': f'lock-{mode}-{stamp}'}, token)['data']
        with connect() as blocker, connect() as observer, concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            blocker.begin()
            with blocker.cursor() as cursor:
                cursor.execute('SELECT id FROM qc_order WHERE id=%s FOR UPDATE', (order['id'],))
            try:
                assigning = pool.submit(admin, '/deliveries/assign', 'POST', {'orderId': order['id'], 'courierId': courier['id'], 'warehouseName': '验收仓库', 'warehouseAddress': '验收仓库路', 'distanceKm': 1})
                def assignment_waiting():
                    with observer.cursor() as cursor:
                        cursor.execute('SELECT COUNT(*) AS n FROM performance_schema.data_lock_waits WHERE BLOCKING_THREAD_ID=(SELECT THREAD_ID FROM performance_schema.threads WHERE PROCESSLIST_ID=%s)', (blocker.thread_id(),))
                        return cursor.fetchone()['n'] > 0
                wait_until(assignment_waiting, 'assignment to reach the locked order')
                if mode == 'delete':
                    retiring = pool.submit(admin, f"/couriers/{courier['id']}", 'DELETE')
                else:
                    retiring = pool.submit(admin, f"/couriers/{courier['id']}", 'PUT', {**courier, 'status': 'DISABLED', 'available': False})
                def retirement_waiting_or_finished():
                    if retiring.done():
                        return True
                    with observer.cursor() as cursor:
                        cursor.execute("SELECT COUNT(*) AS n FROM performance_schema.data_lock_waits w JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID AND l.ENGINE=w.ENGINE WHERE l.OBJECT_SCHEMA='qicai_acceptance' AND l.OBJECT_NAME='qc_courier'")
                        return cursor.fetchone()['n'] > 0
                wait_until(retirement_waiting_or_finished, 'courier retirement response or row lock')
            finally:
                blocker.rollback()
            assignment, retirement = assigning.result(), retiring.result()
            with observer.cursor() as cursor:
                cursor.execute('SELECT c.status,c.deleted,d.id AS taskId,d.status AS taskStatus FROM qc_courier c LEFT JOIN qc_delivery_task d ON d.courier_id=c.id WHERE c.id=%s', (courier['id'],))
                state = cursor.fetchone()
            passed = assignment['http'] == 200 and retirement['http'] == 409 and state['status'] == 'ACTIVE' and state['deleted'] == 0 and state['taskStatus'] == 'ASSIGNED'
            result = {'name': f'分配与{mode}并发不能产生停用配送员的活动任务', 'passed': passed, 'assignmentHttp': assignment['http'], 'retirementHttp': retirement['http'], 'databaseState': state, 'orderId': order['id'], 'courierId': courier['id']}
            results.append(result)
            print(('PASS ' if passed else 'FAIL ') + json.dumps(result, ensure_ascii=False))

    # Hold the product row until both customers have reached it. Before the fix,
    # each request held the same absent-order index gap; releasing the product
    # deterministically creates product-row / insert-intention lock inversion.
    second_body = {**customer_body, 'phone': '170' + stamp}
    second_sms = request('/qicai/mobile/auth/sms', 'POST', second_body)
    second_customer = request('/qicai/mobile/auth/register', 'POST', {**second_body, 'smsCode': second_sms['data']['devCode']})['data']
    second_token = request('/qicai/mobile/auth/login', 'POST', second_body)['data']['token']
    stock_product = admin('/products', 'POST', {'sku': 'STOCK-LOCK-' + stamp, 'name': '跨客户间隙锁验收', 'category': '验收', 'unit': '件', 'stock': 2, 'businessPrice': 50, 'consumerPrice': 100, 'status': 'ACTIVE'})['data']
    with connect() as blocker, connect() as observer, concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        blocker.begin()
        with blocker.cursor() as cursor:
            cursor.execute('SELECT id FROM qc_product WHERE id=%s FOR UPDATE', (stock_product['id'],))
        try:
            ordering = [pool.submit(request, '/qicai/mobile/orders', 'POST', {'productId': stock_product['id'], 'quantity': 1, 'idempotencyKey': 'stock-gap-' + stamp}, customer_token) for customer_token in (token, second_token)]
            def both_orders_waiting():
                with observer.cursor() as cursor:
                    cursor.execute("SELECT COUNT(DISTINCT w.REQUESTING_THREAD_ID) AS n FROM performance_schema.data_lock_waits w JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID AND l.ENGINE=w.ENGINE WHERE l.OBJECT_SCHEMA='qicai_acceptance' AND l.OBJECT_NAME='qc_product' AND l.INDEX_NAME='PRIMARY' AND l.LOCK_DATA=%s", (str(stock_product['id']),))
                    return cursor.fetchone()['n'] == 2
            wait_until(both_orders_waiting, 'both customer orders to reach the product row lock')
        finally:
            blocker.rollback()
        replies = [future.result() for future in ordering]
        with observer.cursor() as cursor:
            cursor.execute('SELECT stock FROM qc_product WHERE id=%s', (stock_product['id'],))
            remaining = cursor.fetchone()['stock']
            cursor.execute('SELECT COUNT(*) AS orders,COALESCE(SUM(i.quantity),0) AS quantity FROM qc_order o JOIN qc_order_item i ON i.order_id=o.id WHERE i.product_id=%s', (stock_product['id'],))
            persisted = cursor.fetchone()
        result = {'name': '两个客户等待同商品锁后下单均成功且无间隙锁死锁', 'passed': all(reply['http'] == 201 for reply in replies) and remaining == 0 and persisted['orders'] == 2 and int(persisted['quantity']) == 2, 'http': [reply['http'] for reply in replies], 'stock': remaining, 'orders': persisted['orders'], 'quantity': int(persisted['quantity']), 'customerIds': [customer['id'], second_customer['id']], 'productId': stock_product['id']}
        results.append(result)
        print(('PASS ' if result['passed'] else 'FAIL ') + json.dumps(result, ensure_ascii=False))

    # Exercise both login entry points in one batch. final-2 reproduced a real
    # session INSERT deadlock when two new customers logged in concurrently.
    accounts = []
    for prefix in ['139', '138']:
        body = {**customer_body, 'phone': prefix + stamp}
        code = request('/qicai/mobile/auth/sms', 'POST', body)['data']['devCode']
        registered = request('/qicai/mobile/auth/register', 'POST', {**body, 'smsCode': code})['data']
        accounts.append(('customer', registered['id'], '/qicai/mobile/auth/login', body))
    for prefix in ['137', '136']:
        registered = admin('/couriers', 'POST', {'phone': prefix + stamp, 'password': 'QcTest123!', 'name': '并发登录验收', 'status': 'ACTIVE', 'available': True})['data']
        accounts.append(('courier', registered['id'], '/qicai/mobile/courier/login', {'phone': registered['phone'], 'password': 'QcTest123!'}))
    for phase in ['新账号同时登录', '清理过期会话时同时登录']:
        if phase.startswith('清理'):
            with connect() as db, db.cursor() as cursor:
                for kind, identifier, _, _ in accounts:
                    cursor.execute('UPDATE qc_session SET expires_at=DATE_SUB(NOW(),INTERVAL 1 MINUTE) WHERE principal_type=%s AND principal_id=%s', (kind, identifier))
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
            pending = [pool.submit(request, endpoint, 'POST', body) for _, _, endpoint, body in accounts for _ in range(2)]
            replies = [future.result() for future in pending]
        counts = []
        with connect() as db, db.cursor() as cursor:
            for kind, identifier, _, _ in accounts:
                cursor.execute('SELECT COUNT(*) AS sessions,SUM(expires_at<NOW()) AS expired FROM qc_session WHERE principal_type=%s AND principal_id=%s', (kind, identifier))
                state = cursor.fetchone()
                counts.append({'type': kind, 'id': identifier, 'sessions': state['sessions'], 'expired': int(state['expired'] or 0)})
        tokens = [reply.get('data', {}).get('token') for reply in replies]
        result = {'name': phase + '八个请求全部保存唯一有效会话', 'passed': all(reply['http'] == 200 and reply.get('data', {}).get('token') for reply in replies) and len(set(tokens)) == 8 and all(state['sessions'] == 2 and state['expired'] == 0 for state in counts), 'http': [reply['http'] for reply in replies], 'databaseState': counts}
        results.append(result)
        print(('PASS ' if result['passed'] else 'FAIL ') + json.dumps(result, ensure_ascii=False))


failure = None
try:
    run()
except Exception as error:
    failure = str(error)
    print('ERROR ' + failure)
REPORT.parent.mkdir(parents=True, exist_ok=True)
REPORT.write_text(json.dumps({'startedAt': started, 'finishedAt': datetime.now(timezone.utc).isoformat(), 'base': BASE, 'database': '127.0.0.1:13319/qicai_acceptance', 'results': results, 'failure': failure, 'passed': not failure and len(results) == 5 and all(item['passed'] for item in results)}, ensure_ascii=False, indent=2), encoding='utf-8')
sys.exit(1 if failure or len(results) != 5 or any(not item['passed'] for item in results) else 0)
