"""Post-upgrade reserved-mode checks. No production business records are changed."""
import gzip, hashlib, json, pathlib, subprocess, time, urllib.request, urllib.error
BASE=pathlib.Path('/www/qicai')
MYSQL=['/www/server/mysql/bin/mysql','--defaults-extra-file=/etc/qicai/mysql-root.cnf','--batch','--skip-column-names']
def sql(query,db='qicai_delivery'): return subprocess.check_output(MYSQL+[db],input=query,text=True).strip()
def http(path,port=18130,body=None,host=None):
    request=urllib.request.Request('http://127.0.0.1:'+str(port)+path,data=None if body is None else json.dumps(body).encode(),headers={'Content-Type':'application/json',**({'Host':host} if host else {})})
    try:
        with urllib.request.urlopen(request,timeout=10) as response: return response.status,response.read().decode()
    except urllib.error.HTTPError as error: return error.code,error.read().decode()
checks=[]
def check(name,ok): checks.append({'name':name,'passed':bool(ok)})
status,body=http('/qicai/mobile/health');check('生产后端健康检查',status==200 and json.loads(body)['data']['status']=='UP')
status,body=http('/captchaImage');check('生产后台验证码保护开启',status==200 and json.loads(body).get('captchaEnabled') is True)
status,body=http('/qicai/mobile/auth/sms',body={'audience':'consumer','phone':'13800000000'});check('未配置真实短信明确503且无开发码',status==503 and 'devCode' not in body)
for p in ['customers','products','orders','couriers','deliveries','coupons','dashboard']:
    status,body=http('/qicai/'+p,18131);check('后台刷新路由 '+p,status==200 and '<html' in body.lower())
status,body=http('/',18132);check('H5生产静态包可访问',status==200 and '<html' in body.lower())
for domain in ['sandingdan.oksja.cn','sandingdanht.oksja.cn']:
    status,body=http('/',80,host=domain);check(domain+'保留待启用页',status==200 and '待' in body)
    status,body=http('/qicai/mobile/health',80,host=domain);check(domain+'公网业务API保持关闭',status==404)
for service in ['qicai-api','qicai-mysql','qicai-redis','qicai-backup.timer']:
    check(service+'正常运行',subprocess.run(['systemctl','is-active','--quiet',service]).returncode==0)
    check(service+'开机自启',subprocess.run(['systemctl','is-enabled','--quiet',service]).returncode==0)
listeners=subprocess.check_output(['ss','-lntH'],text=True).splitlines()
for port in [13310,16390,18130,18131,18132]:
    bound=[line.split()[3] for line in listeners if line.split()[3].endswith(':'+str(port))]
    check('端口'+str(port)+'仅监听本机',bool(bound) and all(item.startswith('127.0.0.1:') for item in bound))
tables=int(sql("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='qicai_delivery' AND LEFT(table_name,3)='qc_'"))
check('13张业务表迁移完成',tables==13)
counts={'customers':int(sql('SELECT COUNT(*) FROM qc_customer')),'orders':int(sql('SELECT COUNT(*) FROM qc_order'))}
check('生产客户订单未混入验收数据',counts=={'customers':0,'orders':0})
check('生产未残留开发短信记录',int(sql('SELECT COUNT(*) FROM qc_sms_code'))==0)
subprocess.run(['systemctl','start','qicai-backup.service'],check=True)
latest=max((BASE/'backups/database').glob('*.sql.gz'),key=lambda p:p.stat().st_mtime)
check('新版本实际备份成功',time.time()-latest.stat().st_mtime<120)
restore='qicai_restore_check_20260926'
if int(sql("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='"+restore+"'")):
    raise SystemExit('Restore check database already exists; refusing overwrite')
sql('CREATE DATABASE '+restore+' CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci')
subprocess.run(MYSQL+[restore],input=gzip.decompress(latest.read_bytes()),check=True)
restored_tables=int(sql("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='"+restore+"' AND LEFT(table_name,3)='qc_'",restore))
check('备份恢复到隔离数据库后表及业务计数正确',restored_tables==13 and int(sql('SELECT COUNT(*) FROM qc_customer',restore))==counts['customers'] and int(sql('SELECT COUNT(*) FROM qc_order',restore))==counts['orders'] and int(sql('SELECT COUNT(*) FROM qc_product',restore))==int(sql('SELECT COUNT(*) FROM qc_product')))
if checks[-1]['passed']: sql('DROP DATABASE qicai_restore_check_20260926')
report={'at':time.strftime('%Y-%m-%d %H:%M:%S'),'release':str((BASE/'current').resolve()),'checks':checks,'passed':sum(x['passed'] for x in checks),'total':len(checks),'liveCounts':counts,'businessTables':tables,'backup':str(latest),'jarSha256':hashlib.sha256((BASE/'current/backend/ruoyi-admin.jar').read_bytes()).hexdigest()}
(BASE/'reserved-check-20260926.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
print(json.dumps(report,ensure_ascii=False))
if report['passed']!=report['total']: raise SystemExit(1)
