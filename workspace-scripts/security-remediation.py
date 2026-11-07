"""SEC02/03 real isolated HTTP, file, MySQL and Redis regression. No payment calls."""
import concurrent.futures, datetime as dt, hashlib, json, os, secrets, socket, struct, sys, threading, urllib.error, urllib.parse, urllib.request, zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BASE = 'http://127.0.0.1:15190'
if os.environ.get('QICAI_ISOLATED_TEST') != '1' or os.environ.get('QICAI_BASE_URL') != BASE or not os.environ.get('QICAI_ADMIN_PASSWORD'):
    raise RuntimeError('Only disposable acceptance environments are supported')
OUT = Path(os.environ['QICAI_EVIDENCE_DIR']); RUNTIME = Path(os.environ['QICAI_RUNTIME']).resolve()
DEST = OUT / 'security-remediation-results.json'
if DEST.exists(): raise RuntimeError('Refuse to overwrite evidence')
sys.path.insert(0, str(ROOT / 'tmp/acceptance-20261004/python-deps'))
import pymysql

now = lambda: dt.datetime.now(dt.timezone.utc).isoformat()
report = {'startedAt': now(), 'kind': 'real isolated HTTP/MySQL/Redis and files; no browser or real payment', 'checks': [], 'requests': []}
REDIS_DATABASE = int(os.environ.get('QICAI_REDIS_DATABASE', '15'))
if not 0 <= REDIS_DATABASE <= 15: raise RuntimeError('Unexpected isolated Redis DB index')
report['redisDatabase'] = REDIS_DATABASE
db = None; users = []; lock = threading.Lock()
def query(sql, args=None):
    with db.cursor() as cursor:
        cursor.execute(sql, args); return cursor.fetchall()

def request(path, method='GET', body=None, token=None, raw=None, content_type=None):
    assert path.startswith(('/login', '/system/user', '/monitor/logininfor/unlock/', '/monitor/cache/getNames', '/monitor/cache/getKeys/', '/common/upload', '/profile/'))
    headers = {'Content-Type': content_type or 'application/json', 'Referer': 'http://localhost:25186/'}
    if token: headers['Authorization'] = 'Bearer ' + token
    data = raw if raw is not None else json.dumps(body).encode() if body is not None else None
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try: response = opener.open(urllib.request.Request(BASE + path, data=data, headers=headers, method=method), timeout=30)
    except urllib.error.HTTPError as error: response = error
    with response: status, headers, value = response.status, dict(response.headers), response.read()
    parsed = json.loads(value) if 'application/json' in headers.get('Content-Type', '') else None
    with lock: report['requests'].append({'path': path, 'method': method, 'http': status, 'code': parsed.get('code') if parsed else None})
    return status, headers, parsed, value

def ok(response):
    assert response[2] and response[2].get('code') == 200, {'http': response[0], 'code': response[2].get('code') if response[2] else None}
    return response[2]

def check(name, condition, **details):
    report['checks'].append({'name': name, 'passed': bool(condition), **details})
    print(('PASS ' if condition else 'FAIL ') + name, flush=True)
    assert condition, name

def redis(*args):
    # Minimal RESP client; only explicit synthetic keys, never FLUSH or production.
    with socket.create_connection(('127.0.0.1', 16399), timeout=5) as client:
        reader = client.makefile('rb')
        def command(*values):
            encoded = [str(arg).encode() for arg in values]
            client.sendall(('*%d\r\n' % len(encoded)).encode() + b''.join(('$%d\r\n' % len(arg)).encode() + arg + b'\r\n' for arg in encoded))
            line = reader.readline(); kind, value = line[:1], line[1:-2]
            if kind == b'-': raise RuntimeError('Isolated Redis command failed')
            if kind == b':': return int(value)
            if kind == b'+': return value.decode()
            if kind == b'$':
                size = int(value)
                if size < 0: return None
                payload = reader.read(size); assert reader.read(2) == b'\r\n'; return payload.decode()
            raise RuntimeError('Unexpected Redis response')
        assert command('SELECT', REDIS_DATABASE) == 'OK'
        return command(*args)

def snapshot():
    result = {}
    for row in query("SELECT table_name AS n FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name LIKE 'qc\\_%'"):
        table = row['n']; assert table.replace('_', '').isalnum()
        data = sorted(json.dumps(item, sort_keys=True, default=str, ensure_ascii=False) for item in query('SELECT * FROM `' + table + '`'))
        result[table] = {'count': len(data), 'sha256': hashlib.sha256('\n'.join(data).encode()).hexdigest()}
    return result

