"""Additive migration, interrupted creation and retry on an owned isolated clone."""
from pathlib import Path
import datetime as dt,gzip,hashlib,importlib.util,json,os,re,shutil,subprocess,sys,uuid
ROOT=Path(__file__).resolve().parents[1]
assert os.environ.get('QICAI_ISOLATED_TEST')=='1' and os.environ.get('QICAI_BASE_URL')=='http://127.0.0.1:15190'
OUT=Path(os.environ['QICAI_EVIDENCE_DIR']);DEST=OUT/'extensions-migration-results.json';assert not DEST.exists()
sys.path.insert(0,str(ROOT/'tmp/acceptance-20261004/python-deps'))
import pymysql
db=pymysql.connect(host='127.0.0.1',port=13319,user='root',password='',database='qicai_acceptance',autocommit=True,cursorclass=pymysql.cursors.DictCursor)
def query(sql,args=None):
    with db.cursor() as cursor:cursor.execute(sql,args);return cursor.fetchall()
state=query('SELECT @@port p,@@datadir d,DATABASE() n')[0]
assert state['p']==13319 and state['n']=='qicai_acceptance' and Path(state['d']).resolve()==Path(os.environ['QICAI_RUNTIME']).resolve()/'mysql'
schema='qicai_ext_'+uuid.uuid4().hex[:12];assert re.fullmatch(r'qicai_ext_[a-f0-9]{12}',schema)
report={'startedAt':dt.datetime.now(dt.timezone.utc).isoformat(),'scope':__doc__,'schema':schema,'checks':[]}
tables=['qc_notice_read','qc_order_item_media','qc_product_media','qc_recon_item','qc_recon_run','qc_recon_bill','qc_order_notice','qc_product_image']
def apply(sql):
    result=subprocess.run([shutil.which('mysql'),'--no-defaults','--protocol=TCP','--host=127.0.0.1','--port=13319','--user=root','--default-character-set=utf8mb4',schema],input=sql.encode(),capture_output=True,timeout=60,creationflags=subprocess.CREATE_NO_WINDOW)
    if result.returncode:raise RuntimeError(result.stderr.decode(errors='replace'))
def snapshot(name,excluded=()):
    result={}
    for row in query('SELECT table_name n FROM information_schema.tables WHERE table_schema=%s ORDER BY table_name',(name,)):
        table=row['n'];assert re.fullmatch(r'[A-Za-z0-9_]+',table)
        if table in excluded:continue
        values=query('SELECT * FROM `'+name+'`.`'+table+'`')
        rows=sorted(json.dumps(value,sort_keys=True,ensure_ascii=False,default=str) for value in values)
        create=query('SHOW CREATE TABLE `'+name+'`.`'+table+'`')[0]['Create Table']
        result[table]={'rows':len(rows),'dataSha256':hashlib.sha256('\n'.join(rows).encode()).hexdigest(),'schemaSha256':hashlib.sha256(create.encode()).hexdigest()}
    return result
def check(name,condition):
    report['checks'].append({'name':name,'passed':bool(condition)});print(('PASS ' if condition else 'FAIL ')+name,flush=True);assert condition,name
