"""Create a separate QA database and internal API. Never initialize the live DB."""
import os, pathlib, secrets, shutil, subprocess, sys

BASE = pathlib.Path('/www/qicai/verification-20260926')
DB = 'qicai_verify_20260926'
USER = 'qicai_verify_0926'
MYSQL = ['/www/server/mysql/bin/mysql', '--defaults-extra-file=/etc/qicai/mysql-root.cnf', '--default-character-set=utf8mb4', '-N', '-B']
JAVA = '/www/server/java/jdk-17.0.8/bin/java'

def sql(query, database=None):
    return subprocess.check_output(MYSQL + ([database] if database else []), input=query, text=True)

if os.geteuid() != 0: sys.exit('Requires root')
listeners = subprocess.check_output(['ss', '-lntH'], text=True)
if any(row.split()[3].endswith(':19186') for row in listeners.splitlines()):
    sys.exit('QA port occupied; choose another port without disturbing its owner')
if BASE.exists() or sql("SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='" + DB + "'").strip():
    sys.exit('QA environment exists; refusing to replace data')
if sql('SELECT COUNT(*) FROM qc_customer', 'qicai_delivery').strip() != '0':
    sys.exit('Live customers exist; use schema-only anonymized fixture instead')
BASE.mkdir(mode=0o755)
env = dict(line.split('=', 1) for line in pathlib.Path('/etc/qicai/app.env').read_text().splitlines() if '=' in line)
redis_env = dict(os.environ, REDISCLI_AUTH=env['QICAI_REDIS_PASSWORD'])
count = subprocess.check_output(['/www/server/redis/src/redis-cli', '-p', '16390', '-n', '14', 'DBSIZE'], env=redis_env, text=True).strip()
if count != '0': sys.exit('Redis QA database is not empty')
dump = subprocess.check_output(['/www/server/mysql/bin/mysqldump', '--defaults-extra-file=/etc/qicai/mysql-root.cnf', '--single-transaction', '--no-tablespaces', '--set-gtid-purged=OFF', 'qicai_delivery'])
(BASE / 'baseline.sql').write_bytes(dump)
(BASE / 'baseline.sql').chmod(0o600)
sql('CREATE DATABASE ' + DB + ' CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci')
subprocess.run(MYSQL + [DB], input=dump, check=True)
pwd = secrets.token_hex(24)
sql("CREATE USER '" + USER + "'@'127.0.0.1' IDENTIFIED BY '" + pwd + "'; GRANT SELECT,INSERT,UPDATE,DELETE ON " + DB + ".* TO '" + USER + "'@'127.0.0.1';")
env.update(QICAI_DB_USERNAME=USER, QICAI_DB_PASSWORD=pwd, QICAI_DB_URL=env['QICAI_DB_URL'].replace('/qicai_delivery?', '/' + DB + '?'), QICAI_SMS_DEV_MODE='true', QICAI_TOKEN_SECRET=secrets.token_hex(48), QICAI_LOG_PATH=str(BASE / 'logs'))
admin_hash = subprocess.check_output([JAVA, '-cp', '/www/qicai/current/deploy:/www/qicai/current/deploy/*', 'PasswordHash'], input='QcVerify2026!\n', text=True).strip()
sql("UPDATE sys_user SET password='" + admin_hash + "',pwd_update_date=NOW() WHERE user_id=1; UPDATE sys_config SET config_value='false' WHERE config_key='sys.account.captchaEnabled';", DB)
(BASE / 'app.env').write_text(''.join(k + '=' + v + '\n' for k, v in env.items()))
(BASE / 'app.env').chmod(0o600)
for directory in ['logs', 'uploads']:
    (BASE / directory).mkdir()
    shutil.chown(BASE / directory, user='qicai', group='qicai')
shutil.copyfile('/www/qicai/current/backend/ruoyi-admin.jar', BASE / 'ruoyi-admin.jar')
unit = '[Unit]\nDescription=Isolated Qicai acceptance (not public)\nAfter=qicai-mysql.service qicai-redis.service\n[Service]\nUser=qicai\nGroup=qicai\nEnvironmentFile=' + str(BASE / 'app.env') + '\nWorkingDirectory=' + str(BASE) + '\nExecStart=' + JAVA + ' -Xms128m -Xmx512m -jar ' + str(BASE / 'ruoyi-admin.jar') + ' --spring.profiles.active=qicai,server --spring.config.additional-location=file:/etc/qicai/ --server.port=19186 --spring.redis.database=14 --ruoyi.profile=' + str(BASE / 'uploads') + '\nNoNewPrivileges=true\nPrivateTmp=true\nUMask=0027\n'
pathlib.Path('/etc/systemd/system/qicai-verify-20260926.service').write_text(unit)
subprocess.run(['systemctl', 'daemon-reload'], check=True)
subprocess.run(['systemctl', 'start', 'qicai-verify-20260926'], check=True)
print('QA service created on 127.0.0.1:19186; DB ' + DB + '; live DB/config unchanged.')
