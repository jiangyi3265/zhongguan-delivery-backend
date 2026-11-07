"""Real database invariants and isolated database/file restoration; no production access."""
import datetime as dt
import hashlib
import gzip
import importlib.util
import json
import decimal
import os
from pathlib import Path
import shutil
import subprocess as sp
import sys
import uuid

if os.environ.get('QICAI_ISOLATED_TEST') != '1' or os.environ.get('QICAI_BASE_URL') != 'http://127.0.0.1:15190':
    raise RuntimeError('Use the isolated acceptance runner')
out = Path(os.environ['QICAI_EVIDENCE_DIR'])
runtime = Path(os.environ['QICAI_RUNTIME'])
mysql = [shutil.which('mysql'), '--no-defaults', '--protocol=TCP', '--host=127.0.0.1', '--port=13319', '--user=root', '--default-character-set=utf8mb4', '--batch', '--skip-column-names']
results=[]

def compare_restored_rows(tables):
    # Preserve CHECKSUM TABLE as a separate gate, and capture business values too.
    # Only hashes/counts leave memory: synthetic passwords and session hashes are
    # not copied into the evidence. A later restart cannot replace this snapshot.
    sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'tmp/acceptance-20261004/python-deps'))
    import pymysql
    def canonical(value):
        if isinstance(value,(dt.date,dt.datetime,decimal.Decimal)):return str(value)
        if isinstance(value,bytes):return {'hex':value.hex()}
        return value
    def digest(value):
        return hashlib.sha256(json.dumps(value,ensure_ascii=False,sort_keys=True,separators=(',',':')).encode()).hexdigest()
    comparisons=[]
    with pymysql.connect(host='127.0.0.1',port=13319,user='root',charset='utf8mb4',cursorclass=pymysql.cursors.DictCursor) as db:
        with db.cursor() as cursor:
            cursor.execute('SELECT @@port AS port,@@datadir AS datadir')
            target=cursor.fetchone()
            assert target['port']==13319 and Path(target['datadir']).resolve()==(runtime/'mysql').resolve()
            db.begin()
            for table in tables:
                assert table.replace('_','').isalnum()
                snapshots=[]
                for schema in ['qicai_acceptance','qicai_acceptance_restore']:
                    cursor.execute('SHOW CREATE TABLE `'+schema+'`.`'+table+'`')
                    ddl=cursor.fetchone()['Create Table']
                    cursor.execute('SELECT * FROM `'+schema+'`.`'+table+'`')
                    rows=[{k:canonical(v) for k,v in row.items()} for row in cursor.fetchall()]
                    # Sorted row hashes preserve multiplicity while avoiding PK assumptions.
                    snapshots.append({'rows':len(rows),'rowValuesSha256':digest(sorted(digest(row) for row in rows)),'schemaSha256':digest(ddl)})
                comparisons.append({'table':table,'source':snapshots[0],'restored':snapshots[1],'equal':snapshots[0]==snapshots[1]})
            db.rollback()
    (out/'database-restored-value-comparison.json').write_text(json.dumps({'at':dt.datetime.now(dt.timezone.utc).isoformat(),'kind':'same-running-instance; all restored schemas and logical cell values; hashes only','tables':comparisons},ensure_ascii=False,indent=2),encoding='utf-8')
    return comparisons
def sql(query, db='qicai_acceptance'):
    p=sp.run(mysql+[db],input=query.encode('utf-8'),capture_output=True)
    if p.returncode:
        raise RuntimeError(p.stderr.decode('utf-8',errors='replace'))
    return p.stdout.decode('utf-8').strip()
def check(name, passed, detail=None):
    results.append({'name':name,'passed':bool(passed),'detail':detail})
    print(('PASS ' if passed else 'FAIL ')+name, flush=True)
