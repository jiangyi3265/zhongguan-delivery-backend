#!/usr/bin/env python3
"""Isolated, reserved deployment. No server login secret is stored in this script."""
import os, sys, json, secrets, pathlib, subprocess, shutil, time, zipfile, pwd, grp, configparser

BASE = pathlib.Path('/www/qicai')
ETC = pathlib.Path('/etc/qicai')
RELEASE = pathlib.Path(__file__).resolve().parent.parent
JAVA = '/www/server/java/jdk-17.0.8/bin/java'
JAVAC = '/www/server/java/jdk-17.0.8/bin/javac'
MYSQL = '/www/server/mysql/bin/mysql'
MYSQLD = '/www/server/mysql/bin/mysqld'
REDIS = '/www/server/redis/src/redis-server'
VHOST = pathlib.Path('/www/server/panel/vhost/nginx')
RESUME = '--resume' in sys.argv

def run(args, **kw):
    return subprocess.run(args, check=True, **kw)

def write(path, content, mode=0o644):
    path = pathlib.Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding='utf-8')
    path.chmod(mode)

def unit(name, text):
    write('/etc/systemd/system/' + name + '.service', text)

if os.geteuid() != 0:
    sys.exit('Run as root')
if (ETC / 'installed.json').exists():
    sys.exit('Installation already exists; refusing to overwrite database/config. Use controlled release update.')
for binary in [JAVA, JAVAC, MYSQL, MYSQLD, REDIS]:
    if not pathlib.Path(binary).is_file():
        sys.exit('Missing runtime: ' + binary)
for port in ([] if RESUME else ['13310', '16390', '18130', '18131', '18132']):
    rows = subprocess.check_output(['ss', '-lntH'], text=True)
    if any(line.split()[3].endswith(':' + port) for line in rows.splitlines()):
        sys.exit('Reserved port already in use: ' + port)

try:
    pwd.getpwnam('qicai')
except KeyError:
    run(['useradd', '--system', '--home-dir', str(BASE), '--shell', '/sbin/nologin', 'qicai'])
ETC.mkdir(mode=0o755, exist_ok=True)
ETC.chmod(0o755)
BASE.mkdir(mode=0o755, exist_ok=True)
for name in ['uploads', 'logs', 'redis']:
    target = BASE / 'shared' / name
    target.mkdir(parents=True, exist_ok=True)
    shutil.chown(target, user='qicai', group='qicai')
data = BASE / 'shared' / 'mysql'
data.mkdir(parents=True, mode=0o700, exist_ok=RESUME)
shutil.chown(data, user='mysql', group='mysql')

root_password = secrets.token_hex(24)
db_password = secrets.token_hex(24)
redis_password = secrets.token_hex(24)
admin_password = 'Qc!' + secrets.token_urlsafe(12)  # RuoYi accepts at most 20 characters.
env = {'QICAI_DB_USERNAME':'qicai_app', 'QICAI_DB_PASSWORD':db_password,
       'QICAI_DB_URL':'jdbc:mysql://127.0.0.1:13310/qicai_delivery?useUnicode=true&characterEncoding=utf8&zeroDateTimeBehavior=convertToNull&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai',
       'QICAI_TOKEN_SECRET':secrets.token_hex(48), 'QICAI_REDIS_PASSWORD':redis_password,
       'QICAI_SMS_DEV_MODE':'true', 'QICAI_LOG_PATH':str(BASE/'shared/logs')}
if RESUME:
    # Resume only this project's interrupted installation; do not reinitialize or drop tables.
    env = dict(line.split('=',1) for line in (ETC/'app.env').read_text().splitlines() if '=' in line)
    config = configparser.ConfigParser(interpolation=None)
    config.read(ETC/'mysql-root.cnf')
    root_password = config['client']['password']
    db_password = env['QICAI_DB_PASSWORD']
    redis_password = env['QICAI_REDIS_PASSWORD']
    admin_password = next(line.split(': ',1)[1] for line in (ETC/'credentials.txt').read_text().splitlines() if line.startswith('Admin password: '))
write(ETC / 'app.env', ''.join(k+'='+v+'\n' for k,v in env.items()), 0o600)
write(ETC / 'mysql-root.cnf', '[client]\nuser=root\npassword='+root_password+'\nsocket='+str(BASE / 'shared/mysql/mysql.sock')+'\n', 0o600)
write(ETC / 'credentials.txt', 'Admin account: admin\nAdmin password: '+admin_password+'\nSSH tunnel admin: http://127.0.0.1:15184\nSSH tunnel H5: http://127.0.0.1:15185\nPublic sites remain a waiting page until filing/HTTPS.\n', 0o600)
write(ETC / 'acceptance.env', 'QICAI_ADMIN_USER=admin\nQICAI_ADMIN_PASSWORD='+admin_password+'\n', 0o600)
write(ETC / 'mysql.cnf', '[mysqld]\nbasedir=/www/server/mysql\ndatadir='+str(data)+'\nport=13310\nbind-address=127.0.0.1\nsocket='+str(data/'mysql.sock')+'\npid-file='+str(data/'mysql.pid')+'\nlog-error='+str(data/'mysql.err')+'\nmysqlx=0\ncharacter-set-server=utf8mb4\ncollation-server=utf8mb4_0900_ai_ci\ninnodb_buffer_pool_size=256M\nmax_connections=80\nskip-name-resolve\n', 0o644)
if not RESUME:
    run([MYSQLD, '--defaults-file='+str(ETC/'mysql.cnf'), '--initialize-insecure', '--user=mysql'])
