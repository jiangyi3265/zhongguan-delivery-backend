"""SEC-01: actual HTTP/MySQL regression, exclusively in the disposable acceptance DB.

Synthetic users/roles/menu are removed in finally; business writes after revocation
must be rejected and all qc_* table contents must remain unchanged.
"""
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import secrets
import sys
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
BASE = 'http://127.0.0.1:15190'
if (os.environ.get('QICAI_ISOLATED_TEST') != '1' or os.environ.get('QICAI_BASE_URL') != BASE
        or not os.environ.get('QICAI_ADMIN_PASSWORD') or not os.environ.get('QICAI_EVIDENCE_DIR')):
    raise RuntimeError('Run only through the isolated acceptance harness')
OUT = Path(os.environ['QICAI_EVIDENCE_DIR'])
RESULT = OUT / 'authorization-remediation-results.json'
if RESULT.exists():
    raise RuntimeError('Refuse to overwrite evidence')
sys.path.insert(0, str(ROOT / 'tmp/acceptance-20261004/python-deps'))
import pymysql

report = {'startedAt': dt.datetime.now(dt.timezone.utc).isoformat(),
          'kind': 'real isolated HTTP, MySQL and Redis; no production/payment', 'checks': [], 'requests': []}
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
db = None
user_ids = []
dept_ids = []
role_id = menu_id = None
original_role = None


def query(sql, args=None):
    with db.cursor() as cursor:
        cursor.execute(sql, args)
        return cursor.fetchall()


def request(path, method='GET', body=None, token=None):
    assert path.startswith(('/login', '/getInfo', '/system/user', '/system/role/authUser/cancel', '/qicai/admin/products'))
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    req = urllib.request.Request(BASE + path, method=method, headers=headers,
                                 data=json.dumps(body).encode() if body is not None else None)
    try:
        response = opener.open(req, timeout=20)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        status, data = response.status, json.load(response)
    report['requests'].append({'path': path, 'method': method, 'http': status, 'code': data.get('code')})
    return data


def ok(data):
    assert data.get('code') == 200, {'code': data.get('code'), 'message': data.get('msg')}
    return data


def check(name, condition, **details):
    report['checks'].append({'name': name, 'passed': bool(condition), **details})
    print(('PASS ' if condition else 'FAIL ') + name, flush=True)
    assert condition, name


def snapshot():
    result = {}
    for row in query("SELECT table_name AS n FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name LIKE 'qc\\_%'"):
        name = row['n']
        assert name.replace('_', '').isalnum()
        lines = sorted(json.dumps(x, sort_keys=True, default=str, ensure_ascii=False) for x in query('SELECT * FROM `' + name + '`'))
        result[name] = {'count': len(lines), 'sha256': hashlib.sha256('\n'.join(lines).encode()).hexdigest()}
    return result


