"""Real isolated system-profile and low-permission API boundaries, not full CRUD coverage.

Run only through scripts/acceptance.py. Fixtures are ordinary users created by the
isolated administrator. No task execution, code generation or production request.
Evidence intentionally excludes passwords, JWTs and response bodies with secrets.
"""
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import sys
import struct
import time
import urllib.error
import urllib.request
import uuid
import zipfile
import zlib

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tmp/acceptance-20261004/python-deps'))
import pymysql

BASE = 'http://127.0.0.1:15190'
if (os.environ.get('QICAI_ISOLATED_TEST') != '1'
        or os.environ.get('QICAI_BASE_URL') != BASE
        or not os.environ.get('QICAI_EVIDENCE_DIR')
        or not os.environ.get('QICAI_ADMIN_PASSWORD')):
    raise RuntimeError('Run via scripts/acceptance.py: isolated acceptance environment required')
OUT = Path(os.environ['QICAI_EVIDENCE_DIR'])
OUT.mkdir(parents=True, exist_ok=True)
RESULT = OUT / 'framework-boundary-results.json'
if RESULT.exists():
    raise RuntimeError('Evidence exists; choose a fresh directory rather than overwrite results')
started = datetime.now(timezone.utc).isoformat()
stamp = str(int(time.time() * 1000))[-9:]
results, routes, observations = [], [], []
PASSWORD = 'FrameworkQa123!'
NEW_PASSWORD = 'FrameworkQa456!'
def png_chunk(kind, payload):
    return struct.pack('>I', len(payload)) + kind + payload + struct.pack('>I', zlib.crc32(kind + payload) & 0xffffffff)


# Deterministic 1x1 RGB PNG with valid chunk CRCs, not a copied opaque fixture.
PNG = (b'\x89PNG\r\n\x1a\n' + png_chunk(b'IHDR', struct.pack('>IIBBBBB', 1, 1, 8, 2, 0, 0, 0))
       + png_chunk(b'IDAT', zlib.compress(b'\x00\xff\x00\x00')) + png_chunk(b'IEND', b''))


