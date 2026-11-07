"""Isolated Windows acceptance entry point. Never connects to an existing database.

python scripts/acceptance.py --local  # local evidence only; not a release approval
python scripts/acceptance.py          # also applies external release gates (exit 2)
"""
import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import socket
import subprocess as sp
import sys
import tempfile
import time
import urllib.request
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / 'tmp' / 'acceptance-20261004'
SKIP = {'.git', 'node_modules', 'target', 'dist', 'unpackage', '__pycache__'}
REQUIRED_UNITS = {
    'com.ruoyi.qicai.DependencyCompatibilityTest':5,
    'com.ruoyi.framework.web.service.PasswordAccountLockTest':9,
    'com.ruoyi.framework.web.service.TokenAuthorizationTest':9,
    'com.ruoyi.framework.web.service.UploadContentSecurityTest':10,
    'com.ruoyi.qicai.payment.WechatLoginGatewayTest':3,
    'com.ruoyi.qicai.payment.WechatPaymentCryptoTest':8,
    'com.ruoyi.qicai.payment.WechatPaymentGatewayHttpTest':13,
    'com.ruoyi.qicai.QicaiBusinessRemediationTest':4,
    'com.ruoyi.qicai.QicaiCatalogTest':6,
    'com.ruoyi.qicai.QicaiCourierAvailabilityTest':8,
    'com.ruoyi.qicai.QicaiWechatCodeTest':2,
    'com.ruoyi.qicai.RefererFilterTest':5,
    'com.ruoyi.qicai.QicaiTradeBillTest':6,
    'com.ruoyi.qicai.QicaiReconciliationCodecTest':2,
    'com.ruoyi.qicai.QicaiProductOrientationTest':8,
}

def unit_report_summary(files):
    suites={}
    failures=[]
    for file in files:
        suite=ET.parse(file).getroot()
        name=suite.attrib['name']
        if name in suites:raise RuntimeError('Duplicate unit suite report: '+name)
        counts={key:int(suite.attrib.get(key,'0')) for key in ['tests','failures','errors','skipped']}
        suites[name]=counts
        if counts['tests']<=0 or any(counts[key] for key in ['failures','errors','skipped']):
            failures.append('Failed, skipped or empty suite: '+name)
    for name,minimum in REQUIRED_UNITS.items():
        if name not in suites or suites[name]['tests']<minimum:
            failures.append('Required suite absent or below minimum '+str(minimum)+': '+name)
    return {'suites':suites,'totals':{key:sum(value[key] for value in suites.values()) for key in ['tests','failures','errors','skipped']},'failures':failures,'passed':not failures}

def now():
    return dt.datetime.now(dt.timezone.utc).isoformat()

def snapshot(destination):
    files = {}
    for area in ['RuoYi-Vue', 'RuoYi-Vue3', '三端配送小程序', 'scripts', 'deploy']:
        for folder, dirs, names in os.walk(ROOT / area):
            dirs[:] = sorted(d for d in dirs if d not in SKIP)
            for name in sorted(names):
                p = Path(folder) / name
                relative = p.relative_to(ROOT).as_posix()
                files[relative] = hashlib.sha256(p.read_bytes()).hexdigest()
                if destination:
                    target = destination / relative
                    target.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copy2(p, target)
    digest = hashlib.sha256(json.dumps(files, sort_keys=True).encode()).hexdigest()
    return {'at': now(), 'sha256': digest, 'files': files}

def executable(name):
    override = os.environ.get('QICAI_NODE_BIN') if name == 'node' else None
    p = str(Path(override).resolve()) if override else shutil.which(name)
    if override and not Path(p).is_file():
        raise RuntimeError('QICAI_NODE_BIN does not point to an installed Node executable')
    if not p:
        raise RuntimeError('Required executable missing: ' + name)
    return p

