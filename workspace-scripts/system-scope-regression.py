"""System menu/config audit of the owned isolated instance; no production access."""
import datetime, json, pathlib, subprocess, urllib.request, urllib.error, os
BASE = 'http://127.0.0.1:15190'
if os.environ.get('QICAI_ISOLATED_TEST')!='1' or os.environ.get('QICAI_BASE_URL')!=BASE or not os.environ.get('QICAI_EVIDENCE_DIR') or not os.environ.get('QICAI_ADMIN_PASSWORD'):
    raise RuntimeError('Run via the isolated acceptance runner')
OUT = pathlib.Path(os.environ['QICAI_EVIDENCE_DIR'])
results = []
def request(url, body=None, token=None):
    headers = {'Content-Type':'application/json'}
    if token: headers['Authorization'] = 'Bearer ' + token
    req = urllib.request.Request(BASE+url, data=json.dumps(body).encode() if body else None, headers=headers)
    try: response=urllib.request.urlopen(req,timeout=15)
    except urllib.error.HTTPError as error: response=error
    with response: return response.status,json.load(response)
def check(name, passed, detail):
    results.append({'name':name,'passed':bool(passed),'detail':detail})
    print(('PASS ' if passed else 'FAIL ')+name)
try:
    _, login=request('/login',{'username':'admin','password':os.environ['QICAI_ADMIN_PASSWORD']})
    token=login['token']
    http, routers=request('/getRouters',token=token)
    check('隔离种子管理员动态菜单为空，业务入口由固定路由提供',http==200 and routers.get('code')==200 and routers.get('data')==[],{'http':http,'routers':routers.get('data')})
    p=subprocess.run(['mysql','--no-defaults','--protocol=TCP','--host=127.0.0.1','--port=13319','--user=root','--batch','--skip-column-names','qicai_acceptance'],input=b"SELECT COUNT(*) FROM sys_menu; SELECT config_value FROM sys_config WHERE config_key='sys.account.registerUser';",capture_output=True)
    check('真实数据库菜单数0及后台注册关闭',p.returncode==0 and p.stdout.decode().strip().splitlines()==['0','false'],{'exitCode':p.returncode,'rows':p.stdout.decode().strip().splitlines()})
    http,data=request('/register',{'username':'supplemental_admin','password':'NeverCreated123!','confirmPassword':'NeverCreated123!'})
    check('配置关闭时后台注册请求拒绝创建账号',not data.get('token') and data.get('code')==500 and data.get('msg')=='当前系统没有开启注册功能！',{'http':http,'code':data.get('code'),'msg':data.get('msg')})
    p=subprocess.run(['mysql','--no-defaults','--protocol=TCP','--host=127.0.0.1','--port=13319','--user=root','--batch','--skip-column-names','qicai_acceptance'],input=b"SELECT COUNT(*) FROM sys_user WHERE user_name='supplemental_admin';",capture_output=True)
    check('后台注册拒绝后无用户落库',p.returncode==0 and p.stdout.decode().strip()=='0',{'exitCode':p.returncode,'count':p.stdout.decode().strip()})
except Exception as error: check('附加系统检查执行完成',False,str(error))
(OUT/'supplemental-system-results.json').write_text(json.dumps({'at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'kind':'real isolated API and MySQL; no production menu inference','results':results},ensure_ascii=False,indent=2),encoding='utf-8')
raise SystemExit(0 if len(results)==4 and all(r['passed'] for r in results) else 1)
