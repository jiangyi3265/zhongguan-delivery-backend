"""Upgrade the early payment candidate in a NEW isolated schema; preserve unknown sends."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import uuid

if os.environ.get('QICAI_ISOLATED_TEST')!='1':
    raise RuntimeError('Use the isolated acceptance runner')
root=Path(__file__).resolve().parent.parent
out=Path(os.environ['QICAI_EVIDENCE_DIR'])
mysql=[shutil.which('mysql'),'--no-defaults','--protocol=TCP','--host=127.0.0.1','--port=13319','--user=root','--default-character-set=utf8mb4','--batch','--skip-column-names']
def sql(query,db='qicai_acceptance'):
    result=subprocess.run(mysql+[db],input=query.encode('utf-8'),capture_output=True)
    if result.returncode:
        raise RuntimeError(result.stderr.decode('utf-8',errors='replace'))
    return result.stdout.decode('utf-8').strip()
assert sql("SELECT CONCAT(@@port,'/',DATABASE())")=='13319/qicai_acceptance'
schema='qicai_payment_upgrade_'+uuid.uuid4().hex[:12]
migration=(root/'RuoYi-Vue/sql/qicai_payment_upgrade_20261007.sql').read_text(encoding='utf-8')
old=migration.split('CREATE TABLE IF NOT EXISTS qc_payment (',1)[1].split('ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;',1)[0]
old=('CREATE TABLE qc_payment ('+old+'ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;').replace("DEFAULT 'PREPARED'","DEFAULT 'PENDING'").replace('  uncertain_attempts INT NOT NULL DEFAULT 0,\n','')
sql('CREATE DATABASE '+schema+' CHARACTER SET utf8mb4;')
sql("CREATE TABLE qc_customer(id BIGINT PRIMARY KEY);CREATE TABLE qc_order(id BIGINT PRIMARY KEY,status VARCHAR(32));INSERT INTO qc_customer VALUES(1);INSERT INTO qc_order VALUES(1,'PENDING_PAYMENT'),(2,'PENDING_PAYMENT');"+old,schema)
insert="INSERT INTO qc_payment(order_id,out_trade_no,merchant_id,app_id,payer_open_id,amount_fen) VALUES(%s,'UPGRADE%s','123456','wx0000000000000000','synthetic',100);"
sql(insert%(1,1),schema)
assert sql('SELECT status FROM qc_payment WHERE order_id=1',schema)=='PENDING'
sql(migration,schema);sql(migration,schema)
sql(insert%(2,2),schema)
results=[
    {'name':'early-candidate-unknown-PENDING-preserved-after-two-upgrades','passed':sql("SELECT CONCAT(status,'/',uncertain_attempts) FROM qc_payment WHERE order_id=1",schema)=='PENDING/1'},
    {'name':'new-record-default-PREPARED-and-zero-unknown-sends','passed':sql("SELECT CONCAT(status,'/',uncertain_attempts) FROM qc_payment WHERE order_id=2",schema)=='PREPARED/0'},
    {'name':'legacy-orders-not-retroactively-charged','passed':sql("SELECT COUNT(*) FROM qc_order WHERE payment_status='NOT_REQUIRED'",schema)=='2'},
]
(out/'payment-migration-results.json').write_text(json.dumps({'kind':'real isolated MySQL early-candidate upgrade; no production','schema':schema,'results':results},ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(results,ensure_ascii=False),flush=True)
if not all(r['passed'] for r in results):raise RuntimeError('Payment upgrade regression failed')