class Acceptance:
    def __init__(self, output):
        self.out = output.resolve()
        if self.out.exists() and any(self.out.iterdir()):
            raise RuntimeError('Evidence directory is not empty; choose a fresh --output to preserve earlier results')
        self.out.mkdir(parents=True, exist_ok=True)
        self.records = []
        self.children = []
        self.handles = []
        # Windows MySQL cannot reliably initialize/reopen data dictionaries under
        # non-ASCII checkout paths. Keep its disposable data under the OS temp root.
        self.runtime = Path(tempfile.gettempdir()) / ('qicai-acceptance-' + secrets.token_hex(8))
        (self.out / 'runtime-location.json').write_text(json.dumps({'path':str(self.runtime), 'purpose':'isolated disposable acceptance instance'}), encoding='utf-8')
        self.env = os.environ.copy()
        self.env.update(QICAI_BASE_URL='http://127.0.0.1:15190', QICAI_ISOLATED_TEST='1',
                        QICAI_ADMIN_PASSWORD='QcVerify2026!', QICAI_H5_URL='http://127.0.0.1:25187',
                        QICAI_ADMIN_URL='http://127.0.0.1:25186', QICAI_EVIDENCE_DIR=str(self.out),
                        PYTHONIOENCODING='utf-8', VITE_QICAI_API_BASE='http://127.0.0.1:25187/qicai/mobile')
        # Acceptance never inherits live payment/login switches or contacts the
        # merchant provider. Provider integration suites supply their own doubles.
        self.env.update(QICAI_WECHAT_PAY_ENABLED='false', QICAI_WECHAT_LOGIN_ENABLED='false', QICAI_WECHAT_APP_ID='wx0000000000000000', QICAI_WECHAT_MERCHANT_ID='1900000109')
        self.env['MAVEN_OPTS'] = '-Xmx512m -XX:ActiveProcessorCount=2 -XX:HeapBaseMinAddress=8g'
        if os.environ.get('QICAI_NODE_BIN'):
            self.env['PATH']=str(Path(executable('node')).parent)+os.pathsep+self.env.get('PATH','')

    def run(self, name, command, cwd=ROOT, data=None, timeout=600, env=None):
        print('RUN ' + name, flush=True)
        start = now()
        log = self.out / (name + '.log')
        try:
            with log.open('wb') as f:
                result = sp.run(command, cwd=cwd, env=env or self.env, input=data,
                                stdout=f, stderr=sp.STDOUT, timeout=timeout,
                                creationflags=sp.CREATE_NO_WINDOW if os.name == 'nt' else 0)
            code = result.returncode
        except Exception as e:
            code = -1
            with log.open('a', encoding='utf-8') as f:
                f.write('\n' + str(e))
        # Configuration secrets are passed in stdin/environment, never command or report.
        self.records.append({'name': name, 'command': command, 'cwd': str(cwd),
                             'startedAt': start, 'endedAt': now(), 'exitCode': code, 'log': str(log)})
        self.save()
        print(f'{"PASS" if code == 0 else "FAIL"} {name}: exit {code}', flush=True)
        if code:
            raise RuntimeError(name + ' failed; see ' + str(log))

    def save(self):
        (self.out / 'commands.json').write_text(json.dumps(self.records, ensure_ascii=False, indent=2), encoding='utf-8')

    def spawn(self, name, command, env=None):
        log = (self.out / (name + '.log')).open('wb')
        self.handles.append(log)
        proc = sp.Popen(command, cwd=ROOT, env=env or self.env, stdout=log, stderr=sp.STDOUT,
                        creationflags=sp.CREATE_NO_WINDOW if os.name == 'nt' else 0)
        self.children.append(proc)
        self.records.append({'name': name, 'command': command, 'startedAt': now(), 'pid': proc.pid, 'log': str(self.out / (name + '.log'))})
        self.save()
        return proc

    def port(self, port):
        with socket.socket() as s:
            return s.connect_ex(('127.0.0.1', port)) == 0

    def wait_port(self, port, proc):
        for _ in range(90):
            if proc.poll() is not None:
                raise RuntimeError(f'Service exited before port {port} became ready')
            if self.port(port):
                return
            time.sleep(1)
        raise RuntimeError(f'Port {port} did not start')

    def sql(self, name, source, database=None):
        command = [executable('mysql'), '--no-defaults', '--protocol=TCP', '--host=127.0.0.1',
                   '--port=13319', '--user=root', '--default-character-set=utf8mb4', '--batch', '--raw']
        if database:
            command += [database]
        self.run(name, command, data=source.encode('utf-8'), timeout=120)

    def build(self):
        self.run('package-manager-guard',[executable('node'),'scripts/package-manager-guard.cjs'])
        self.run('runtime-info', [executable('node'), '-e', "console.log(JSON.stringify({node:process.version,libuv:process.versions.uv,platform:process.platform,executable:process.execPath}));if(process.platform==='win32'&&process.version==='v24.15.0'){console.error('Node 24.15.0 native TCP crash reproduced on this host; select a tested installed runtime with QICAI_NODE_BIN. See ACCEPTANCE_GUIDE.md');process.exitCode=1}"])
        self.run('backend-build', [executable('mvn.cmd'), '-B', 'clean', 'package', '-DskipTests=false'], ROOT / 'RuoYi-Vue')
        self.unit_report_gate()
        pnpm_cli=Path(executable('pnpm.cmd')).parent/'node_modules/pnpm/bin/pnpm.mjs'
        if not pnpm_cli.is_file():
            raise RuntimeError('Install the project-declared pnpm version before acceptance')
        self.run('admin-frozen-install',[executable('node'),str(pnpm_cli),'install','--frozen-lockfile'],ROOT/'RuoYi-Vue3')
        self.run('admin-dependency-integrity',[executable('node'),'scripts/admin-dependency-regression.cjs'])
        self.run('mini-frozen-install',[executable('node'),str(pnpm_cli),'install','--frozen-lockfile'],ROOT/'三端配送小程序')
        self.run('client-dependency-integrity',[executable('node'),'scripts/client-dependency-regression.cjs'])
        for name, folder, script in [('admin-build','RuoYi-Vue3','build:prod'), ('h5-build','三端配送小程序','build:h5'), ('wechat-build','三端配送小程序','build:mp-weixin')]:
            build_env=self.env.copy()
            if name=='wechat-build':
                build_env['VITE_QICAI_API_BASE']=os.environ.get('QICAI_WECHAT_API_BASE','https://dingdan.uuok.top/qicai/mobile')
            command=[executable('npm.cmd'), 'run', script]
            if os.environ.get('QICAI_NODE_BIN'):
                npm_cli=Path(executable('npm.cmd')).parent/'node_modules/npm/bin/npm-cli.js'
                if not npm_cli.is_file():
                    raise RuntimeError('Cannot locate the installed npm CLI for the selected Node runtime')
                command=[executable('node'),str(npm_cli),'run',script]
            self.run(name, command, ROOT / folder, env=build_env)
        self.run('sfc-static', [executable('node'), 'scripts/validate-sfc.mjs'])

    def unit_report_gate(self):
        start=now()
        destination=self.out/'unit-reports';destination.mkdir()
        code=1
        try:
            files=list((ROOT/'RuoYi-Vue').glob('*/target/surefire-reports/TEST-*.xml'))
            for file in files:shutil.copy2(file,destination/(file.parents[2].name+'-'+file.name))
            result=unit_report_summary(files)
            (self.out/'unit-results.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
            if not result['passed']:raise RuntimeError('Required unit test gate failed: '+'; '.join(result['failures']))
            code=0
        finally:
            self.records.append({'name':'unit-report-gate','command':[sys.executable,'scripts/acceptance.py','unit_report_gate (internal)'],'startedAt':start,'endedAt':now(),'exitCode':code,'log':str(self.out/'unit-results.json')})
            self.save()

    def start(self):
        for port in [13319,16399,15190,25186,25187]:
            if self.port(port):
                raise RuntimeError(f'Isolated port {port} is occupied; refusing to reuse any existing service')
        if self.runtime.exists():
            raise RuntimeError('Runtime already exists. Use a new --output directory; existing evidence/data are never deleted.')
        self.runtime.mkdir(parents=True)
        mysql_data = self.runtime / 'mysql'
        self.run('mysql-initialize', [executable('mysqld'), '--no-defaults', '--initialize-insecure', '--datadir=' + str(mysql_data)], timeout=120)
        p = self.spawn('mysql', [executable('mysqld'), '--no-defaults', '--console', '--datadir=' + str(mysql_data), '--port=13319', '--bind-address=127.0.0.1', '--mysqlx=OFF', '--max-connections=60', '--innodb-buffer-pool-size=64M'])
        self.wait_port(13319, p)
        self.sql('database-create', 'CREATE DATABASE qicai_acceptance CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;')
        self.sql('database-ruoyi', (ROOT / 'RuoYi-Vue/sql/ry_20250522.sql').read_text(encoding='utf-8'), 'qicai_acceptance')
        sql = (ROOT / 'RuoYi-Vue/sql/qicai_delivery.sql').read_text(encoding='utf-8')
        sql = re.sub(r'CREATE DATABASE IF NOT EXISTS qicai_delivery.*?;', '', sql, flags=re.S).replace('USE qicai_delivery;', '')
        self.sql('database-business', sql, 'qicai_acceptance')
        migration = (ROOT / 'RuoYi-Vue/sql/qicai_delivery_upgrade_20260926.sql').read_text(encoding='utf-8')
        self.sql('database-migration', migration, 'qicai_acceptance')
        self.sql('database-migration-repeat', migration, 'qicai_acceptance')
        payment_migration = (ROOT / 'RuoYi-Vue/sql/qicai_payment_upgrade_20261007.sql').read_text(encoding='utf-8')
        self.sql('database-payment-migration', payment_migration, 'qicai_acceptance')
        self.sql('database-payment-migration-repeat', payment_migration, 'qicai_acceptance')
        workflow_migration = (ROOT / 'RuoYi-Vue/sql/qicai_workflow_upgrade_20261015.sql').read_text(encoding='utf-8')
        self.sql('database-workflow-migration', workflow_migration, 'qicai_acceptance')
        self.sql('database-workflow-migration-repeat', workflow_migration, 'qicai_acceptance')
        storefront_migration = (ROOT / 'RuoYi-Vue/sql/qicai_storefront_upgrade_20261017.sql').read_text(encoding='utf-8')
        self.sql('database-storefront-migration', storefront_migration, 'qicai_acceptance')
        self.sql('database-storefront-migration-repeat', storefront_migration, 'qicai_acceptance')
        remediation_migration = (ROOT / 'RuoYi-Vue/sql/qicai_commercial_remediation_upgrade_20261017.sql').read_text(encoding='utf-8')
        self.sql('database-commercial-remediation-migration', remediation_migration, 'qicai_acceptance')
        self.sql('database-commercial-remediation-migration-repeat', remediation_migration, 'qicai_acceptance')
        extension_migration = (ROOT / 'RuoYi-Vue/sql/qicai_extensions_upgrade_20261018.sql').read_text(encoding='utf-8')
        self.sql('database-extension-migration', extension_migration, 'qicai_acceptance')
        self.sql('database-extension-migration-repeat', extension_migration, 'qicai_acceptance')
        brand_migration = (ROOT / 'RuoYi-Vue/sql/qicai_brand_upgrade_20261019.sql').read_text(encoding='utf-8')
        self.sql('database-brand-migration', brand_migration, 'qicai_acceptance')
        self.sql('database-brand-migration-repeat', brand_migration, 'qicai_acceptance')
        unit_migration = (ROOT / 'RuoYi-Vue/sql/qicai_product_unit_upgrade_20261031.sql').read_text(encoding='utf-8')
        self.sql('database-unit-migration', unit_migration, 'qicai_acceptance')
        self.sql('database-unit-migration-repeat', unit_migration, 'qicai_acceptance')
        warehouse_migration = (ROOT / 'RuoYi-Vue/sql/qicai_forward_warehouse_upgrade_20261101.sql').read_text(encoding='utf-8')
        self.sql('database-warehouse-migration', warehouse_migration, 'qicai_acceptance')
        self.sql('database-warehouse-migration-repeat', warehouse_migration, 'qicai_acceptance')
        sales_migration=(ROOT/'RuoYi-Vue/sql/qicai_sales_names_upgrade_20261102.sql').read_text(encoding='utf8')
        self.sql('sales-names-migration',sales_migration,'qicai_acceptance')
        self.sql('sales-names-migration-repeat',sales_migration,'qicai_acceptance')
        sys.path.insert(0, str(ROOT/'tmp/acceptance-20261004/python-deps'))
        import bcrypt
        hashed = bcrypt.hashpw(self.env['QICAI_ADMIN_PASSWORD'].encode(), bcrypt.gensalt()).decode()
        self.sql('database-test-fixtures', f"UPDATE sys_user SET password='{hashed}' WHERE user_id=1; UPDATE sys_config SET config_value='false' WHERE config_key='sys.account.captchaEnabled';", 'qicai_acceptance')
        p = self.spawn('redis', [executable('redis-server'), '--port', '16399', '--bind', '127.0.0.1', '--save', '', '--appendonly', 'no', '--dir', str(self.runtime)])
        self.wait_port(16399, p)
        self.env.update(QICAI_DB_URL='jdbc:mysql://127.0.0.1:13319/qicai_acceptance?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai',
                        QICAI_DB_USERNAME='root', QICAI_DB_PASSWORD='', QICAI_TOKEN_SECRET=secrets.token_urlsafe(48),
                        QICAI_SMS_DEV_MODE='true', QICAI_LOG_PATH=str(self.runtime / 'logs'), QICAI_RUNTIME=str(self.runtime))
        # This shared 32-core Windows host exhausted native compiler memory in a
        # recorded run. Bound our disposable JVM worker count and keep its heap
        # above the low native address region; this is not a capacity benchmark.
        java_isolation = ['-XX:ActiveProcessorCount=2', '-XX:HeapBaseMinAddress=8g'] if os.name == 'nt' else []
        # Run a verified private copy so a Windows investigation JVM does not
        # hold the build output open during the next authorized correction.
        source_jar = ROOT / 'RuoYi-Vue/ruoyi-admin/target/ruoyi-admin.jar'
        runtime_jar = self.runtime / 'backend.jar'
        shutil.copy2(source_jar, runtime_jar)
        jar_digest = hashlib.sha256(source_jar.read_bytes()).hexdigest()
        if hashlib.sha256(runtime_jar.read_bytes()).hexdigest() != jar_digest:
            raise RuntimeError('Isolated runtime JAR copy differs from build output')
        (self.out / 'backend-artifact.json').write_text(json.dumps({'source':str(source_jar),'runtime':str(runtime_jar),'sha256':jar_digest}),encoding='utf-8')
        p = self.spawn('backend', [executable('java'), *java_isolation, '-Xms128m', '-Xmx512m', '-jar', str(runtime_jar),
                                 '--server.address=127.0.0.1', '--server.port=15190', '--spring.redis.host=127.0.0.1', '--spring.redis.port=16399',
                                 '--ruoyi.profile=' + str(self.runtime / 'uploads'), '--logging.level.com.ruoyi=info', '--spring.devtools.restart.enabled=false'])
        self.wait_port(15190, p)
        for _ in range(60):
            try:
                with urllib.request.urlopen('http://127.0.0.1:15190/qicai/mobile/health', timeout=3) as r:
                    if json.load(r).get('data',{}).get('status') == 'UP':
                        break
            except Exception:
                time.sleep(1)
        else:
            raise RuntimeError('Backend health did not become UP')
        p = self.spawn('preview', [executable('node'), 'scripts/serve-production.cjs'])
        self.wait_port(25186, p)
        self.wait_port(25187, p)
        print('READY isolated API 15190, admin 25186, H5 25187', flush=True)

    def payment_tests(self):
        self.run('workflow-migration-upgrade',[sys.executable,'scripts/workflow-migration-regression.py'])
        self.run('payment-migration-upgrade',[sys.executable,'scripts/payment-migration-regression.py'])
        storefront_xml=ROOT/'RuoYi-Vue/ruoyi-admin/target/failsafe-reports/TEST-com.ruoyi.qicai.QicaiStorefrontIT.xml'
        try:
            self.run('storefront-integration',[executable('mvn.cmd'),'-B','-pl','ruoyi-admin','-am','test-compile',
                'org.apache.maven.plugins:maven-failsafe-plugin:2.22.2:integration-test','org.apache.maven.plugins:maven-failsafe-plugin:2.22.2:verify',
                '-Dit.test=QicaiStorefrontIT','-DfailIfNoTests=false','-Dfailsafe.failIfNoSpecifiedTests=false','-DargLine=-Xmx512m -XX:ActiveProcessorCount=2 -XX:HeapBaseMinAddress=8g','-Druoyi.profile='+str(self.runtime/'uploads')],ROOT/'RuoYi-Vue')
        finally:
            if storefront_xml.exists(): shutil.copy2(storefront_xml,self.out/'storefront-integration.xml')
        storefront=ET.parse(storefront_xml).getroot()
        if int(storefront.attrib.get('tests','0'))<17 or any(int(storefront.attrib.get(k,'0')) for k in ['failures','errors','skipped']):
            raise RuntimeError('Required storefront integration checks missing, skipped or failed')
        try:
            self.run('payment-integration',[executable('mvn.cmd'),'-B','-pl','ruoyi-admin','-am','test-compile',
                'org.apache.maven.plugins:maven-failsafe-plugin:2.22.2:integration-test','org.apache.maven.plugins:maven-failsafe-plugin:2.22.2:verify',
                '-Dit.test=WechatPaymentIT','-DfailIfNoTests=false','-Dfailsafe.failIfNoSpecifiedTests=false','-DargLine=-Xmx512m -XX:ActiveProcessorCount=2 -XX:HeapBaseMinAddress=8g','-Druoyi.profile='+str(self.runtime/'uploads')],ROOT/'RuoYi-Vue')
        finally:
            for folder in ['surefire-reports','failsafe-reports']:
                source=ROOT/'RuoYi-Vue/ruoyi-admin/target'/folder
                if source.exists():shutil.copytree(source,self.out/folder,dirs_exist_ok=True)
        xml=ROOT/'RuoYi-Vue/ruoyi-admin/target/failsafe-reports/TEST-com.ruoyi.qicai.payment.WechatPaymentIT.xml'
        report=ET.parse(xml).getroot()
        counts={k:int(report.attrib.get(k,'0')) for k in ['tests','failures','errors','skipped']}
        if counts['tests']<43 or any(counts[k] for k in ['failures','errors','skipped']):
            raise RuntimeError('Payment integration report missing, skipped or failed')
        units=[]
        for name,minimum in [('WechatPaymentCryptoTest',8),('WechatPaymentGatewayHttpTest',13)]:
            suite=ET.parse(self.out/'surefire-reports'/('TEST-com.ruoyi.qicai.payment.'+name+'.xml')).getroot()
            unit={k:int(suite.attrib.get(k,'0')) for k in ['tests','failures','errors','skipped']}
            if unit['tests']<minimum or any(unit[k] for k in ['failures','errors','skipped']):
                raise RuntimeError('Required payment unit checks missing, skipped or failed: '+name)
            units.append({'suite':name,**unit})
        self.run('payment-client',[executable('node'),'scripts/payment-client-regression.mjs'])
        self.run('session-client',[executable('node'),'scripts/session-client-regression.mjs'])
        self.run('native-client',[executable('node'),'scripts/native-client-regression.mjs'])
        self.run('storefront-client',[executable('node'),'scripts/storefront-client-regression.mjs'])
        self.run('storefront-pages',[executable('node'),'三端配送小程序/scripts/storefront-session-regression.mjs'])
        self.run('native-header',[executable('node'),'三端配送小程序/scripts/native-header-regression.cjs'])
        self.run('admin-session-client',[executable('node'),'scripts/admin-session-client-regression.mjs'])
        self.run('payment-browser',[executable('node'),'scripts/payment-browser-regression.cjs'])
        (self.out/'payment-results.json').write_text(json.dumps({'kind':'real HTTP/MySQL + synthetic provider; unit local SDK HTTP; Chromium H5/admin; not real WeChat','integrationCounts':counts,'unitCounts':units,'local':'已实测通过'},ensure_ascii=False,indent=2),encoding='utf-8')
        return counts

    def remediation_integrations(self):
        suites={'com.ruoyi.qicai.QicaiCourierAvailabilityIT':2,
                'com.ruoyi.qicai.QicaiBusinessRemediationIT':12,
                'com.ruoyi.qicai.payment.WechatPaymentPoolIT':1}
        try:
            self.run('remediation-integration',[executable('mvn.cmd'),'-B','-pl','ruoyi-admin','-am','test-compile',
                'org.apache.maven.plugins:maven-failsafe-plugin:2.22.2:integration-test','org.apache.maven.plugins:maven-failsafe-plugin:2.22.2:verify',
                '-Dit.test='+','.join(name.rsplit('.',1)[1] for name in suites),'-DfailIfNoTests=false','-Dfailsafe.failIfNoSpecifiedTests=false',
                '-DargLine=-Xmx512m -XX:ActiveProcessorCount=2 -XX:HeapBaseMinAddress=8g','-Druoyi.profile='+str(self.runtime/'uploads')],ROOT/'RuoYi-Vue')
        finally:
            for name in suites:
                source=ROOT/'RuoYi-Vue/ruoyi-admin/target/failsafe-reports'/('TEST-'+name+'.xml')
                if source.exists():shutil.copy2(source,self.out/source.name)
        counts=[]
        for name,minimum in suites.items():
            suite=ET.parse(self.out/('TEST-'+name+'.xml')).getroot()
            count={key:int(suite.attrib.get(key,'0')) for key in ['tests','failures','errors','skipped']}
            if count['tests']<minimum or any(count[key] for key in ['failures','errors','skipped']):
                raise RuntimeError('Required remediation integration tests missing, failed or skipped: '+name)
            counts.append({'suite':name,**count})
        (self.out/'remediation-integration-results.json').write_text(json.dumps({'kind':'actual isolated HTTP/MySQL/Redis; controlled synthetic provider for pool concurrency, no real payment','suites':counts},ensure_ascii=False,indent=2),encoding='utf-8')

    def tests(self, include_payment=True):
        failures=[]
        def execute(name, command):
            try:
                self.run(name,command)
            except Exception as e:
                failures.append(str(e))
        for script in ['authorization-remediation','security-remediation','upload-batch-regression','remediation-migration-regression','alert-regression']:
            execute(script,[sys.executable,'scripts/'+script+'.py'])
        for script in ['upload-security-browser','session-product-boundary','product-detail-boundary-browser','frontend-remediation-client','frontend-monitor-remediation-client','frontend-task-lifecycle-client',
                       'frontend-location-client','frontend-pagination-client','frontend-pagination-shrink-client',
                       'frontend-remediation-browser','frontend-pagination-browser','frontend-courier-pagination-browser','frontend-monitor-browser','admin-editor-browser']:
            execute(script,[executable('node'),'scripts/'+script+'.cjs'])
        try:self.remediation_integrations()
        except Exception as e:failures.append(str(e))
        for name, script in [('commerce-browser', 'commerce-regression.cjs'), ('checkout-amount-browser', 'checkout-amount-browser.cjs'), ('task-lifecycle', 'task-lifecycle-browser-regression.cjs'), ('catalog-browser', 'catalog-browser-regression.cjs'), ('search-input-browser', 'search-input-browser-regression.cjs'), ('checkout-input-browser', 'checkout-input-browser-regression.cjs'), ('workflow-input-browser', 'workflow-input-browser-regression.cjs'), ('api', 'api-acceptance.mjs'), ('edge','edge-acceptance.mjs'), ('ui','ui-acceptance.cjs'), ('ui-second','ui-second-pass.cjs')]:
            execute(name, [executable('node'), 'scripts/' + script])
        for script in ['backend-regression.mjs', 'frontend-regression.mjs', 'security-regression.mjs', 'extended-api.mjs', 'frontend-browser-regression.cjs','coverage-ui-regression.cjs','mobile-boundary-regression.cjs','ui-combination-regression.cjs','login-storage-regression.cjs']:
            execute(script.split('.')[0], [executable('node'), 'scripts/' + script])
        for script in ['backend-concurrency','session-regression','login-reset-regression','system-scope-regression','coverage-gap-regression','business-boundary-regression','framework-boundary-regression','exception-regression','database-acceptance','release-regression']:
            if script=='database-acceptance' and include_payment:
                try: self.payment_tests()
                except Exception as e: failures.append(str(e))
            execute(script, [sys.executable, 'scripts/'+script+'.py'])
        execute('catalog-integration', [executable('mvn.cmd'), '-B', '-f', str(ROOT/'RuoYi-Vue/pom.xml'), '-pl', 'ruoyi-admin', '-am', 'test-compile', 'org.apache.maven.plugins:maven-failsafe-plugin:2.22.2:integration-test', 'org.apache.maven.plugins:maven-failsafe-plugin:2.22.2:verify', '-Dit.test=QicaiCatalogIT', '-DfailIfNoTests=false', '-Dfailsafe.failIfNoSpecifiedTests=false', '-DargLine=-Xmx512m -XX:ActiveProcessorCount=2 -XX:HeapBaseMinAddress=8g', '-Druoyi.profile='+str(self.runtime/'uploads')])
        catalog_xml=ROOT/'RuoYi-Vue/ruoyi-admin/target/failsafe-reports/TEST-com.ruoyi.qicai.QicaiCatalogIT.xml'
        if catalog_xml.exists():
            shutil.copy2(catalog_xml,self.out/'catalog-integration.xml')
            suite=ET.parse(catalog_xml).getroot()
            if int(suite.attrib.get('tests','0'))<6 or any(int(suite.attrib.get(k,'0')) for k in ['failures','errors','skipped']):failures.append('Required catalog integration tests failed or skipped')
        else:failures.append('Catalog integration report missing')
        execute('reconciliation-money-client', [executable('node'), 'scripts/reconciliation-money-client.mjs'])
        execute('notice-client', [executable('node'), 'scripts/notice-client-regression.mjs'])
        execute('extensions-browser', [executable('node'), 'scripts/extensions-browser.cjs'])
        execute('notice-pagination-browser', [executable('node'), 'scripts/notice-pagination-browser.cjs'])
        execute('brand-storefront-browser', [executable('node'), 'scripts/brand-storefront-browser.cjs'])
        execute('brand-migration', [sys.executable, 'scripts/brand-migration-regression.py'])
        execute('extensions-migration', [sys.executable, 'scripts/extensions-migration-regression.py'])
        execute('inventory', [executable('node'), 'scripts/acceptance-inventory.mjs'])
        for name,check in [('production-mode',self.production_mode_tests),('dependency-failure',self.dependency_failure_tests)]:
            start=now()
            code=0
            try:
                check()
            except Exception as e:
                code=1
                failures.append(str(e))
            self.records.append({'name':name,'command':['python','scripts/acceptance.py',name+' (internal)'],'startedAt':start,'endedAt':now(),'exitCode':code,'log':str(self.out/(name+'-results.json'))})
            self.save()
        if failures:
            raise RuntimeError('Required acceptance failures: '+'; '.join(failures))

    def production_mode_tests(self):
        record=next(r for r in self.records if r['name']=='backend')
        child=next(c for c in self.children if c.pid==record['pid'])
        self.terminate(child)
        self.sql('production-captcha-config', "UPDATE sys_config SET config_value='true' WHERE config_key='sys.account.captchaEnabled';", 'qicai_acceptance')
        with socket.create_connection(('127.0.0.1',16399),timeout=5) as client:
            client.sendall(b'*2\r\n$6\r\nSELECT\r\n$2\r\n15\r\n')
            client.recv(1024)
            key=b'sys_config:sys.account.captchaEnabled'
            client.sendall(b'*2\r\n$3\r\nDEL\r\n$'+str(len(key)).encode()+b'\r\n'+key+b'\r\n')
            client.recv(1024)
        prod_env=self.env.copy()
        prod_env['QICAI_SMS_DEV_MODE']='false'
        p=self.spawn('backend-production-mode',record['command'],prod_env)
        self.wait_port(15190,p)
        def call(url,body=None,token=None):
            headers={'Content-Type':'application/json'}
            if token:
                headers['Authorization']='Bearer '+token
            req=urllib.request.Request('http://127.0.0.1:15190'+url,data=json.dumps(body).encode() if body else None,headers=headers)
            try:
                response=urllib.request.urlopen(req,timeout=30)
            except urllib.error.HTTPError as e:
                response=e
            with response:
                return response.status,json.load(response)
        results=[]
        http,data=call('/qicai/mobile/health')
        results.append({'name':'同库同文件目录重启后依赖仍真实UP','passed':http==200 and data.get('data',{}).get('status')=='UP','http':http})
        http,data=call('/qicai/mobile/auth/sms',{'audience':'consumer','phone':'15800000123'})
        results.append({'name':'关闭开发短信后未接供应商返回503且不暴露开发码','passed':http==503 and not data.get('data',{}).get('devCode'),'http':http})
        http,data=call('/captchaImage')
        results.append({'name':'隔离生产配置真实启用后台图形验证码','passed':http==200 and data.get('captchaEnabled') is True and bool(data.get('img')),'http':http})
        http,data=call('/login',{'username':'admin','password':self.env['QICAI_ADMIN_PASSWORD'],'code':'','uuid':''})
        results.append({'name':'启用图形验证码后无验证码登录不发Token','passed':not data.get('token') and data.get('code')!=200,'http':http,'code':data.get('code')})
        # Read the synthetic server-generated answer from our own isolated Redis.
        # This tests the real captcha contract; it does not claim human image solving.
        def captcha_answer(uuid):
            with socket.create_connection(('127.0.0.1',16399),timeout=5) as client:
                stream=client.makefile('rb')
                def command(*parts):
                    encoded=[str(part).encode() for part in parts]
                    client.sendall(b'*'+str(len(encoded)).encode()+b'\r\n'+b''.join(b'$'+str(len(part)).encode()+b'\r\n'+part+b'\r\n' for part in encoded))
                    line=stream.readline()
                    if line.startswith(b'+'):
                        return line[1:-2].decode()
                    if line.startswith(b'$'):
                        size=int(line[1:-2])
                        if size<0:
                            return None
                        value=stream.read(size);stream.read(2)
                        return value.decode()
                    raise RuntimeError('Unexpected isolated Redis response')
                if command('SELECT',15)!='OK':
                    raise RuntimeError('Isolated captcha Redis selection failed')
                answer=command('GET','captcha_codes:'+uuid)
                return str(json.loads(answer)) if answer else None
        http,image=call('/captchaImage')
        answer=captcha_answer(image['uuid'])
        http,invalid=call('/login',{'username':'admin','password':self.env['QICAI_ADMIN_PASSWORD'],'code':'wrong-isolated-captcha','uuid':image['uuid']})
        results.append({'name':'真实验证码错误拒绝且验证码消费后不可重用','passed':not invalid.get('token') and invalid.get('code')!=200 and captcha_answer(image['uuid']) is None,'http':http,'code':invalid.get('code')})
        http,image=call('/captchaImage')
        answer=captcha_answer(image['uuid'])
        http,login=call('/login',{'username':'admin','password':self.env['QICAI_ADMIN_PASSWORD'],'code':answer,'uuid':image['uuid']})
        token=login.get('token')
        info_http,info=call('/getInfo',token=token)
        routers_http,routers=call('/getRouters',token=token)
        results.append({'name':'真实有效验证码登录并读取管理员身份与隔离种子固定路由配置','passed':http==200 and bool(token) and info_http==200 and info.get('code')==200 and info.get('user',{}).get('userId')==1 and 'admin' in info.get('roles',[]) and routers_http==200 and routers.get('code')==200 and routers.get('data')==[] and captcha_answer(image['uuid']) is None,'http':http,'getInfoHttp':info_http,'getRoutersHttp':routers_http,'adminUserId':info.get('user',{}).get('userId'),'adminRole': 'admin' in info.get('roles',[]),'dynamicRouterCount':len(routers.get('data',[]))})
        logout_http,logout=call('/logout',body={},token=token)
        old_http,old=call('/getInfo',token=token)
        results.append({'name':'验证码登录后的后台注销撤销旧JWT','passed':logout_http==200 and logout.get('code')==200 and old_http==401,'logoutHttp':logout_http,'oldJwtHttp':old_http})
        (self.out/'production-mode-results.json').write_text(json.dumps({'results':results},ensure_ascii=False,indent=2),encoding='utf-8')
        if not all(r['passed'] for r in results):
            raise RuntimeError('Production-mode isolation checks failed')

    def dependency_failure_tests(self):
        results=[]
        for name in ['redis','mysql']:
            record=next(r for r in self.records if r['name']==name)
            child=next(c for c in self.children if c.pid==record['pid'])
            self.terminate(child)
            start=now()
            try:
                try:
                    response=urllib.request.urlopen('http://127.0.0.1:15190/qicai/mobile/health',timeout=55)
                except urllib.error.HTTPError as e:
                    response=e
                with response:
                    data=json.load(response)
                    passed=response.status==503 and data.get('data',{}).get('status')=='DOWN' and data.get('data',{}).get('database' if name=='mysql' else 'redis')=='DOWN'
                    results.append({'name':name+'停止后就绪检查必须503/DOWN','passed':passed,'http':response.status,'body':data,'startedAt':start,'endedAt':now()})
            except Exception as e:
                results.append({'name':name+'停止后就绪检查必须503/DOWN','passed':False,'error':str(e)})
        (self.out/'dependency-failure-results.json').write_text(json.dumps({'results':results},ensure_ascii=False,indent=2),encoding='utf-8')
        if not all(x['passed'] for x in results):
            raise RuntimeError('Dependency failure readiness checks failed')

    def stop(self):
        for child in reversed(self.children):
            self.terminate(child)
            for rec in self.records:
                if rec.get('pid') == child.pid:
                    rec['endedAt'] = now()
                    rec['exitCode'] = child.returncode
                    rec['stoppedByHarness'] = True
        for f in self.handles:
            f.close()
        self.save()

    def terminate(self, child):
        if child.poll() is None:
            # Windows mysqld uses a launcher plus a server child. Killing only the
            # launcher leaves a live database behind; target this owned process tree.
            if os.name == 'nt':
                sp.run(['taskkill','/PID',str(child.pid),'/T','/F'],stdout=sp.DEVNULL,stderr=sp.DEVNULL,timeout=15)
            else:
                child.terminate()
            try:
                child.wait(timeout=15)
            except sp.TimeoutExpired:
                child.kill()
                child.wait(timeout=5)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--local', action='store_true')
    parser.add_argument('--serve', action='store_true', help='prepare isolated services for investigation; stop on stdin EOF')
    parser.add_argument('--skip-build', action='store_true', help='investigation only; not permitted in final acceptance')
    parser.add_argument('--output', type=Path, default=OUT / ('run-' + dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%SZ')))
    parser.add_argument('--snapshot-only', action='store_true')
    args = parser.parse_args()
    OUT.mkdir(parents=True, exist_ok=True)
    if args.snapshot_only:
        dest = OUT / 'initial-source'
        if dest.exists():
            raise RuntimeError('Initial source already preserved')
        (OUT / 'initial-manifest.json').write_text(json.dumps(snapshot(dest), indent=2, ensure_ascii=False), encoding='utf-8')
        for repo in ['RuoYi-Vue','RuoYi-Vue3']:
            result = sp.run(['git','-C',str(ROOT/repo),'status','--short'], capture_output=True)
            (OUT / (repo + '-initial-status.txt')).write_bytes(result.stdout)
        return 0
    if args.skip_build and not args.serve:
        parser.error('--skip-build is only for --serve investigation')
    job = Acceptance(args.output)
    status = {'startedAt':now(), 'environment':'Windows; isolated loopback MySQL/Redis; Chromium H5/admin; development SMS only',
              'local':'未测试', 'release':'环境阻塞', 'externalGates':['正式短信按用户要求暂缓，注册不可正式使用','真实微信收款/关单/退款与商户通道核验（用户暂停）','小程序合法域名原生请求/当前版本真机','真实地图隐私授权/选点/导航','真实告警渠道送达与Linux/systemd','本版本生产部署/恢复证据','运营协议与正式业务资料']}
    initial_source = snapshot(None)
    (job.out / 'source-manifest.json').write_text(json.dumps(initial_source, ensure_ascii=False, indent=2), encoding='utf-8')
    code = 1
    try:
        if not args.skip_build:
            job.build()
        job.start()
        if args.serve:
            for line in sys.stdin:
                if line.strip() == 'stop':
                    break
            status['local'] = '未测试'
            code = 0
        else:
            job.tests()
            status['local'] = '已实测通过'
            code = 0 if args.local else 2
    except Exception as e:
        status['local'] = '失败'
        status['error'] = str(e)
        print(str(e), file=sys.stderr, flush=True)
    finally:
        job.stop()
        final_source=snapshot(None)
        (job.out/'source-manifest-end.json').write_text(json.dumps(final_source,ensure_ascii=False,indent=2),encoding='utf-8')
        status['sourceUnchanged']=final_source['sha256']==initial_source['sha256']
        if not args.serve and not status['sourceUnchanged']:
            code=1
            status['local']='失败'
            status['sourceError']='验收期间源码变化，不能认证最终版本'
        status.update(endedAt=now(), exitCode=code, investigationOnly=args.serve)
        (job.out / 'summary.json').write_text(json.dumps(status, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(status, ensure_ascii=False), flush=True)
    return code

if __name__ == '__main__':
    sys.exit(main())