checks={
 '订单金额与明细应付一致': "SELECT COUNT(*) FROM qc_order o WHERE o.subtotal_amount<>(SELECT COALESCE(SUM(line_amount),0) FROM qc_order_item WHERE order_id=o.id) OR o.payable_amount<>o.subtotal_amount-o.discount_amount OR o.discount_amount>o.subtotal_amount",
 '库存非负': 'SELECT COUNT(*) FROM qc_product WHERE stock<0',
 '种子库存与所有未取消订单精确对账': "SELECT COUNT(*) FROM qc_product p WHERE p.id IN (1,2,3) AND p.stock<>(CASE p.id WHEN 1 THEN 328 WHEN 2 THEN 510 ELSE 86 END)-(SELECT COALESCE(SUM(i.quantity),0) FROM qc_order_item i JOIN qc_order o ON o.id=i.order_id WHERE i.product_id=p.id AND o.status<>'CANCELLED')",
 '订单幂等唯一': 'SELECT COUNT(*) FROM (SELECT customer_id,idempotency_key FROM qc_order GROUP BY customer_id,idempotency_key HAVING COUNT(*)>1) t',
 '订单配送状态跨端一致': "SELECT COUNT(*) FROM qc_delivery_task d JOIN qc_order o ON o.id=d.order_id WHERE (d.status='DELIVERED' AND o.status<>'COMPLETED') OR (d.status IN ('ASSIGNED','PICKED_UP','IN_DELIVERY') AND d.status<>o.status)",
 '已使用优惠券归属及订单一致': "SELECT COUNT(*) FROM qc_customer_coupon c LEFT JOIN qc_order o ON o.id=c.used_order_id WHERE c.status='USED' AND (o.id IS NULL OR o.customer_id<>c.customer_id OR o.customer_coupon_id<>c.id OR o.status='CANCELLED')",
 '已取消订单没有继续占用优惠券': "SELECT COUNT(*) FROM qc_customer_coupon c JOIN qc_order o ON o.id=c.used_order_id WHERE o.status='CANCELLED' AND c.status='USED'",
 '签收具备姓名和凭证': "SELECT COUNT(*) FROM qc_delivery_task WHERE status='DELIVERED' AND (signed_name IS NULL OR signed_name='' OR delivery_proof_url IS NULL OR delivery_proof_url='')",
 '所有附件有真实上传登记': 'SELECT COUNT(*) FROM qc_delivery_attachment a LEFT JOIN qc_upload u ON u.file_name=a.file_name WHERE u.file_name IS NULL',
 '凭证上传主体属于订单': "SELECT COUNT(*) FROM qc_delivery_attachment a JOIN qc_upload u ON u.file_name=a.file_name JOIN qc_delivery_task d ON d.id=a.delivery_id JOIN qc_order o ON o.id=d.order_id WHERE NOT ((u.principal_type='courier' AND u.principal_id=d.courier_id) OR (u.principal_type='customer' AND u.principal_id=o.customer_id))",
 'Token仅保存哈希': 'SELECT COUNT(*) FROM qc_session WHERE LENGTH(token_hash)<>64',
 '停用或删除账号没有有效会话': "SELECT COUNT(*) FROM qc_session s LEFT JOIN qc_customer c ON s.principal_type='customer' AND c.id=s.principal_id LEFT JOIN qc_courier d ON s.principal_type='courier' AND d.id=s.principal_id WHERE (s.principal_type='customer' AND (c.deleted=1 OR c.status IN ('DISABLED','REJECTED'))) OR (s.principal_type='courier' AND (d.deleted=1 OR d.status='DISABLED'))",
 '未完成任务配送员未被停用或删除': "SELECT COUNT(*) FROM qc_delivery_task t JOIN qc_courier c ON c.id=t.courier_id WHERE t.status NOT IN ('DELIVERED','CANCELLED') AND (c.deleted=1 OR c.status<>'ACTIVE')",
 '微信客户不伪造密码登录手机号或密码': "SELECT COUNT(*) FROM qc_customer WHERE auth_method='WECHAT' AND (phone IS NOT NULL OR password_hash IS NOT NULL)",
 '微信账号映射的客群与认证方式一致': "SELECT COUNT(*) FROM qc_wechat_account a LEFT JOIN qc_customer c ON c.id=a.consumer_customer_id LEFT JOIN qc_customer b ON b.id=a.business_customer_id WHERE (a.consumer_customer_id IS NOT NULL AND (c.id IS NULL OR c.audience<>'consumer' OR c.auth_method<>'WECHAT')) OR (a.business_customer_id IS NOT NULL AND (b.id IS NULL OR b.audience<>'business' OR b.auth_method<>'WECHAT'))",
}
try:
    assert sql("SELECT CONCAT(@@port,'/',DATABASE())")=='13319/qicai_acceptance'
    assert Path(sql('SELECT @@datadir')).resolve()==(runtime/'mysql').resolve()
    # Persist explicit synthetic WECHAT rows so dump/restore proves nullable identity
    # and role mappings survive too. These are database fixtures, not real wx.login.
    fixture='restore_'+uuid.uuid4().hex[:20]
    ids=sql("START TRANSACTION; INSERT INTO qc_customer(audience,auth_method,contact_name,contact_phone,address,status) VALUES('consumer','WECHAT','恢复验收','13900001111','隔离恢复地址','ACTIVE'); SET @restore_c=LAST_INSERT_ID(); INSERT INTO qc_customer(audience,auth_method,company_name,contact_name,contact_phone,tax_id,address,status) VALUES('business','WECHAT','恢复企业','恢复联系人','13900001111','"+fixture+"','隔离企业地址','PENDING'); SET @restore_b=LAST_INSERT_ID(); INSERT INTO qc_wechat_account(app_id,open_id,consumer_customer_id,business_customer_id) VALUES('wx_restore_fixture','"+fixture+"',@restore_c,@restore_b); INSERT INTO qc_wechat_identity(customer_id,app_id,open_id) VALUES(@restore_c,'wx_restore_fixture','"+fixture+"'),(@restore_b,'wx_restore_fixture','"+fixture+"'); COMMIT; SELECT @restore_c,@restore_b;")
    check('恢复范围含合成微信C/B账号及同微信分角色映射',len(ids.split('\t'))==2,{'kind':'direct isolated DB fixture; no real provider','customerIds':ids.split('\t')})
    for name,query in checks.items():
        violations=int(sql(query))
        check(name,violations==0,{'violations':violations})
    files=sql('SELECT DISTINCT file_name FROM qc_delivery_attachment').splitlines()
    missing=[]
    for file in files:
        physical=runtime/'uploads'/file.removeprefix('/profile/')
        if not file.startswith('/profile/') or not physical.is_file():missing.append(file)
    check('数据库附件对应物理文件存在',len(missing)==0,{'files':len(files),'missingCount':len(missing)})
    # Dump from the private instance and restore into a separate empty database.
    command=[shutil.which('mysqldump'),'--no-defaults','--protocol=TCP','--host=127.0.0.1','--port=13319','--user=root','--default-character-set=utf8mb4','--single-transaction','--set-gtid-purged=OFF','--no-tablespaces','--hex-blob','qicai_acceptance']
    spec=importlib.util.spec_from_file_location('qicai_backup',Path(__file__).resolve().parents[1]/'deploy/backup.py')
    backup_module=importlib.util.module_from_spec(spec)
    spec.loader.exec_module(backup_module)
    package=backup_module.create_backup(out/'backup',runtime/'uploads',command,database='qicai_acceptance')
    bundle=Path(package['path'])
    manifest=backup_module.verify_backup(bundle)
    check('实际生产备份代码生成真实MySQL及文件完整包',True,package)
    sql('CREATE DATABASE qicai_acceptance_restore CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci')
    with gzip.open(bundle/'database.sql.gz','rb') as f:
        p=sp.run(mysql+['qicai_acceptance_restore'],input=f.read(),stdout=sp.PIPE,stderr=sp.PIPE)
    check('在隔离恢复库执行SQL成功',p.returncode==0,{'exitCode':p.returncode})
    if p.returncode:raise RuntimeError('Restore failed')
    tables=sql('SHOW TABLES').splitlines()
    differing=[]; checksums=[]
    for table in tables:
        source_lines=sql('CHECKSUM TABLE `'+table+'`; SHOW WARNINGS').splitlines()
        restored_lines=sql('CHECKSUM TABLE `'+table+'`; SHOW WARNINGS','qicai_acceptance_restore').splitlines()
        left=source_lines[0].split('\t')[-1]
        right=restored_lines[0].split('\t')[-1]
        checksums.append({'table':table,'source':left,'restored':right,'sourceWarnings':source_lines[1:],'restoredWarnings':restored_lines[1:]})
        if left!=right or left=='NULL':differing.append(table)
    check('恢复后所有表校验和逐表一致',not differing,{'tables':len(tables),'differing':differing,'checksums':checksums})
    compared=compare_restored_rows(tables)
    check('同一运行实例恢复后所有表schema和逻辑字段逐表一致',all(row['equal'] for row in compared),{'tables':len(compared),'differing':[row['table'] for row in compared if not row['equal']]})
    source=runtime/'uploads'
    recovered=runtime/'restored-uploads'
    backup_module.restore_uploads(bundle,recovered)
    all_files=[p for p in source.rglob('*') if p.is_file()]
    mismatches=[str(p.relative_to(source)) for p in all_files if hashlib.sha256(p.read_bytes()).digest()!=hashlib.sha256((recovered/p.relative_to(source)).read_bytes()).digest()]
    check('上传文件备份恢复逐文件SHA256一致',not mismatches,{'files':len(all_files),'mismatches':len(mismatches)})
except Exception as e:
    check('数据库验收执行完成',False,str(e))
finally:
    (out/'database-results.json').write_text(json.dumps({'at':dt.datetime.now(dt.timezone.utc).isoformat(),'results':results},ensure_ascii=False,indent=2),encoding='utf-8')
sys.exit(1 if any(not x['passed'] for x in results) else 0)
