"""Independent deterministic inspection of courier password-reset/login race.
Uses only the explicitly isolated acceptance endpoint and disposable DB.
The verdict checks token usability after the password reset has committed.
"""
import concurrent.futures
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import sys
import time
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tmp/acceptance-20261004/python-deps'))
import pymysql

BASE = 'http://127.0.0.1:15190'
if os.environ.get('QICAI_ISOLATED_TEST') != '1' or os.environ.get('QICAI_BASE_URL') != BASE or not os.environ.get('QICAI_ADMIN_PASSWORD'):
    raise RuntimeError('Explicit disposable acceptance environment required')
OUT = Path(os.environ['QICAI_EVIDENCE_DIR']) / 'login-reset-race-review.json'
evidence = {'startedAt': datetime.now(timezone.utc).isoformat(), 'database': '127.0.0.1:13319/qicai_acceptance', 'base': BASE}


def req(path, method='GET', body=None, token=None, admin=False):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization' if admin else 'X-Qicai-Token'] = 'Bearer ' + token if admin else token
    request = urllib.request.Request(BASE + path, data=json.dumps(body).encode() if body is not None else None, headers=headers, method=method)
    try:
        response = urllib.request.urlopen(request, timeout=30)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        return {'http': response.status, **json.load(response)}


def db():
    return pymysql.connect(host='127.0.0.1', port=13319, user='root', password='', database='qicai_acceptance', autocommit=True, cursorclass=pymysql.cursors.DictCursor)


def run():
    admin_token = req('/login', 'POST', {'username': 'admin', 'password': os.environ['QICAI_ADMIN_PASSWORD']})['token']
    def admin(path, method='GET', body=None):
        return req('/qicai/admin' + path, method, body, admin_token, True)
    old_password = 'OldQaTest123!'
    new_password = 'NewQaTest456!'
    phone = '181' + str(time.time_ns())[-8:]
    courier = admin('/couriers', 'POST', {'phone': phone, 'password': old_password, 'name': '独立密码重置竞争验收', 'status': 'ACTIVE', 'available': True})['data']
    evidence['courierId'] = courier['id']
    expired_hash = hashlib.sha256(uuid.uuid4().bytes).hexdigest()
    with db() as blocker, db() as observer, concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        with observer.cursor() as cursor:
            cursor.execute("INSERT INTO qc_session(token_hash,principal_type,principal_id,audience,expires_at) VALUES(%s,'courier',%s,'courier',DATE_SUB(NOW(),INTERVAL 1 MINUTE))", (expired_hash, courier['id']))
        blocker.begin()
        with blocker.cursor() as cursor:
            cursor.execute('SELECT token_hash FROM qc_session WHERE token_hash=%s FOR UPDATE', (expired_hash,))

        def wait_for_waiters(expected, pending):
            deadline = time.monotonic() + 12
            while time.monotonic() < deadline:
                with observer.cursor() as cursor:
                    cursor.execute("SELECT DISTINCT w.REQUESTING_THREAD_ID AS requester,l.OBJECT_NAME AS tableName,l.INDEX_NAME AS indexName FROM performance_schema.data_lock_waits w JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID AND l.ENGINE=w.ENGINE WHERE l.OBJECT_SCHEMA='qicai_acceptance' AND ((l.OBJECT_NAME='qc_session' AND l.LOCK_DATA LIKE %s) OR (l.OBJECT_NAME='qc_courier' AND l.LOCK_DATA=%s))", ('%' + expired_hash + '%', str(courier['id'])))
                    rows = cursor.fetchall()
                if len({row['requester'] for row in rows}) >= expected:
                    evidence['waiters' + str(expected)] = rows
                    return
                if pending.done():
                    evidence['earlyResponse' + str(expected)] = {'http': pending.result()['http'], 'message': pending.result().get('message', pending.result().get('msg'))}
                    return
                time.sleep(0.04)
            raise RuntimeError('Expected lock waiters not observed: ' + str(expected))

        try:
            resetting = pool.submit(admin, '/couriers/' + str(courier['id']), 'PUT', {**courier, 'available': True, 'password': new_password})
            wait_for_waiters(1, resetting)
            logging_in = pool.submit(req, '/qicai/mobile/courier/login', 'POST', {'phone': phone, 'password': old_password})
            wait_for_waiters(2, logging_in)
        finally:
            blocker.rollback()
        reset_result = resetting.result()
        login_result = logging_in.result()
        token = login_result.get('data', {}).get('token')
        after = req('/qicai/mobile/courier/tasks', token=token) if token else None
        fresh_old = req('/qicai/mobile/courier/login', 'POST', {'phone': phone, 'password': old_password})
        fresh_new = req('/qicai/mobile/courier/login', 'POST', {'phone': phone, 'password': new_password})
        with observer.cursor() as cursor:
            cursor.execute("SELECT COUNT(*) AS sessions,SUM(expires_at<NOW()) AS expired FROM qc_session WHERE principal_type='courier' AND principal_id=%s", (courier['id'],))
            counts = cursor.fetchone()
        evidence.update({'passwordResetHttp': reset_result['http'], 'concurrentOldLoginHttp': login_result['http'], 'concurrentTokenTasksHttp': after['http'] if after else None, 'freshOldLoginHttp': fresh_old['http'], 'freshNewLoginHttp': fresh_new['http'], 'database': {'sessions': counts['sessions'], 'expired': int(counts['expired'] or 0)}})
        evidence['riskReproduced'] = reset_result['http'] == 200 and login_result['http'] == 200 and after['http'] == 200
        evidence['passed'] = reset_result['http'] == 200 and (login_result['http'] == 401 or after is not None and after['http'] == 401) and fresh_old['http'] == 401 and fresh_new['http'] == 200


try:
    run()
except Exception as error:
    evidence['failure'] = str(error)
    evidence['passed'] = False
evidence['finishedAt'] = datetime.now(timezone.utc).isoformat()
OUT.parent.mkdir(parents=True, exist_ok=True)
OUT.write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(evidence, ensure_ascii=False, indent=2))
sys.exit(0 if evidence.get('passed') else 1)
