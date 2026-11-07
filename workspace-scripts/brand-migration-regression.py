"""Repeat/interrupted brand migration on an owned isolated clone; preserves existing rows and later admin edits."""
from pathlib import Path
import datetime as dt,hashlib,json,os,re,shutil,subprocess,sys,uuid
ROOT=Path(__file__).resolve().parents[1]
assert os.environ.get('QICAI_ISOLATED_TEST')=='1' and os.environ.get('QICAI_BASE_URL')=='http://127.0.0.1:15190'
OUT=Path(os.environ['QICAI_EVIDENCE_DIR']);DEST=OUT/'brand-migration-results.json';assert not DEST.exists()
sys.path.insert(0,str(ROOT/'tmp/acceptance-20261004/python-deps'))
import pymysql
db=pymysql.connect(host='127.0.0.1',port=13319,user='root',password='',database='qicai_acceptance',autocommit=True,cursorclass=pymysql.cursors.DictCursor)
def query(sql,args=None):
 with db.cursor() as c:c.execute(sql,args);return c.fetchall()
identity=query('SELECT @@port p,@@datadir d,DATABASE() n')[0]
assert identity['p']==13319 and identity['n']=='qicai_acceptance' and Path(identity['d']).resolve()==Path(os.environ['QICAI_RUNTIME']).resolve()/'mysql'
schema='qicai_brand_'+uuid.uuid4().hex[:12];assert re.fullmatch(r'qicai_brand_[a-f0-9]{12}',schema)
report={'startedAt':dt.datetime.now(dt.timezone.utc).isoformat(),'scope':__doc__,'checks':[]}
def snapshot(database,excluded=()):
 result={}
 for row in query('SELECT table_name n FROM information_schema.tables WHERE table_schema=%s ORDER BY table_name',(database,)):
  name=row['n'];assert re.fullmatch(r'[A-Za-z0-9_]+',name)
  if name in excluded:continue
  rows=query('SELECT * FROM `'+database+'`.`'+name+'`')
  values=sorted(json.dumps(v,ensure_ascii=False,sort_keys=True,default=str) for v in rows)
  ddl=query('SHOW CREATE TABLE `'+database+'`.`'+name+'`')[0]['Create Table']
  result[name]={'rows':len(rows),'values':hashlib.sha256('\n'.join(values).encode()).hexdigest(),'schema':hashlib.sha256(ddl.encode()).hexdigest()}
 return result
def apply(sql):
 p=subprocess.run([shutil.which('mysql'),'--no-defaults','--protocol=TCP','--host=127.0.0.1','--port=13319','--user=root','--default-character-set=utf8mb4',schema],input=sql.encode(),capture_output=True,timeout=60,creationflags=subprocess.CREATE_NO_WINDOW)
 assert p.returncode==0,p.stderr.decode(errors='replace')
def check(name,condition):
 report['checks'].append({'name':name,'passed':bool(condition)});assert condition,name;print('PASS '+name,flush=True)
try:
 original=snapshot('qicai_acceptance');query('CREATE DATABASE `'+schema+'` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci')
 dump=subprocess.run([shutil.which('mysqldump'),'--no-defaults','--protocol=TCP','--host=127.0.0.1','--port=13319','--user=root','--single-transaction','--hex-blob','--no-tablespaces','--set-gtid-purged=OFF','qicai_acceptance'],capture_output=True,timeout=60,creationflags=subprocess.CREATE_NO_WINDOW)
 assert dump.returncode==0;apply(dump.stdout.decode())
 check('隔离克隆逐行值与表结构一致',original==snapshot(schema))
 apply("DROP TABLE qc_brand_slide; DROP TABLE qc_product_brand; DROP TABLE qc_brand; DELETE FROM qc_schema_version WHERE version='brands-20261019';")
 before=snapshot(schema,excluded=['qc_schema_version'])
 migration=(ROOT/'RuoYi-Vue/sql/qicai_brand_upgrade_20261019.sql').read_text(encoding='utf-8')
 first=re.search(r'CREATE TABLE IF NOT EXISTS qc_brand\s*\([\s\S]*?ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;',migration).group(0)
 apply(first);apply(migration)
 check('中断后重试建立3张品牌表与版本标记',all(t in snapshot(schema) for t in ['qc_brand','qc_brand_slide','qc_product_brand']) and query('SELECT COUNT(*) n FROM `'+schema+"`.qc_schema_version WHERE version='brands-20261019'")[0]['n']==1)
 check('迁移保留全部旧表结构和逐行值',before==snapshot(schema,excluded=['qc_schema_version','qc_brand','qc_brand_slide','qc_product_brand']))
 db.select_db(schema)
 query("UPDATE qc_brand SET name='保留管理员品牌名称',description='保留简介',version=version+1 WHERE code='PPS'")
 query("DELETE s FROM qc_brand_slide s JOIN qc_brand b ON b.id=s.brand_id WHERE b.code='PPS'")
 query("DELETE FROM qc_product_brand WHERE product_id=(SELECT id FROM qc_product WHERE sku='TEST-20261018-RICE-001')")
 edited=snapshot(schema);apply(migration);apply(migration)
 check('重复迁移不会还原管理员编辑、删除的介绍图或已解除的商品归属',edited==snapshot(schema))
 check('原验收库未被克隆迁移更改',original==snapshot('qicai_acceptance'));report['exitCode']=0
except Exception as e:report['exitCode']=1;report['error']=str(e);raise
finally:
 db.select_db('qicai_acceptance');query('DROP DATABASE IF EXISTS `'+schema+'`');db.close();report['endedAt']=dt.datetime.now(dt.timezone.utc).isoformat();DEST.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