try:
    db = pymysql.connect(host='127.0.0.1', port=13319, user='root', password='', database='qicai_acceptance',
                         autocommit=True, cursorclass=pymysql.cursors.DictCursor)
    state = query('SELECT @@port AS port, @@datadir AS datadir, DATABASE() AS db')[0]
    runtime = Path(os.environ['QICAI_RUNTIME']).resolve()
    assert state['port'] == 13319 and state['db'] == 'qicai_acceptance' and Path(state['datadir']).resolve().parent == runtime
    report['isolationDatadirVerified'] = True
    admin = ok(request('/login', 'POST', {'username': 'admin', 'password': os.environ['QICAI_ADMIN_PASSWORD']}))['token']
    # Login contract limits usernames to 20 characters; all fixture labels fit.
    suffix = secrets.token_hex(4)
    password = secrets.token_urlsafe(14)

    for label in ['A', 'B']:
        query("INSERT INTO sys_dept(parent_id,ancestors,dept_name,status,del_flag) VALUES(100,'0,100',%s,'0','0')", ('隔离部门' + label + suffix,))
        dept_ids.append(query('SELECT LAST_INSERT_ID() AS id')[0]['id'])
    dept_a, dept_b = dept_ids

    def create_user(label, roles, dept=None):
        dept = dept_a if dept is None else dept
        name = 'sec01' + label + suffix
        ok(request('/system/user', 'POST', {'userName': name, 'nickName': '隔离授权回归', 'password': password,
                   'sex': '0', 'status': '0', 'roleIds': roles, 'postIds': [1], 'deptId': dept}, admin))
        uid = query('SELECT user_id FROM sys_user WHERE user_name=%s', (name,))[0]['user_id']
        user_ids.append(uid)
        return uid, ok(request('/login', 'POST', {'username': name, 'password': password}))['token']

    uid, old = create_user('role', [1])
    ok(request('/qicai/admin/products', token=old))
    ok(request('/system/role/authUser/cancel', 'PUT', {'userId': uid, 'roleId': 1}, admin))
    assert not query('SELECT role_id FROM sys_user_role WHERE user_id=%s', (uid,))
    before = snapshot()
    payload = {'sku': 'SEC01-' + suffix, 'name': '撤权后不得落库', 'category': '隔离测试', 'unit': '件', 'stock': 1,
               'businessPrice': '1.00', 'consumerPrice': '2.00', 'status': 'INACTIVE'}
    denied = request('/qicai/admin/products', 'POST', payload, old)
    check('已撤销角色的旧JWT不能新增商品且所有业务表不变', denied.get('code') == 403 and snapshot() == before,
          code=denied.get('code'), businessTableHashes=before)
    info = ok(request('/getInfo', token=old))
    check('旧JWT身份接口和后续读写都不保留admin角色', 'admin' not in info.get('roles', [])
          and request('/qicai/admin/products', token=old).get('code') == 403)
    query('INSERT INTO sys_user_role(user_id,role_id) VALUES(%s,1)', (uid,))
    check('重新授权后的同JWT从当前数据库恢复权限', request('/qicai/admin/products', token=old).get('code') == 200)
    original_role = query('SELECT status,del_flag FROM sys_role WHERE role_id=1')[0]
    for column, value, label in [('status', '1', '停用'), ('del_flag', '2', '删除')]:
        query('UPDATE sys_role SET status=%s,del_flag=%s WHERE role_id=1', (original_role['status'], original_role['del_flag']))
        query('UPDATE sys_role SET ' + column + '=%s WHERE role_id=1', (value,))
        denied = request('/qicai/admin/products', 'POST', payload, old)
        check(label + '角色立即阻止旧JWT写入并保持业务数据不变', denied.get('code') == 403 and snapshot() == before)
    query('UPDATE sys_role SET status=%s,del_flag=%s WHERE role_id=1', (original_role['status'], original_role['del_flag']))
    query("INSERT INTO sys_role(role_name,role_key,role_sort,data_scope,status,del_flag) VALUES(%s,%s,99,'1','0','0')", ('隔离授权' + suffix, 'sec01' + suffix))
    role_id = query('SELECT LAST_INSERT_ID() AS id')[0]['id']
    query("INSERT INTO sys_menu(menu_name,parent_id,order_num,path,menu_type,visible,status,perms,is_frame,is_cache) VALUES(%s,0,99,'sec01','F','0','0','system:user:list',1,0)", ('隔离菜单' + suffix,))
    menu_id = query('SELECT LAST_INSERT_ID() AS id')[0]['id']
    query('INSERT INTO sys_role_menu(role_id,menu_id) VALUES(%s,%s)', (role_id, menu_id))
    scoped_uid, scoped_token = create_user('scope', [role_id])
    create_user('other', [role_id], dept_b)
    rows = ok(request('/system/user/list?pageNum=1&pageSize=100', token=scoped_token))['rows']
    check('变更前数据权限为全部且可见其他部门', any(row.get('deptId') == dept_b for row in rows))
    query("UPDATE sys_role SET data_scope='3' WHERE role_id=%s", (role_id,))
    rows = ok(request('/system/user/list?pageNum=1&pageSize=100', token=scoped_token))['rows']
    check('缩小数据范围后旧JWT仅可见本部门', bool(rows) and all(row.get('deptId') == dept_a for row in rows),
          returnedDepartments=[row.get('deptId') for row in rows], expectedDepartment=dept_a)
    query('UPDATE sys_user SET dept_id=%s WHERE user_id=%s', (dept_b, scoped_uid))
    rows = ok(request('/system/user/list?pageNum=1&pageSize=100', token=scoped_token))['rows']
    check('部门变化后旧JWT使用新部门数据范围', bool(rows) and all(row.get('deptId') == dept_b for row in rows),
          returnedDepartments=[row.get('deptId') for row in rows], expectedDepartment=dept_b)
    query('DELETE FROM sys_role_menu WHERE role_id=%s', (role_id,))
    denied = request('/system/user/list', token=scoped_token)
    check('撤销菜单权限后旧JWT不再有用户列表权限', denied.get('code') == 403)
    report['exitCode'] = 0
except Exception as error:
    report.update(exitCode=1, errorType=type(error).__name__, error=str(error))
finally:
    if db:
        try:
            if original_role:
                query('UPDATE sys_role SET status=%s,del_flag=%s WHERE role_id=1', (original_role['status'], original_role['del_flag']))
            for uid in user_ids:
                query('DELETE FROM sys_user_role WHERE user_id=%s', (uid,))
                query('DELETE FROM sys_user_post WHERE user_id=%s', (uid,))
                query('DELETE FROM sys_user WHERE user_id=%s', (uid,))
            if role_id:
                query('DELETE FROM sys_role_menu WHERE role_id=%s', (role_id,))
                query('DELETE FROM sys_role WHERE role_id=%s', (role_id,))
            if menu_id:
                query('DELETE FROM sys_menu WHERE menu_id=%s', (menu_id,))
            for dept_id in dept_ids:
                query('DELETE FROM sys_dept WHERE dept_id=%s', (dept_id,))
            report['fixtureCleanup'] = 'complete; business tables were never cleaned or repaired by this test'
        except Exception as error:
            report.update(exitCode=1, cleanupError=str(error))
        db.close()
    report['endedAt'] = dt.datetime.now(dt.timezone.utc).isoformat()
    RESULT.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
raise SystemExit(report['exitCode'])
