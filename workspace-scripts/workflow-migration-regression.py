"""Real additive upgrade/backfill checks in a new isolated schema."""
import json, os, shutil, subprocess, uuid
from pathlib import Path
if os.environ.get('QICAI_ISOLATED_TEST')!='1': raise RuntimeError('Isolated acceptance context required')
root=Path(__file__).resolve().parents[1]
out=Path(os.environ['QICAI_EVIDENCE_DIR'])
command=[shutil.which('mysql'),'--no-defaults','--protocol=TCP','--host=127.0.0.1','--port=13319','--user=root','--default-character-set=utf8mb4','--batch','--skip-column-names']
def sql(query, schema='qicai_acceptance'):
    p=subprocess.run(command+[schema],input=query.encode(),capture_output=True)
    if p.returncode: raise RuntimeError(p.stderr.decode(errors='replace'))
    return p.stdout.decode().strip()
assert sql("SELECT CONCAT(@@port,'/',DATABASE())")=='13319/qicai_acceptance'
schema='qicai_workflow_upgrade_'+uuid.uuid4().hex[:12]
sql('CREATE DATABASE '+schema+' CHARACTER SET utf8mb4;')
sql("CREATE TABLE qc_order(id BIGINT PRIMARY KEY,status VARCHAR(24)); CREATE TABLE qc_delivery_task(id BIGINT PRIMARY KEY,order_id BIGINT,status VARCHAR(24)); CREATE TABLE qc_delivery_event(id BIGINT PRIMARY KEY,delivery_id BIGINT,status VARCHAR(24)); INSERT INTO qc_order VALUES(1,'PICKED_UP'),(2,'IN_DELIVERY'),(3,'ASSIGNED'),(4,'ASSIGNED'); INSERT INTO qc_delivery_task VALUES(1,1,'EXCEPTION'),(2,2,'EXCEPTION'),(3,3,'ASSIGNED'),(4,4,'EXCEPTION'); INSERT INTO qc_delivery_event VALUES(1,1,'PICKED_UP'),(2,1,'EXCEPTION'),(3,2,'ASSIGNED'),(4,2,'EXCEPTION'),(5,3,'ASSIGNED');",schema)
migration=(root/'RuoYi-Vue/sql/qicai_workflow_upgrade_20261015.sql').read_text(encoding='utf-8')
sql(migration,schema)
before=sql('SELECT id,order_id,status,exception_from FROM qc_delivery_task ORDER BY id',schema)
assert before.splitlines()==['1\t1\tEXCEPTION\tPICKED_UP','2\t2\tEXCEPTION\tNULL','3\t3\tASSIGNED\tNULL','4\t4\tEXCEPTION\tNULL']
sql("INSERT INTO qc_refund(order_id,out_refund_no,amount_fen,status,reason,requested_by,requested_name) VALUES(1,'RFupgrade',100,'PROCESSING','synthetic',1,'admin'); INSERT INTO qc_refund_event(order_id,status,note) VALUES(1,'PROCESSING','synthetic'); INSERT INTO qc_payment_maintenance(order_id,status,attempts) VALUES(2,'RETRY',3);",schema)
ledger=sql('SELECT out_refund_no,amount_fen,status,reason FROM qc_refund; SELECT order_id,status,attempts FROM qc_payment_maintenance; SELECT id,order_id,status,note FROM qc_refund_event',schema)
sql(migration,schema)
assert before==sql('SELECT id,order_id,status,exception_from FROM qc_delivery_task ORDER BY id',schema)
assert ledger==sql('SELECT out_refund_no,amount_fen,status,reason FROM qc_refund; SELECT order_id,status,attempts FROM qc_payment_maintenance; SELECT id,order_id,status,note FROM qc_refund_event',schema)
results=[{'name':'legacy-exception-backfill-requires-matching-order-and-last-event','passed':True},{'name':'unknown-or-inconsistent-history-remains-blocked','passed':True},{'name':'repeat-migration-preserves-tasks-refunds-events-and-retry-ledger','passed':True}]
(out/'workflow-migration-results.json').write_text(json.dumps({'kind':'isolated MySQL, not production','schema':schema,'results':results},ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(results,ensure_ascii=False))