def png():
    chunk = lambda name, data: struct.pack('!I', len(data)) + name + data + struct.pack('!I', zlib.crc32(name + data) & 0xffffffff)
    return b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('!2I5B', 1, 1, 8, 2, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(b'\x00\xff\x00\x00')) + chunk(b'IEND', b'')

def upload(name, mime, data, token, multi=False):
    boundary = 'SecBoundary' + secrets.token_hex(8)
    field = 'files' if multi else 'file'
    body = (f'--{boundary}\r\nContent-Disposition: form-data; name="{field}"; filename="{name}"\r\nContent-Type: {mime}\r\n\r\n').encode() + data + f'\r\n--{boundary}--\r\n'.encode()
    return request('/common/uploads' if multi else '/common/upload', 'POST', token=token, raw=body, content_type='multipart/form-data; boundary=' + boundary)

try:
    db = pymysql.connect(host='127.0.0.1', port=13319, user='root', password='', database='qicai_acceptance', autocommit=True, cursorclass=pymysql.cursors.DictCursor)
    state = query('SELECT @@port AS port, @@datadir AS datadir, DATABASE() AS db')[0]
    assert state['port'] == 13319 and state['db'] == 'qicai_acceptance' and Path(state['datadir']).resolve().parent == RUNTIME
    report['isolationDatadirVerified'] = True
    assert redis('PING') == 'PONG'
    before = snapshot()
    admin = ok(request('/login', 'POST', {'username': 'admin', 'password': os.environ['QICAI_ADMIN_PASSWORD']}))['token']
    dept = query("SELECT dept_id FROM sys_dept WHERE del_flag='0' AND status='0' ORDER BY dept_id LIMIT 1")[0]['dept_id']
    password = secrets.token_hex(8) + 'A!'
    def user(label, explicit_name=None):
        name = explicit_name or 'Sec' + label + secrets.token_hex(4)
        ok(request('/system/user', 'POST', {'userName': name, 'nickName': '安全隔离回归', 'password': password, 'status': '0', 'sex': '0', 'roleIds': [2], 'postIds': [1], 'deptId': dept}, admin))
        uid = query('SELECT user_id FROM sys_user WHERE user_name=%s', (name,))[0]['user_id']
        users.append((uid, name)); return uid, name
    uploader_id, uploader = user('Upload')
    upload_token = ok(request('/login', 'POST', {'username': uploader, 'password': password}))['token']
    html = b'<html><script>document.cookie="SecUnexpected=1";document.title="EXECUTED";</script></html>'
    file_root = RUNTIME / 'uploads'
    paths_before = {str(path.relative_to(file_root)) for path in file_root.rglob('*') if path.is_file()}
    bad = [('active.html', 'text/html', html), ('active.HTM', 'image/png', html), ('active.svg', 'image/svg+xml', b'<svg onload="x()"/>'), ('fake.png', 'image/png', html), ('broken.png', 'image/png', b'\x89PNG\r\n\x1a\n'), ('wrong.jpg', 'image/jpeg', png())]
    for name, mime, body in bad:
        value = upload(name, mime, body, upload_token)
        check('单文件拒绝 ' + name, value[2] is not None and value[2].get('code') != 200)
    check('多文件入口拒绝HTML', upload('active.html', 'text/html', html, upload_token, True)[2].get('code') != 200)
    check('拒绝的文件没有落盘', paths_before == {str(path.relative_to(file_root)) for path in file_root.rglob('*') if path.is_file()})
    image = png(); image_path = ok(upload('valid.png', 'image/png', image, upload_token))['fileName']
    image_response = request(image_path)
    check('真实PNG仍可匿名显示', image_response[0] == 200 and image_response[3] == image and image_response[1].get('Content-Type', '').startswith('image/png') and 'Content-Disposition' not in image_response[1])
    pdf_path = ok(upload('document.pdf', 'application/pdf', b'%PDF-1.4 synthetic', upload_token))['fileName']
    check('PDF文档仍可下载', request(pdf_path)[1].get('Content-Disposition') == 'attachment')
    legacy_dir = file_root / ('security-legacy-' + secrets.token_hex(4)); legacy_dir.mkdir()
    for filename, content in [('old.html', html), ('old.svg', b'<svg onload="x()"/>')]:
        (legacy_dir / filename).write_bytes(content)
        response = request('/profile/' + legacy_dir.name + '/' + filename)
        check('旧文件安全响应 ' + filename, response[0] == 200 and response[1].get('Content-Disposition') == 'attachment' and response[1].get('Content-Security-Policy') == "sandbox; default-src 'none'" and response[1].get('X-Content-Type-Options') == 'nosniff')
    uid, name = user('Lock'); key = 'pwd_account_err_cnt:' + str(uid)
    assert query('SELECT user_id FROM sys_user WHERE user_name=%s', (name.upper(),))[0]['user_id'] == uid
    gate = threading.Barrier(12)
    def failure(index):
        gate.wait(timeout=10)
        return request('/login', 'POST', {'username': name.upper() if index % 2 else name.lower(), 'password': 'WrongPass2026!'})[2]
    with concurrent.futures.ThreadPoolExecutor(max_workers=12) as executor: values = list(executor.map(failure, range(12)))
    count, ttl = redis('GET', key), redis('TTL', key)
    check('12并发错误别名共享5次上限和TTL', all(value.get('code') != 200 and 'token' not in value for value in values) and count == '5' and 0 < ttl <= 600, counter=count, ttlSeconds=ttl)
    names = ok(request('/monitor/cache/getNames', token=admin))['data']
    keys = ok(request('/monitor/cache/getKeys/pwd_account_err_cnt:', token=admin))['data']
    check('后台缓存列表能查询当前账号锁定计数', any(row.get('cacheName') == 'pwd_account_err_cnt:' for row in names) and key in keys)
    for alias in (name, name.upper(), name.lower()):
        denied = request('/login', 'POST', {'username': alias, 'password': password})[2]
        check('锁定后正确密码别名仍拒绝 ' + ('original' if alias == name else 'upper' if alias == name.upper() else 'lower'), denied.get('code') != 200 and 'token' not in denied)
    ok(request('/monitor/logininfor/unlock/' + urllib.parse.quote(name.upper()), token=admin))
    check('管理员大小写别名解锁实际清除稳定key', redis('GET', key) is None)
    check('解锁后正确密码可登录', request('/login', 'POST', {'username': name.upper(), 'password': password})[2].get('code') == 200)
    assert redis('SET', key, 3, 'EX', 600) == 'OK'
    collision_id, collision_name = user('Collision', 'account:' + str(uid))
    collision_login = request('/login', 'POST', {'username': collision_name, 'password': password})[2]
    check('特殊合法用户名登录不删除另一账号计数', collision_login.get('code') == 200 and redis('GET', key) == '3')
    legacy_id, legacy_name = user('Old'); legacy_key = 'pwd_err_cnt:' + legacy_name
    assert redis('SET', legacy_key, 5, 'EX', 600) == 'OK'
    denied = request('/login', 'POST', {'username': legacy_name.upper(), 'password': password})[2]
    check('升级前规范用户名锁保留并迁入稳定ID', denied.get('code') != 200 and 'token' not in denied and redis('GET', 'pwd_account_err_cnt:' + str(legacy_id)) == '5')
    wrong = request('/login', 'POST', {'username': uploader, 'password': 'WrongPass2026!'})[2]
    unknown = request('/login', 'POST', {'username': 'Unknown' + secrets.token_hex(4), 'password': 'WrongPass2026!'})[2]
    check('未知用户名不增加存在性错误消息', unknown.get('code') == wrong.get('code') and unknown.get('msg') == wrong.get('msg') and 'token' not in unknown)
    check('安全回归未改任何qc业务表', snapshot() == before, businessTables=before)
    fixture = {'baseUrl': BASE, 'legacyHtmlPath': '/profile/' + legacy_dir.name + '/old.html', 'pngPath': image_path, 'htmlSha256': hashlib.sha256(html).hexdigest(), 'pngSha256': hashlib.sha256(image).hexdigest()}
    (OUT / 'security-browser-fixture.json').write_text(json.dumps(fixture, indent=2), encoding='utf-8')
    report['exitCode'] = 0
except Exception as error:
    report.update(exitCode=1, errorType=type(error).__name__, error=str(error))
finally:
    if db:
        try:
            for uid, name in users:
                query('DELETE FROM sys_user_role WHERE user_id=%s', (uid,)); query('DELETE FROM sys_user_post WHERE user_id=%s', (uid,)); query('DELETE FROM sys_user WHERE user_id=%s', (uid,))
                for key in ('pwd_account_err_cnt:' + str(uid), 'pwd_err_cnt:' + name, 'pwd_err_cnt:' + name.upper(), 'pwd_err_cnt:' + name.lower()): redis('DEL', key)
            report['fixtureCleanup'] = 'synthetic users and exact lock keys removed; safe files retained only in disposable runtime for browser verification'
        except Exception as error: report.update(exitCode=1, cleanupError=str(error))
        db.close()
    report['endedAt'] = now(); DEST.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
raise SystemExit(report['exitCode'])
