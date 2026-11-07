#!/usr/bin/env python3
"""Finalize only this fresh reserved Qicai installation after acceptance."""
import pathlib, subprocess, json
etc = pathlib.Path('/etc/qicai')
base = pathlib.Path('/www/qicai')
marker = json.loads((etc/'installed.json').read_text())
if marker['mode'] != 'reserved':
    raise SystemExit('Not a reserved environment; refusing cleanup')
if marker.get('finalized'):
    raise SystemExit('Already finalized; refusing to delete any later business data')
mysql = ['/www/server/mysql/bin/mysql','--defaults-extra-file=/etc/qicai/mysql-root.cnf','--default-character-set=utf8mb4','qicai_delivery']
def sql(text):
    return subprocess.check_output(mysql+['-Nse',text],text=True)
counts = sql("SELECT 'customers',COUNT(*) FROM qc_customer UNION ALL SELECT 'orders',COUNT(*) FROM qc_order UNION ALL SELECT 'couriers',COUNT(*) FROM qc_courier;")
print('Acceptance data before cleanup:\n'+counts)
# These services have never been exposed publicly; all non-seed business records are from this run.
if int(sql('SELECT COUNT(*) FROM qc_customer;').strip()) > 12:
    raise SystemExit('Unexpected customer count; review manually before cleanup')
subprocess.run(['/usr/bin/python3',str(base/'current/deploy/backup.py')],check=True)
cleanup = """START TRANSACTION;
DELETE FROM qc_delivery_event;
DELETE FROM qc_delivery_task;
DELETE FROM qc_order_item;
UPDATE qc_customer_coupon SET used_order_id=NULL, used_at=NULL;
DELETE FROM qc_order;
DELETE FROM qc_customer_coupon;
DELETE FROM qc_session;
DELETE FROM qc_sms_code;
DELETE FROM qc_customer;
DELETE FROM qc_courier;
DELETE FROM qc_product WHERE id>3;
UPDATE qc_product SET stock=CASE id WHEN 1 THEN 328 WHEN 2 THEN 510 WHEN 3 THEN 86 END,version=0,deleted=0,status='ACTIVE' WHERE id IN(1,2,3);
UPDATE sys_config SET config_value='true' WHERE config_key='sys.account.captchaEnabled';
COMMIT;"""
subprocess.run(mysql,input=cleanup,text=True,check=True)
env = etc/'app.env'
env.write_text(env.read_text().replace('QICAI_SMS_DEV_MODE=true','QICAI_SMS_DEV_MODE=false'))
env.chmod(0o600)
service = '[Unit]\nDescription=Qicai transactional database backup\nAfter=qicai-mysql.service\n[Service]\nType=oneshot\nUser=root\nUMask=0077\nExecStart=/usr/bin/python3 /www/qicai/current/deploy/backup.py\n'
timer = '[Unit]\nDescription=Qicai daily database backup\n[Timer]\nOnCalendar=*-*-* 03:15:00\nPersistent=true\nRandomizedDelaySec=600\n[Install]\nWantedBy=timers.target\n'
pathlib.Path('/etc/systemd/system/qicai-backup.service').write_text(service)
pathlib.Path('/etc/systemd/system/qicai-backup.timer').write_text(timer)
subprocess.run(['systemctl','daemon-reload'],check=True)
subprocess.run(['systemctl','enable','--now','qicai-backup.timer'],check=True)
subprocess.run(['systemctl','restart','qicai-api'],check=True)
subprocess.run(['/usr/bin/python3',str(base/'current/deploy/backup.py')],check=True)
print('Clean data counts:\n'+sql("SELECT 'customers',COUNT(*) FROM qc_customer UNION ALL SELECT 'orders',COUNT(*) FROM qc_order UNION ALL SELECT 'couriers',COUNT(*) FROM qc_courier UNION ALL SELECT 'products',COUNT(*) FROM qc_product UNION ALL SELECT 'coupons',COUNT(*) FROM qc_coupon;"))
print('FINALIZED: development SMS disabled, CAPTCHA restored, daily backups enabled.')
marker['finalized'] = True
(etc/'installed.json').write_text(json.dumps(marker,indent=2))
(etc/'installed.json').chmod(0o600)