unit('qicai-mysql', '[Unit]\nDescription=Qicai isolated MySQL\nAfter=network.target\n[Service]\nUser=mysql\nGroup=mysql\nExecStart='+MYSQLD+' --defaults-file='+str(ETC/'mysql.cnf')+'\nRestart=on-failure\nRestartSec=5\nLimitNOFILE=65535\nTimeoutStartSec=120\n[Install]\nWantedBy=multi-user.target\n')
write(ETC / 'redis.conf', 'bind 127.0.0.1\nprotected-mode yes\nport 16390\ndaemonize no\nsupervised systemd\nrequirepass '+redis_password+'\ndir '+str(BASE/'shared/redis')+'\nappendonly yes\nlogfile ""\n', 0o640)
shutil.chown(ETC/'redis.conf', group='qicai')
unit('qicai-redis', '[Unit]\nDescription=Qicai isolated Redis\nAfter=network.target\n[Service]\nUser=qicai\nGroup=qicai\nExecStart='+REDIS+' '+str(ETC/'redis.conf')+'\nRestart=on-failure\nRestartSec=5\nNoNewPrivileges=true\nPrivateTmp=true\n[Install]\nWantedBy=multi-user.target\n')
run(['systemctl', 'daemon-reload'])
run(['systemctl', 'enable', '--now', 'qicai-mysql', 'qicai-redis'])
for _ in range(60):
    if (data/'mysql.sock').exists():
        probe_args = [MYSQL, '--defaults-extra-file='+str(ETC/'mysql-root.cnf')] if RESUME else [MYSQL, '--socket='+str(data/'mysql.sock'), '-uroot']
        check = subprocess.run(probe_args+['-Nse', 'SELECT 1'], capture_output=True)
        if check.returncode == 0:
            break
    time.sleep(1)
else:
    sys.exit('MySQL startup failed')
if not RESUME:
    run([MYSQL, '--socket='+str(data/'mysql.sock'), '-uroot'], input="ALTER USER 'root'@'localhost' IDENTIFIED BY '"+root_password+"';\n", text=True)
mysql_args = [MYSQL, '--defaults-extra-file='+str(ETC/'mysql-root.cnf'), '--default-character-set=utf8mb4']
if not RESUME:
    run(mysql_args, input='CREATE DATABASE qicai_delivery CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;\n', text=True)
    run(mysql_args+['qicai_delivery'], input=(RELEASE/'sql/ry_20250522.sql').read_text(encoding='utf-8-sig'), text=True)
    run(mysql_args, input=(RELEASE/'sql/qicai_delivery.sql').read_text(encoding='utf-8-sig'), text=True)
    run(mysql_args+['qicai_delivery'], input=(RELEASE/'sql/qicai_delivery_upgrade_20260926.sql').read_text(encoding='utf-8-sig'), text=True)
    run(mysql_args+['qicai_delivery'], input=(RELEASE/'sql/qicai_payment_upgrade_20261007.sql').read_text(encoding='utf-8-sig'), text=True)
    run(mysql_args+['qicai_delivery'], input=(RELEASE/'sql/qicai_workflow_upgrade_20261015.sql').read_text(encoding='utf-8-sig'), text=True)
    run(mysql_args+['qicai_delivery'], input=(RELEASE/'sql/qicai_storefront_upgrade_20261017.sql').read_text(encoding='utf-8-sig'), text=True)
    run(mysql_args+['qicai_delivery'], input=(RELEASE/'sql/qicai_commercial_remediation_upgrade_20261017.sql').read_text(encoding='utf-8-sig'), text=True)
    run(mysql_args+['qicai_delivery'], input=(RELEASE/'sql/qicai_extensions_upgrade_20261018.sql').read_text(encoding='utf-8-sig'), text=True)
    run(mysql_args+['qicai_delivery'], input=(RELEASE/'sql/qicai_brand_upgrade_20261019.sql').read_text(encoding='utf-8-sig'), text=True)
    run(mysql_args+['qicai_delivery'], input=(RELEASE/'sql/qicai_product_unit_upgrade_20261031.sql').read_text(encoding='utf-8-sig'), text=True)
    run(mysql_args+['qicai_delivery'], input=(RELEASE/'sql/qicai_forward_warehouse_upgrade_20261101.sql').read_text(encoding='utf-8-sig'), text=True)
    run(mysql_args+['qicai_delivery'], input=(RELEASE/'sql/qicai_sales_names_upgrade_20261102.sql').read_text(encoding='utf-8-sig'), text=True)
