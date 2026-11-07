"""Real MySQL upgrade/retry checks in newly owned schemas; no production or payment."""
import datetime as dt, hashlib, json, os, re, shutil, subprocess, sys, uuid
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
if os.environ.get('QICAI_ISOLATED_TEST')!='1' or os.environ.get('QICAI_BASE_URL')!='http://127.0.0.1:15190':
    raise RuntimeError('Verified isolated acceptance context required')
OUT=Path(os.environ['QICAI_EVIDENCE_DIR']);OUT.mkdir(parents=True,exist_ok=True)
DEST=OUT/'remediation-migration-results.json'
if DEST.exists(): raise RuntimeError('Refuse to overwrite evidence')
sys.path.insert(0,str(ROOT/'tmp/acceptance-20261004/python-deps'))
import pymysql
db=pymysql.connect(host='127.0.0.1',port=13319,user='root',password='',database='qicai_acceptance',autocommit=True,cursorclass=pymysql.cursors.DictCursor)
report={'startedAt':dt.datetime.now(dt.timezone.utc).isoformat(),'kind':__doc__,'checks':[],'commands':[]}
schemas=[]
def query(sql,args=None):
    with db.cursor() as c: c.execute(sql,args);return c.fetchall()
state=query('SELECT @@port AS port,@@datadir AS datadir,DATABASE() AS db')[0]
runtime=Path(os.environ['QICAI_RUNTIME']).resolve()
assert state['port']==13319 and state['db']=='qicai_acceptance' and Path(state['datadir']).resolve()==runtime/'mysql'
assert re.fullmatch(r'qicai-acceptance-[a-f0-9]+',runtime.name)
report['isolationDatadirVerified']=True
def check(name,value,**details):
    report['checks'].append({'name':name,'passed':bool(value),**details})
    print(('PASS ' if value else 'FAIL ')+name,flush=True)
    assert value,name
def apply(name,sql,schema,expected_success=True):
    assert schema in schemas and re.fullmatch(r'qicai_remediation_[a-f0-9]{12}',schema)
    command=[shutil.which('mysql'),'--no-defaults','--protocol=TCP','--host=127.0.0.1','--port=13319','--user=root','--default-character-set=utf8mb4','--batch',schema]
    start=dt.datetime.now(dt.timezone.utc).isoformat()
    result=subprocess.run(command,input=sql.encode(),capture_output=True,timeout=60,creationflags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0)
    report['commands'].append({'name':name,'command':command,'sqlSha256':hashlib.sha256(sql.encode()).hexdigest(),'startedAt':start,'endedAt':dt.datetime.now(dt.timezone.utc).isoformat(),'exitCode':result.returncode,'expectedSuccess':expected_success})
    (OUT/(name+'.log')).write_bytes(result.stdout+result.stderr)
    assert (result.returncode==0)==expected_success,name
def bootstrap(schema):
    query('CREATE DATABASE `'+schema+'` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci')
    schemas.append(schema)
    source=(ROOT/'RuoYi-Vue/sql/qicai_delivery.sql').read_text(encoding='utf-8')
    source=re.sub(r'CREATE DATABASE IF NOT EXISTS qicai_delivery.*?;','',source,flags=re.S).replace('USE qicai_delivery;','')
    apply(schema+'-base',source,schema)
    for filename in ['qicai_delivery_upgrade_20260926.sql','qicai_payment_upgrade_20261007.sql','qicai_workflow_upgrade_20261015.sql','qicai_storefront_upgrade_20261017.sql']:
        apply(schema+'-'+filename,(ROOT/'RuoYi-Vue/sql'/filename).read_text(encoding='utf-8'),schema)
def columns(schema):
    result={}
    for row in query('SELECT table_name t,column_name c FROM information_schema.columns WHERE table_schema=%s ORDER BY table_name,ordinal_position',(schema,)):
        result.setdefault(row['t'],[]).append(row['c'])
    return result