try:
    original=snapshot('qicai_acceptance')
    query('CREATE DATABASE `'+schema+'` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci')
    command=[shutil.which('mysqldump'),'--no-defaults','--protocol=TCP','--host=127.0.0.1','--port=13319','--user=root','--default-character-set=utf8mb4','--single-transaction','--routines','--triggers','--hex-blob','--no-tablespaces','--set-gtid-purged=OFF','qicai_acceptance']
    spec=importlib.util.spec_from_file_location('extension_backup',ROOT/'deploy/backup.py');backup=importlib.util.module_from_spec(spec);spec.loader.exec_module(backup)
    bundle=backup.create_backup(OUT/'full-backup',Path(os.environ['QICAI_RUNTIME'])/'uploads',command,database='qicai_acceptance')
    manifest=backup.verify_backup(bundle['path']);restored=OUT/'restored-uploads';backup.restore_uploads(bundle['path'],restored)
    for entry in manifest['files']:assert hashlib.sha256((restored/entry['path']).read_bytes()).hexdigest()==entry['sha256']
    photos=query('SELECT image_hash,byte_size FROM qc_product_image');assert photos
    for photo in photos:
        file=restored/'qicai-product'/(photo['image_hash']+'.jpg')
        assert file.is_file() and hashlib.sha256(file.read_bytes()).hexdigest()==photo['image_hash'] and file.stat().st_size==photo['byte_size']
    check('真实备份独立恢复全部上传文件和商品主图内容哈希',True)
    report['backup']={'path':bundle['path'],'files':len(manifest['files']),'productImages':len(photos)}
    with gzip.open(Path(bundle['path'])/'database.sql.gz','rt',encoding='utf-8') as dump:apply(dump.read())
    check('同一备份包恢复后的44张表结构和逐行值完全一致',original==snapshot(schema))
    apply('\n'.join('DROP TABLE IF EXISTS '+name+';' for name in tables)+"\nDELETE FROM qc_schema_version WHERE version='extensions-20261018';")
    before=snapshot(schema,excluded=['qc_schema_version']);old_marker=query('SELECT * FROM `'+schema+'`.qc_schema_version')
    migration=(ROOT/'RuoYi-Vue/sql/qicai_extensions_upgrade_20261018.sql').read_text(encoding='utf-8')
    # Simulate interruption immediately after the first table has been created.
    first=re.search(r'CREATE TABLE IF NOT EXISTS qc_product_image\s*\([\s\S]*?ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;',migration).group(0)
    apply(first);apply(migration)
    after=snapshot(schema,excluded=['qc_schema_version',*tables])
    check('增量迁移保留所有原表的结构、逐行值和行数',before==after)
    check('中断后重试创建全部8张新表且记录版本',set(tables).issubset(snapshot(schema)) and query('SELECT COUNT(*) n FROM `'+schema+"`.qc_schema_version WHERE version='extensions-20261018'")[0]['n']==1)
    db.select_db(schema)
    query("INSERT INTO qc_product_image(image_hash,byte_size,width,height,created_by) VALUES(%s,123,1,1,1)",('a'*64,))
    query("INSERT INTO qc_product_media(product_id,image_hash) SELECT MIN(id),%s FROM qc_product",('a'*64,))
    query("INSERT INTO qc_order_notice(order_id,event_key,event_type,recipient_type,recipient_id,title,content) VALUES(1,'migration:1','CREATED','customer',1,'synthetic','retain notice')")
    query("INSERT INTO qc_notice_read(notice_id,principal_type,principal_id) SELECT MAX(id),'customer',1 FROM qc_order_notice")
    query("INSERT INTO qc_recon_bill(bill_date,source_hash,merchant_id,normalized_rows,imported_by) VALUES('2026-10-01',%s,'synthetic','[]',1)",('b'*64,))
    complete=snapshot(schema);apply(migration);apply(migration)
    check('重复迁移完整保留新增图片、消息、已读和账单记录',complete==snapshot(schema))
    check('原验收库未被克隆迁移更改',original==snapshot('qicai_acceptance'))
    report.update(exitCode=0,oldTables=len(before),newTables=len(tables))
except Exception as error:report.update(exitCode=1,error=str(error))
finally:
    if report.get('exitCode')==0:
        assert re.fullmatch(r'qicai_ext_[a-f0-9]{12}',schema);db.select_db('qicai_acceptance');query('DROP DATABASE `'+schema+'`');report['ownedCloneRemoved']=True
    else:report['ownedCloneKeptForDiagnosis']=True
    db.close();report['endedAt']=dt.datetime.now(dt.timezone.utc).isoformat();DEST.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
raise SystemExit(report['exitCode'])