jar = RELEASE/'backend/ruoyi-admin.jar'
with zipfile.ZipFile(jar) as archive:
    crypto = next(n for n in archive.namelist() if n.startswith('BOOT-INF/lib/spring-security-crypto-'))
    (RELEASE/'deploy/crypto.jar').write_bytes(archive.read(crypto))
    jcl = next(n for n in archive.namelist() if n.startswith('BOOT-INF/lib/spring-jcl-'))
    (RELEASE/'deploy/jcl.jar').write_bytes(archive.read(jcl))
run([JAVAC, '-cp', str(RELEASE/'deploy/crypto.jar'), str(RELEASE/'deploy/PasswordHash.java')])
hash_value = subprocess.check_output([JAVA, '-cp', str(RELEASE/'deploy')+':'+str(RELEASE/'deploy/*'), 'PasswordHash'], input=admin_password+'\n', text=True)
sql = "UPDATE sys_user SET password='"+hash_value+"',pwd_update_date=NOW() WHERE user_id=1; UPDATE sys_config SET config_value='"+('Qi!'+secrets.token_urlsafe(12))+"' WHERE config_key='sys.user.initPassword'; CREATE USER 'qicai_app'@'127.0.0.1' IDENTIFIED BY '"+db_password+"'; GRANT SELECT,INSERT,UPDATE,DELETE ON qicai_delivery.* TO 'qicai_app'@'127.0.0.1';"
run(mysql_args+['qicai_delivery'], input=sql, text=True)
shutil.copyfile(RELEASE/'deploy/application-server.yml', ETC/'application-server.yml')
current = BASE/'current'
if current.exists() or current.is_symlink():
    sys.exit('Unexpected existing release link')
current.symlink_to(RELEASE)
unit('qicai-api', '[Unit]\nDescription=Qicai procurement and delivery API\nAfter=network.target qicai-mysql.service qicai-redis.service\nRequires=qicai-mysql.service qicai-redis.service\n[Service]\nUser=qicai\nGroup=qicai\nWorkingDirectory='+str(BASE/'shared')+'\nEnvironmentFile='+str(ETC/'app.env')+'\nExecStart='+JAVA+' -Xms256m -Xmx768m -jar '+str(current/'backend/ruoyi-admin.jar')+' --spring.profiles.active=qicai,server --spring.config.additional-location=file:'+str(ETC)+'/'+'\nRestart=on-failure\nRestartSec=5\nNoNewPrivileges=true\nPrivateTmp=true\nUMask=0027\nTimeoutStopSec=45\n[Install]\nWantedBy=multi-user.target\n')

backup = BASE/'backups'/time.strftime('%Y%m%d-%H%M%S')
backup.mkdir(parents=True)
for domain in ['sandingdan.oksja.cn', 'sandingdanht.oksja.cn']:
    conf = VHOST/(domain+'.conf')
    shutil.copy2(conf, backup/conf.name)
    site = pathlib.Path('/www/wwwroot')/domain
    shutil.copytree(site, backup/domain)
    # Preserve the original site config; only replace the default placeholder contents.
    shutil.copyfile(RELEASE/'deploy/waiting.html', site/'index.html')

def internal_server(port, root):
    return '''server {
    listen 127.0.0.1:PORT;
    server_name localhost;
    root ROOT;
    index index.html;
    client_max_body_size 6m;
    add_header X-Content-Type-Options nosniff always;
    add_header Referrer-Policy same-origin always;
    location / { try_files $uri $uri/ /index.html; }
    location ^~ /prod-api/ { proxy_pass http://127.0.0.1:18130/; proxy_set_header Host $host; proxy_set_header X-Real-IP $remote_addr; proxy_set_header Forwarded ""; proxy_set_header X-Forwarded-For $remote_addr; proxy_set_header X-Forwarded-Proto $scheme; }
QICAI_PROXY
    location ^~ /profile/ { proxy_pass http://127.0.0.1:18130; proxy_set_header Forwarded ""; proxy_set_header X-Forwarded-For $remote_addr; }
    location ~ /\\. { deny all; }
}
'''.replace('PORT',str(port)).replace('ROOT',str(root)).replace('QICAI_PROXY', '    location ^~ /qicai/mobile/ { proxy_pass http://127.0.0.1:18130; proxy_set_header Host $host; proxy_set_header X-Real-IP $remote_addr; proxy_set_header Forwarded ""; proxy_set_header X-Forwarded-For $remote_addr; }' if port == 18132 else '')
write(VHOST/'qicai-internal.conf', internal_server(18131,current/'admin')+internal_server(18132,current/'h5'))
run(['/www/server/nginx/sbin/nginx','-t'])
run(['/www/server/nginx/sbin/nginx','-s','reload'])
run(['systemctl','daemon-reload'])
run(['systemctl','enable','--now','qicai-api'])
write(ETC/'installed.json', json.dumps({'release':str(RELEASE),'mode':'reserved','adminPort':18131,'h5Port':18132,'apiPort':18130,'mysqlPort':13310,'redisPort':16390,'backup':str(backup)},indent=2),0o600)
print('DEPLOYED_RESERVED: public waiting pages; all business listeners loopback only. Credentials: /etc/qicai/credentials.txt (root-only).')