def digest(schema,fields):
    result={}
    for table,names in fields.items():
        assert all(re.fullmatch(r'[A-Za-z0-9_]+',x) for x in [table,*names])
        values=query('SELECT '+','.join('`'+n+'`' for n in names)+' FROM `'+schema+'`.`'+table+'`')
        rows=sorted(hashlib.sha256(json.dumps(row,sort_keys=True,ensure_ascii=False,default=str).encode()).hexdigest() for row in values)
        result[table]={'count':len(rows),'rowsSha256':rows,'sha256':hashlib.sha256('\n'.join(rows).encode()).hexdigest()}
    return result
try:
    schema='qicai_remediation_'+uuid.uuid4().hex[:12];bootstrap(schema)
    apply('migration-synthetic-fixtures',"""
INSERT INTO qc_customer(id,audience,auth_method,phone,password_hash,contact_name,address,status) VALUES
(900001,'consumer','PASSWORD','13900000001','synthetic-hash','synthetic-password','isolated','ACTIVE'),
(900002,'consumer','WECHAT',NULL,NULL,'synthetic-wechat','isolated','ACTIVE');
INSERT INTO qc_wechat_account(app_id,open_id,consumer_customer_id) VALUES('synthetic-app','synthetic-openid',900002);
UPDATE qc_product SET stock=2147483647 WHERE id=1;
INSERT INTO qc_order(id,order_no,customer_id,audience,status,payment_status,subtotal_amount,discount_amount,payable_amount,contact_name,contact_phone,delivery_address,idempotency_key) VALUES
(900001,'SYNTH-MIGRATION',900001,'consumer','PENDING_SHIPMENT','PAID',1,0,1,'synthetic','13900000001','unchanged-address','migration-only');
INSERT INTO qc_order_item(order_id,product_id,sku,product_name,unit,unit_price,quantity,line_amount) VALUES(900001,1,'migration-sku','synthetic','item',1,1,1);
INSERT INTO qc_customer_coupon(customer_id,coupon_id,status,used_order_id) VALUES(900001,3,'USED',900001);
INSERT INTO qc_delivery_task(id,task_no,order_id,status) VALUES(900001,'SYNTH-TASK',900001,'WAITING_ASSIGN');
INSERT INTO qc_delivery_event(delivery_id,status,note) VALUES(900001,'WAITING_ASSIGN','historical actor unknown');
INSERT INTO qc_payment(order_id,out_trade_no,merchant_id,app_id,payer_open_id,amount_fen,status,uncertain_attempts) VALUES(900001,'SYNTH-PAY','synthetic-merchant','synthetic-app','synthetic-openid',100,'PAID',0);
INSERT INTO qc_refund(order_id,out_refund_no,amount_fen,status,reason,requested_by,requested_name) VALUES(900001,'SYNTH-REFUND',100,'REQUESTED','synthetic',1,'synthetic-admin');
INSERT INTO qc_refund_event(order_id,status,note) VALUES(900001,'REQUESTED','synthetic');
""",schema)
    old_fields=columns(schema);before=digest(schema,old_fields)
    explain_sql='EXPLAIN UPDATE `'+schema+"`.qc_customer_coupon SET status='AVAILABLE',used_order_id=NULL WHERE used_order_id=900001 AND status='USED'"
    explain_before=query(explain_sql)
    migration=(ROOT/'RuoYi-Vue/sql/qicai_commercial_remediation_upgrade_20261017.sql').read_text(encoding='utf-8')
    report['migrationSha256']=hashlib.sha256(migration.encode()).hexdigest()
    apply('remediation-upgrade-first',migration,schema)
    report['couponReturnExplain']={'before':explain_before,'after':query(explain_sql),'kind':'Actual MySQL EXPLAIN on synthetic rows; no capacity or lock-contention claim'}
    after=digest(schema,old_fields);report['oldColumnDigests']={'before':before,'after':after}
    check('迁移保持所有旧列原值和行数（包含密码/微信映射/订单/券/付款/退款）',before==after)
    db.select_db(schema)
    check('历史配送主体保持未知且旧订单坐标保持空',query('SELECT COUNT(*) n FROM qc_delivery_event WHERE actor_type IS NOT NULL OR actor_id IS NOT NULL OR actor_name IS NOT NULL')[0]['n']==0
          and query('SELECT COUNT(*) n FROM qc_order WHERE delivery_latitude IS NOT NULL OR delivery_longitude IS NOT NULL OR delivery_location_name IS NOT NULL')[0]['n']==0)
    apply('remediation-new-data',"""
UPDATE qc_product SET stock=2147483648 WHERE id=1;
UPDATE qc_coupon SET version=7 WHERE id=3;
UPDATE qc_order SET delivery_latitude=22.5430960,delivery_longitude=114.0578650,delivery_location_name='synthetic-place' WHERE id=900001;
UPDATE qc_delivery_event SET note=CONCAT('signed:',REPEAT('x',255)),actor_type='courier',actor_id=77,actor_name='synthetic-courier' WHERE delivery_id=900001;
INSERT INTO qc_business_audit(actor_type,actor_id,actor_name,action,object_type,object_id,before_values,after_values) VALUES('admin',1,'synthetic','UPDATE','coupon',3,JSON_OBJECT('version',6),JSON_OBJECT('version',7));
""",schema)
    fields=columns(schema);before_repeat=digest(schema,fields)
    apply('remediation-upgrade-repeat',migration,schema)
    check('重复迁移保留新版本/超INT库存/完整备注/地点/审计和原marker',before_repeat==digest(schema,fields))
    check('新约束支持旧INT边界回补和完整备注',query('SELECT stock FROM qc_product WHERE id=1')[0]['stock']==2147483648
          and query('SELECT CHAR_LENGTH(note) n FROM qc_delivery_event WHERE delivery_id=900001')[0]['n']==262)
    rejected=0
    for statement in ["UPDATE qc_product SET stock=-1 WHERE id=1","UPDATE qc_product SET stock=9007199254740992 WHERE id=1",
                      "UPDATE qc_order SET delivery_latitude=91 WHERE id=900001","UPDATE qc_order SET delivery_longitude=NULL WHERE id=900001"]:
        try: query(statement)
        except pymysql.err.MySQLError as error:
            if error.args[0] != 3819: raise
            rejected+=1
    check('数据库负库存/安全整数溢出/越界或半套坐标全部拒绝且无副作用',rejected==4 and before_repeat==digest(schema,fields))
    index=query("SELECT column_name c FROM information_schema.statistics WHERE table_schema=%s AND table_name='qc_customer_coupon' AND index_name='idx_qc_customer_coupon_used_order' ORDER BY seq_in_index",(schema,))
    check('回补索引顺序及最后就绪标记存在',index==[{'c':'used_order_id'},{'c':'status'}] and query("SELECT COUNT(*) n FROM qc_schema_version WHERE version='commercial-remediation-20261017'")[0]['n']==1)
    partial='qicai_remediation_'+uuid.uuid4().hex[:12];bootstrap(partial)
    apply('partial-migration-inject-missing-table','RENAME TABLE qc_coupon TO qc_coupon_temporarily_missing',partial)
    apply('partial-migration-must-fail',migration,partial,expected_success=False)
    check('DDL中途失败不会生成就绪标记',query("SELECT COUNT(*) n FROM information_schema.tables WHERE table_schema=%s AND table_name='qc_schema_version'",(partial,))[0]['n']==0)
    apply('partial-migration-repair-fixture','RENAME TABLE qc_coupon_temporarily_missing TO qc_coupon',partial)
    apply('partial-migration-retry',migration,partial)
    check('中途DDL失败修正前提后可重试完成',query('SELECT COUNT(*) n FROM `'+partial+"`.qc_schema_version WHERE version='commercial-remediation-20261017'")[0]['n']==1)
    report['exitCode']=0
except Exception as error:
    report.update(exitCode=1,errorType=type(error).__name__,error=str(error))
finally:
    report['schemas']=schemas
    try:
        db.select_db('qicai_acceptance')
        assert Path(query('SELECT @@datadir p')[0]['p']).resolve()==runtime/'mysql'
        for schema in schemas:
            assert re.fullmatch(r'qicai_remediation_[a-f0-9]{12}',schema)
            query('DROP DATABASE `'+schema+'`')
        report['ownedSchemasRemoved']=True
    except Exception as error: report.update(exitCode=1,cleanupError=str(error))
    db.close();report['endedAt']=dt.datetime.now(dt.timezone.utc).isoformat()
    DEST.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
raise SystemExit(report['exitCode'])
