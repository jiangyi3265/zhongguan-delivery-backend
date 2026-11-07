"""Apply the verified release; preserve reserved public sites and all live data."""
import gzip, hashlib, json, os, pathlib, shutil, subprocess, sys, tarfile, time, urllib.request
from release_switch import switch_release

BASE=pathlib.Path('/www/qicai')
RELEASE=BASE/'releases/20260926-001'
ARCHIVE=pathlib.Path('/tmp/qicai-upgrade-20260926-001.tar.gz')
CURRENT=BASE/'current'
OLD=CURRENT.resolve(strict=True)
MYSQL=['/www/server/mysql/bin/mysql','--defaults-extra-file=/etc/qicai/mysql-root.cnf','--default-character-set=utf8mb4','-N','-B','qicai_delivery']
if os.geteuid()!=0: sys.exit('Requires root')
if RELEASE.exists(): sys.exit('Release exists; refusing overwrite')
if OLD.parent != BASE/'releases': sys.exit('Unexpected current release')
if len(sys.argv)!=2 or hashlib.sha256(ARCHIVE.read_bytes()).hexdigest()!=sys.argv[1].lower(): sys.exit('Archive checksum mismatch')
nginx_config=pathlib.Path('/www/server/panel/vhost/nginx/qicai-internal.conf')
nginx_original=nginx_config.read_text()
bad_proxy='    location ^~ /qicai/ { proxy_pass http://127.0.0.1:18130; proxy_set_header Host $host; proxy_set_header X-Real-IP $remote_addr; }\n'
if nginx_original.count(bad_proxy)!=2: sys.exit('Unexpected internal nginx configuration; inspect before upgrading')
def sql(query): return subprocess.check_output(MYSQL,input=query,text=True).strip()
before=sql("SELECT 'customers',COUNT(*) FROM qc_customer UNION ALL SELECT 'orders',COUNT(*) FROM qc_order")
backup=BASE/'backups'/('pre-upgrade-'+time.strftime('%Y%m%d-%H%M%S')+'.sql.gz')
dump=subprocess.check_output(['/www/server/mysql/bin/mysqldump','--defaults-extra-file=/etc/qicai/mysql-root.cnf','--single-transaction','--no-tablespaces','--set-gtid-purged=OFF','qicai_delivery'])
backup.write_bytes(gzip.compress(dump)); backup.chmod(0o600)
nginx_backup=backup.with_suffix('.nginx.conf')
shutil.copy2(nginx_config,nginx_backup); nginx_backup.chmod(0o600)
RELEASE.mkdir(mode=0o755)
shutil.copytree(OLD/'deploy',RELEASE/'deploy')
with tarfile.open(ARCHIVE) as tar:
    for member in tar.getmembers():
        target=(RELEASE/member.name).resolve()
        if not target.is_relative_to(RELEASE) or member.issym() or member.islnk() or member.isdev():
            sys.exit('Unsafe archive entry')
    tar.extractall(RELEASE)
for entry in ['backend/ruoyi-admin.jar','admin/index.html','h5/index.html','mp-weixin/app.json','sql/qicai_delivery_upgrade_20260926.sql']:
    if not (RELEASE/entry).is_file(): sys.exit('Missing release artifact: '+entry)
subprocess.run(MYSQL,input=(RELEASE/'sql/qicai_delivery_upgrade_20260926.sql').read_text(),text=True,check=True)
# The admin's /qicai/customers etc. are SPA routes, not backend endpoints.
# Only the first (admin) internal server loses this conflicting proxy.
next_link=BASE/'current-next-20260926'
def healthy_release():
    for _ in range(40):
        try:
            with urllib.request.urlopen('http://127.0.0.1:18130/qicai/mobile/health',timeout=5) as response:
                data=json.load(response).get('data',{})
                if data.get('status')=='UP' and data.get('database')=='UP' and data.get('redis')=='UP': return True
        except Exception: pass
        time.sleep(1)
    return False
switch_release(CURRENT,next_link,RELEASE,OLD,nginx_config,nginx_original,
               nginx_original.replace(bad_proxy,'',1),healthy_release)
after=sql("SELECT 'customers',COUNT(*) FROM qc_customer UNION ALL SELECT 'orders',COUNT(*) FROM qc_order")
report={'release':str(RELEASE),'previous':str(OLD),'backup':str(backup),'nginxBackup':str(nginx_backup),'health':'UP','liveCountsBefore':before,'liveCountsAfter':after,'captcha':sql("SELECT config_value FROM sys_config WHERE config_key='sys.account.captchaEnabled'"),'publicMode':'reserved; public domain configuration unchanged','internalNginx':'admin SPA refresh proxy conflict repaired'}
(BASE/'upgrade-20260926-result.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
print(json.dumps(report,ensure_ascii=False))