def call(path, method='GET', body=None, token=None, raw=None, content_type=None):
    headers = {'Content-Type': content_type or 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    request = urllib.request.Request(BASE + path, method=method, headers=headers,
                                     data=raw if raw is not None else json.dumps(body).encode() if body is not None else None)
    try:
        response = urllib.request.urlopen(request, timeout=30)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        data = response.read()
        try:
            parsed = json.loads(data)
        except (ValueError, UnicodeDecodeError):
            parsed = {}
        return {'http': response.status, 'json': parsed, 'bytes': data,
                'type': response.headers.get('Content-Type', '')}


def status(response):
    return {'http': response['http'], 'code': response['json'].get('code')}


def ok(response):
    return response['http'] == 200 and response['json'].get('code') == 200


def denied(response, code):
    return response['json'].get('code') == code and response['http'] in (200, code)


def rejected(response):
    return not ok(response) and response['http'] < 500 and response['json'].get('code') not in (200, None)


def check(name, passed, detail):
    results.append({'name': name, 'passed': bool(passed), 'detail': detail})
    print(('PASS ' if passed else 'FAIL ') + name + ' ' + json.dumps(detail, ensure_ascii=False), flush=True)


def login(name, password=PASSWORD):
    return call('/login', 'POST', {'username': name, 'password': password})


def avatar(token, filename, content, mime):
    boundary = 'framework-' + uuid.uuid4().hex
    raw = (f'--{boundary}\r\nContent-Disposition: form-data; name="avatarfile"; filename="{filename}"\r\n'
           f'Content-Type: {mime}\r\n\r\n').encode() + content + f'\r\n--{boundary}--\r\n'.encode()
    return call('/system/user/profile/avatar', 'POST', token=token, raw=raw,
                content_type='multipart/form-data; boundary=' + boundary)


def run():
    with pymysql.connect(host='127.0.0.1', port=13319, user='root', password='', database='qicai_acceptance',
                         autocommit=True, cursorclass=pymysql.cursors.DictCursor) as db:
        def sql(statement, args=()):
            with db.cursor() as cursor:
                cursor.execute(statement, args)
                return cursor.fetchall() if cursor.description else cursor.rowcount
        admin = login('admin', os.environ['QICAI_ADMIN_PASSWORD'])['json'].get('token')
        if not admin:
            raise RuntimeError('Isolated administrator fixture login failed')
        role_key = 'framework_' + stamp
        role_reply = call('/system/role', 'POST', {'roleName': '框架验收' + stamp, 'roleKey': role_key,
                          'roleSort': 99, 'status': '0', 'menuIds': [], 'deptIds': [], 'dataScope': '1',
                          'menuCheckStrictly': True, 'deptCheckStrictly': True}, admin)
        if not ok(role_reply):
            raise RuntimeError('Real ordinary role fixture could not be created: ' + str(status(role_reply)))
        role_id = sql('SELECT role_id FROM sys_role WHERE role_key=%s AND del_flag=\'0\'', (role_key,))[0]['role_id']
        accounts = []
        for suffix in ['a', 'b', 'short']:
            name = 'fwqa' + stamp + suffix
            created = call('/system/user', 'POST', {'userName': name, 'nickName': '框架边界验收' + suffix,
                           'password': PASSWORD, 'sex': '0', 'status': '0', 'roleIds': [role_id], 'postIds': [1],
                           'deptId': 103}, admin)
            if not ok(created):
                raise RuntimeError('Ordinary account fixture could not be created: ' + str(status(created)))
            row = sql('SELECT user_id FROM sys_user WHERE user_name=%s', (name,))[0]
            token = login(name)['json'].get('token')
            if not token:
                raise RuntimeError('Ordinary account fixture login failed')
            accounts.append({'id': row['user_id'], 'name': name, 'token': token})
        owner, victim, short = accounts
        token = owner['token']
        info = call('/getInfo', token=token)
        permissions = info['json'].get('permissions')
        roles = info['json'].get('roles')
        if not ok(info) or permissions != [] or roles != [role_key]:
            raise RuntimeError('Fixture must have a real non-admin role and zero function permissions')
        check('真实普通系统账号可读取本人个人中心且无管理员权限',
              ok(call('/system/user/profile', token=token)) and info['json']['user']['userId'] == owner['id'],
              {'userId': owner['id'], 'roleCount': len(roles), 'permissionCount': len(permissions)})
        victim_before = sql('SELECT user_id,nick_name,email,phonenumber,sex,status,password FROM sys_user WHERE user_id=%s', (victim['id'],))[0]
        forged = {'userId': victim['id'], 'userName': victim['name'], 'nickName': '仅修改本人' + stamp,
                  'email': 'framework' + stamp + '@example.invalid', 'phonenumber': '19' + stamp,
                  'sex': '1', 'status': '1', 'roleIds': [1], 'admin': True}
        updated = call('/system/user/profile', 'PUT', forged, token)
        owner_after = sql('SELECT nick_name,email,phonenumber,sex,status FROM sys_user WHERE user_id=%s', (owner['id'],))[0]
        victim_after = sql('SELECT user_id,nick_name,email,phonenumber,sex,status,password FROM sys_user WHERE user_id=%s', (victim['id'],))[0]
        owner_roles = sql('SELECT role_id FROM sys_user_role WHERE user_id=%s ORDER BY role_id', (owner['id'],))
        reentered = call('/system/user/profile', token=token)
        relogged = login(owner['name'])['json'].get('token')
        fresh_profile = call('/system/user/profile', token=relogged) if relogged else {'json': {}}
        unsafe_nickname = '<script>framework_xss_marker</script>普通昵称'
        cleaned_profile = call('/system/user/profile', 'PUT', {**forged, 'nickName': unsafe_nickname}, token)
        cleaned_name = sql('SELECT nick_name FROM sys_user WHERE user_id=%s', (owner['id'],))[0]['nick_name']
        clean_relogin = call('/system/user/profile', token=login(owner['name'])['json'].get('token'))
        nonpassword_cleaned = (ok(cleaned_profile) and '<' not in cleaned_name and '>' not in cleaned_name
                               and '普通昵称' in cleaned_name
                               and clean_relogin['json'].get('data', {}).get('nickName') == cleaned_name)
        check('个人资料伪造身份只能修改本人允许字段且昵称XSS过滤和重新登录持久化正常',
              ok(updated) and victim_before == victim_after and owner_roles == [{'role_id': role_id}]
              and owner_after == {'nick_name': forged['nickName'], 'email': forged['email'],
                                  'phonenumber': forged['phonenumber'], 'sex': '1', 'status': '0'}
              and reentered['json'].get('data', {}).get('nickName') == forged['nickName']
              and fresh_profile['json'].get('data', {}).get('nickName') == forged['nickName'] and nonpassword_cleaned,
              {'reply': status(updated), 'victimUnchanged': victim_before == victim_after,
               'rolesUnchanged': owner_roles == [{'role_id': role_id}], 'persisted': owner_after['nick_name'] == forged['nickName'],
               'reloginPersisted': fresh_profile['json'].get('data', {}).get('nickName') == forged['nickName'],
               'nonPasswordMarkupRemovedAndReloginPersisted': nonpassword_cleaned})

        invalids = [('empty.png', b'', 'image/png'), ('invalid.txt', b'not-an-image', 'text/plain'),
                    ('forged.png', b'not-an-image', 'image/png')]
        for filename, content, mime in invalids:
            before = sql('SELECT avatar FROM sys_user WHERE user_id=%s', (owner['id'],))[0]['avatar']
            response = avatar(token, filename, content, mime)
            after = sql('SELECT avatar FROM sys_user WHERE user_id=%s', (owner['id'],))[0]['avatar']
            check('无效头像拒绝且数据库不变：' + filename,
                  rejected(response) and before == after,
                  {'reply': status(response), 'avatarUnchanged': before == after})
        uploaded = avatar(token, 'valid.png', PNG, 'image/png')
        avatar_path = uploaded['json'].get('imgUrl')
        current_avatar = sql('SELECT avatar FROM sys_user WHERE user_id=%s', (owner['id'],))[0]['avatar']
        fetched = call(avatar_path) if isinstance(avatar_path, str) and avatar_path.startswith('/profile/avatar/') else None
        avatar_fresh = call('/system/user/profile', token=login(owner['name'])['json'].get('token'))
        check('合法头像实际落库匿名可读且重新登录保持同一图片',
              ok(uploaded) and current_avatar == avatar_path and fetched is not None and fetched['http'] == 200
              and fetched['bytes'] == PNG and avatar_fresh['json'].get('data', {}).get('avatar') == avatar_path,
              {'reply': status(uploaded), 'dbMatches': current_avatar == avatar_path,
               'bytesMatch': fetched is not None and fetched['bytes'] == PNG,
               'reloginPersisted': avatar_fresh['json'].get('data', {}).get('avatar') == avatar_path})

        def password_hash(user_id):
            return sql('SELECT password FROM sys_user WHERE user_id=%s', (user_id,))[0]['password']
        old_hash = password_hash(owner['id'])
        wrong = call('/system/user/profile/updatePwd', 'PUT', {'oldPassword': 'WrongPassword123!', 'newPassword': NEW_PASSWORD}, token)
        same = call('/system/user/profile/updatePwd', 'PUT', {'oldPassword': PASSWORD, 'newPassword': PASSWORD}, token)
        check('修改密码拒绝错误旧密码及相同新密码且数据库不变',
              rejected(wrong) and rejected(same) and password_hash(owner['id']) == old_hash,
              {'wrongOld': status(wrong), 'samePassword': status(same), 'hashUnchanged': password_hash(owner['id']) == old_hash})
        short_before = password_hash(short['id'])
        short_reply = call('/system/user/profile/updatePwd', 'PUT', {'oldPassword': PASSWORD, 'newPassword': 'x'}, short['token'])
        short_after = password_hash(short['id'])
        short_login = login(short['name'], 'x')
        original_login = login(short['name'])
        check('后台API拒绝短密码且保留原密码可登录',
              rejected(short_reply) and short_before == short_after and not short_login['json'].get('token')
              and bool(original_login['json'].get('token')),
              {'update': status(short_reply), 'hashUnchanged': short_before == short_after,
               'shortLoginAccepted': bool(short_login['json'].get('token')),
               'originalLoginAccepted': bool(original_login['json'].get('token'))})

        second_token = login(owner['name'])['json'].get('token')
        both_sessions_valid = bool(second_token) and ok(call('/system/user/profile', token=token)) and ok(call('/system/user/profile', token=second_token))
        changed = call('/system/user/profile/updatePwd', 'PUT', {'oldPassword': PASSWORD, 'newPassword': NEW_PASSWORD}, token)
        current = call('/system/user/profile', token=token)
        stale = call('/system/user/profile', token=second_token)
        old_login, new_login = login(owner['name']), login(owner['name'], NEW_PASSWORD)
        check('合法修改密码持久化且新密码登录成功旧密码登录拒绝',
              ok(changed) and password_hash(owner['id']) != old_hash and ok(current)
              and not old_login['json'].get('token') and bool(new_login['json'].get('token')),
              {'update': status(changed), 'currentSession': status(current),
               'oldLoginAccepted': bool(old_login['json'].get('token')), 'newLoginAccepted': bool(new_login['json'].get('token'))})
        check('修改密码撤销其他已有JWT会话', both_sessions_valid and denied(stale, 401),
              {'bothSessionsValidBeforeChange': both_sessions_valid,
               'otherPreexistingSession': status(stale), 'currentSessionRetained': ok(current)})
        observations.append({'policy': 'Existing profile UI keeps the changing session; other old sessions must be revoked',
                             'oldOtherJwtAccepted': ok(stale)})

        protected = [('/system/user/list', 'GET'), ('/system/role/list', 'GET'), ('/system/menu/list', 'GET'),
                     ('/system/dept/list', 'GET'), ('/system/post/list', 'GET'), ('/system/dict/type/list', 'GET'),
                     ('/system/dict/data/list', 'GET'), ('/system/config/list', 'GET'), ('/system/notice/list', 'GET'),
                     ('/monitor/online/list', 'GET'), ('/monitor/logininfor/list', 'GET'), ('/monitor/operlog/list', 'GET'),
                     ('/monitor/server', 'GET'), ('/monitor/cache', 'GET'), ('/monitor/cache/getNames', 'GET'),
                     ('/monitor/cache/getKeys/login_tokens:', 'GET'), ('/system/user/export', 'POST')]
        for path, method in protected:
            anonymous = call(path, method)
            ordinary = call(path, method, token=victim['token'])
            routes.append({'path': path, 'method': method, 'anonymous': status(anonymous), 'ordinary': status(ordinary),
                           'passed': denied(anonymous, 401) and denied(ordinary, 403)})
        check('保留系统及监控敏感只读导出接口未登录401和普通角色403',
              len(routes) == 17 and all(r['passed'] for r in routes),
              {'testedRoutes': len(routes), 'rejectedForBothIdentities': sum(r['passed'] for r in routes)})
        victim_hash_before = password_hash(victim['id'])
        reset_payload = {'userId': victim['id'], 'password': 'Unauthorized123!'}
        anonymous_reset = call('/system/user/resetPwd', 'PUT', reset_payload)
        ordinary_reset = call('/system/user/resetPwd', 'PUT', reset_payload, token)
        check('未登录及普通角色不能通过系统管理接口重置他人密码',
              denied(anonymous_reset, 401) and denied(ordinary_reset, 403)
              and password_hash(victim['id']) == victim_hash_before,
              {'anonymous': status(anonymous_reset), 'ordinary': status(ordinary_reset),
               'victimPasswordUnchanged': password_hash(victim['id']) == victim_hash_before})
        with zipfile.ZipFile(ROOT / 'RuoYi-Vue/ruoyi-admin/target/ruoyi-admin.jar') as jar:
            optional_absent = not any('ruoyi-quartz' in n or 'ruoyi-generator' in n for n in jar.namelist())
        optional = []
        for path in ['/monitor/job/list', '/monitor/jobLog/list', '/tool/gen/list', '/tool/gen/db/list']:
            anonymous, ordinary, administrator = call(path), call(path, token=victim['token']), call(path, token=admin)
            optional.append({'path': path, 'anonymous': status(anonymous), 'ordinary': status(ordinary),
                             'administrator': status(administrator)})
        check('构建未包含定时任务和代码生成模块且列表路由实际不可用',
              optional_absent and all(r['anonymous']['code'] == 401 and r['ordinary']['http'] == 404
                                      and r['administrator']['http'] == 404 for r in optional),
              {'modulesAbsentFromJar': optional_absent, 'routes': optional,
               'scope': 'Unavailable modules are not counted as permission-denial coverage; no job/gen action was invoked'})

        admin_password_cases = [('empty', ''), ('one-character', 'x'), ('over-twenty', 'A' * 21),
                                ('forbidden-character', 'Valid<123!')]
        create_cases, reset_cases = [], []
        for index, (label, value) in enumerate(admin_password_cases):
            invalid_name = 'bad' + stamp + str(index)
            response = call('/system/user', 'POST', {'userName': invalid_name, 'nickName': '拒绝创建边界',
                            'password': value, 'sex': '0', 'status': '0', 'roleIds': [role_id], 'postIds': [1],
                            'deptId': 103}, admin)
            created_rows = sql('SELECT user_id FROM sys_user WHERE user_name=%s AND del_flag=\'0\'', (invalid_name,))
            create_cases.append({'case': label, 'reply': status(response), 'createdRows': len(created_rows),
                                 'passed': rejected(response) and len(created_rows) == 0})
            for invalid_row in created_rows:
                cleanup = call('/system/user/' + str(invalid_row['user_id']), 'DELETE', token=admin)
                if not ok(cleanup):
                    raise RuntimeError('Cannot clean an unexpectedly accepted synthetic invalid-password account')
            before = password_hash(victim['id'])
            response = call('/system/user/resetPwd', 'PUT', {'userId': victim['id'], 'password': value}, admin)
            unchanged = password_hash(victim['id']) == before
            reset_cases.append({'case': label, 'reply': status(response), 'hashUnchanged': unchanged,
                                'passed': rejected(response) and unchanged})
            if not unchanged:
                restore = call('/system/user/resetPwd', 'PUT', {'userId': victim['id'], 'password': PASSWORD}, admin)
                if not ok(restore):
                    raise RuntimeError('Cannot restore administrator password boundary fixture')
        check('管理员新增账号拒绝不合规密码且无新增账号', all(c['passed'] for c in create_cases), {'cases': create_cases})
        check('管理员重置密码拒绝不合规密码且不改数据库', all(c['passed'] for c in reset_cases), {'cases': reset_cases})

        # Each boundary uses a live account with the documented UI password
        # contract (6-20 chars, no < > double/single quote, backslash or pipe).
        # Restore an incorrectly changed synthetic fixture only after recording
        # the failure, so later independent checks still run on usable accounts.
        invalid_passwords = [('null', None), ('empty', ''), ('five-characters', '12345'),
                             ('over-twenty', 'A' * 21), ('forbidden-character', 'Valid<123!'),
                             ('nested-object', {'password': 'Nested<123!'}), ('array-value', ['Nested<123!'])]
        password_cases = []
        for label, value in invalid_passwords:
            live_token = login(victim['name'])['json'].get('token')
            if not live_token:
                raise RuntimeError('Independent password boundary fixture cannot login')
            before = password_hash(victim['id'])
            response = call('/system/user/profile/updatePwd', 'PUT', {'oldPassword': PASSWORD, 'newPassword': value}, live_token)
            unchanged = before == password_hash(victim['id'])
            password_cases.append({'case': label, 'reply': status(response), 'hashUnchanged': unchanged,
                                   'passed': rejected(response) and unchanged})
            if not unchanged:
                restore = call('/system/user/resetPwd', 'PUT', {'userId': victim['id'], 'password': PASSWORD}, admin)
                if not ok(restore):
                    raise RuntimeError('Cannot restore invalid-password synthetic fixture')
        live_token = login(victim['name'])['json'].get('token')
        if not live_token:
            raise RuntimeError('Malformed-password boundary fixture cannot login')
        before = password_hash(victim['id'])
        malformed_body = json.dumps({'oldPassword': PASSWORD, 'newPassword': 'Valid<123!'}).encode() + b'<script></script>'
        malformed = call('/system/user/profile/updatePwd', 'PUT', token=live_token, raw=malformed_body)
        unchanged = before == password_hash(victim['id'])
        password_cases.append({'case': 'malformed-json-trailing-markup', 'reply': status(malformed),
                               'hashUnchanged': unchanged, 'passed': rejected(malformed) and unchanged})
        if not unchanged:
            restore = call('/system/user/resetPwd', 'PUT', {'userId': victim['id'], 'password': PASSWORD}, admin)
            if not ok(restore):
                raise RuntimeError('Cannot restore malformed-password synthetic fixture')
        check('API密码边界遵循页面规则且无效输入均不改数据库', all(c['passed'] for c in password_cases),
              {'cases': password_cases})
        victim_tokens = [login(victim['name'])['json'].get('token'), login(victim['name'])['json'].get('token')]
        if not all(victim_tokens):
            raise RuntimeError('Administrative reset session fixture did not login')
        reset = call('/system/user/resetPwd', 'PUT', {'userId': victim['id'], 'password': NEW_PASSWORD}, admin)
        reset_stale = [call('/system/user/profile', token=t) for t in victim_tokens]
        reset_login = login(victim['name'], NEW_PASSWORD)
        check('管理员重置密码后原有JWT全失效且新密码可登录',
              ok(reset) and all(denied(r, 401) for r in reset_stale) and bool(reset_login['json'].get('token')),
              {'reset': status(reset), 'oldSessions': [status(r) for r in reset_stale],
               'newLoginAccepted': bool(reset_login['json'].get('token'))})
        victim_token = reset_login['json'].get('token')
        disabled = call('/system/user/changeStatus', 'PUT', {'userId': victim['id'], 'status': '1'}, admin)
        disabled_session = call('/system/user/profile', token=victim_token)
        disabled_login = login(victim['name'], NEW_PASSWORD)
        disabled_db = sql('SELECT status FROM sys_user WHERE user_id=%s', (victim['id'],))[0]['status']
        check('停用普通后台账号立即拒绝已有JWT和新登录',
              ok(disabled) and disabled_db == '1' and denied(disabled_session, 401) and not disabled_login['json'].get('token'),
              {'disable': status(disabled), 'persistedStatus': disabled_db,
               'oldSession': status(disabled_session), 'freshLoginAccepted': bool(disabled_login['json'].get('token'))})
        enabled = call('/system/user/changeStatus', 'PUT', {'userId': victim['id'], 'status': '0'}, admin)
        enabled_token = login(victim['name'], NEW_PASSWORD)['json'].get('token')
        if not ok(enabled) or not enabled_token:
            raise RuntimeError('Synthetic deleted-account fixture cannot be enabled/login')
        deleted = call('/system/user/' + str(victim['id']), 'DELETE', token=admin)
        deleted_session = call('/system/user/profile', token=enabled_token)
        deleted_login = login(victim['name'], NEW_PASSWORD)
        deleted_db = sql('SELECT del_flag FROM sys_user WHERE user_id=%s', (victim['id'],))[0]['del_flag']
        check('删除普通后台账号立即拒绝已有JWT和新登录',
              ok(deleted) and deleted_db == '2' and denied(deleted_session, 401) and not deleted_login['json'].get('token'),
              {'delete': status(deleted), 'persistedDeleted': deleted_db == '2',
               'oldSession': status(deleted_session), 'freshLoginAccepted': bool(deleted_login['json'].get('token'))})
        edge_name = 'edge' + stamp
        lower_create = call('/system/user', 'POST', {'userName': edge_name, 'nickName': '合法密码下界',
                            'password': 'Valid', 'sex': '0', 'status': '0', 'roleIds': [role_id],
                            'postIds': [1], 'deptId': 103}, admin)
        lower_login = login(edge_name, 'Valid')
        edge_rows = sql('SELECT user_id FROM sys_user WHERE user_name=%s AND del_flag=\'0\'', (edge_name,))
        if not ok(lower_create) or len(edge_rows) != 1:
            raise RuntimeError('Administrator five-character password contract rejected')
        lower_reset = call('/system/user/resetPwd', 'PUT', {'userId': edge_rows[0]['user_id'], 'password': 'Other'}, admin)
        lower_reset_login = login(edge_name, 'Other')
        personal_six = call('/system/user/profile/updatePwd', 'PUT', {'oldPassword': NEW_PASSWORD, 'newPassword': 'Valid6'}, token)
        personal_six_login = login(owner['name'], 'Valid6')
        personal_twenty = call('/system/user/profile/updatePwd', 'PUT', {'oldPassword': 'Valid6', 'newPassword': 'A' * 20}, token)
        personal_twenty_login = login(owner['name'], 'A' * 20)
        personal_ampersand = call('/system/user/profile/updatePwd', 'PUT', {'oldPassword': 'A' * 20, 'newPassword': 'Safe&123!'}, token)
        personal_ampersand_login = login(owner['name'], 'Safe&123!')
        check('保留管理员5字符及个人6到20字符和合法特殊字符密码并实际重新登录',
              ok(lower_create) and bool(lower_login['json'].get('token')) and ok(lower_reset)
              and bool(lower_reset_login['json'].get('token')) and ok(personal_six)
              and bool(personal_six_login['json'].get('token')) and ok(personal_twenty)
              and bool(personal_twenty_login['json'].get('token')) and ok(personal_ampersand)
              and bool(personal_ampersand_login['json'].get('token')),
              {'adminCreate': status(lower_create), 'adminFiveLogin': bool(lower_login['json'].get('token')),
               'adminReset': status(lower_reset), 'adminResetFiveLogin': bool(lower_reset_login['json'].get('token')),
               'personalSix': status(personal_six), 'personalSixLogin': bool(personal_six_login['json'].get('token')),
               'personalTwenty': status(personal_twenty), 'personalTwentyLogin': bool(personal_twenty_login['json'].get('token')),
               'personalLegalSpecialCharacter': status(personal_ampersand),
               'personalLegalSpecialCharacterLogin': bool(personal_ampersand_login['json'].get('token'))})


failure = None
try:
    run()
except Exception as error:
    failure = str(error)
    print('ERROR ' + failure, flush=True)
passed = failure is None and len(results) == 20 and all(r['passed'] for r in results)
RESULT.write_text(json.dumps({'startedAt': started, 'finishedAt': datetime.now(timezone.utc).isoformat(),
                             'kind': 'real isolated API/MySQL, ordinary role with zero function permissions',
                             'scopeLimit': 'Profile and sampled permission boundaries only; not full framework CRUD',
                             'results': results, 'routes': routes, 'observations': observations,
                             'failure': failure, 'passed': passed}, ensure_ascii=False, indent=2), encoding='utf-8')
sys.exit(0 if passed else 1)
