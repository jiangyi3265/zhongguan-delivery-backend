"""Reset only the explicitly named, disposable QA fixture after preserving it."""
import gzip, pathlib, subprocess, time
BASE = pathlib.Path('/www/qicai/verification-20260926')
DB = 'qicai_verify_20260926'
assert '/'+DB+'?' in (BASE/'app.env').read_text()
MYSQL = ['/www/server/mysql/bin/mysql','--defaults-extra-file=/etc/qicai/mysql-root.cnf','--default-character-set=utf8mb4',DB]
subprocess.run(['systemctl','stop','qicai-verify-20260926'],check=True)
dump = subprocess.check_output(['/www/server/mysql/bin/mysqldump','--defaults-extra-file=/etc/qicai/mysql-root.cnf','--single-transaction','--no-tablespaces','--set-gtid-purged=OFF',DB])
backup = BASE/('qa-before-reset-'+time.strftime('%Y%m%d-%H%M%S')+'.sql.gz')
backup.write_bytes(gzip.compress(dump)); backup.chmod(0o600)
tables = ['qc_delivery_attachment','qc_delivery_event','qc_delivery_task','qc_order_item','qc_customer_coupon','qc_order','qc_session','qc_sms_code','qc_upload','qc_customer','qc_courier','qc_product','qc_coupon']
sql = 'START TRANSACTION;\n' + ''.join('DELETE FROM '+t+';\n' for t in tables)
baseline=(BASE/'baseline.sql').read_text()
sql+='\n'.join(line for line in baseline.splitlines() if line.startswith(('INSERT INTO `qc_product`','INSERT INTO `qc_coupon`')))+'\nCOMMIT;'
subprocess.run(MYSQL,input=sql,text=True,check=True)
print('QA-only reset complete; previous QA data recoverable in '+str(backup))
subprocess.run(['python3','/tmp/qicai-verify-restart.py'],check=True)
