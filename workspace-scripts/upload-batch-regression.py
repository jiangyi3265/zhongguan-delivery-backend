"""Real isolated HTTP and filesystem batch-upload rollback regression."""
import datetime as dt, hashlib, json, os, secrets, shutil, struct, subprocess, urllib.request, zlib
from pathlib import Path
assert os.environ.get('QICAI_ISOLATED_TEST')=='1' and os.environ.get('QICAI_BASE_URL')=='http://127.0.0.1:15190'
base=os.environ['QICAI_BASE_URL'];runtime=Path(os.environ['QICAI_RUNTIME']).resolve();out=Path(os.environ['QICAI_EVIDENCE_DIR']);out.mkdir(parents=True,exist_ok=True)
destination=out/'upload-batch-results.json'
if destination.exists(): raise RuntimeError('Refuse to overwrite evidence')
mysql=[shutil.which('mysql'),'--no-defaults','--protocol=TCP','--host=127.0.0.1','--port=13319','--user=root','--batch','--skip-column-names','-e','SELECT @@datadir']
actual=Path(subprocess.check_output(mysql,encoding='utf-8',creationflags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0).strip()).resolve()
assert actual==runtime/'mysql' and runtime.name.startswith('qicai-acceptance-')
report={'startedAt':dt.datetime.now(dt.timezone.utc).isoformat(),'kind':__doc__,'checks':[],'isolationDatadirVerified':True}
def request(route,body,content_type='application/json',token=None):
    headers={'Content-Type':content_type,'Referer':'http://localhost:25186/'}
    if token: headers['Authorization']='Bearer '+token
    with urllib.request.urlopen(urllib.request.Request(base+route,data=body,headers=headers),timeout=20) as response:return json.load(response)
def check(name,value):
    report['checks'].append({'name':name,'passed':bool(value)});print(('PASS ' if value else 'FAIL ')+name,flush=True);assert value,name
def files():return {str(p.relative_to(runtime/'uploads')):hashlib.sha256(p.read_bytes()).hexdigest() for p in (runtime/'uploads').rglob('*') if p.is_file()}
chunk=lambda name,data:struct.pack('!I',len(data))+name+data+struct.pack('!I',zlib.crc32(name+data)&0xffffffff)
png=b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('!2I5B',1,1,8,2,0,0,0))+chunk(b'IDAT',zlib.compress(b'\x00\xff\x00\x00'))+chunk(b'IEND',b'')
def upload(parts,token):
    boundary='BatchRegression'+secrets.token_hex(8)
    body=b''.join((f'--{boundary}\r\nContent-Disposition: form-data; name="files"; filename="{name}"\r\nContent-Type: {mime}\r\n\r\n').encode()+content+b'\r\n' for name,mime,content in parts)+f'--{boundary}--\r\n'.encode()
    return request('/common/uploads',body,'multipart/form-data; boundary='+boundary,token)
try:
    token=request('/login',json.dumps({'username':'admin','password':os.environ['QICAI_ADMIN_PASSWORD']}).encode())['token']
    before=files();good=('valid.png','image/png',png);bad=('active.html','text/html',b'<html><script>window.synthetic=true</script></html>')
    response=upload([good,bad],token)
    report['mixedResponse']={'code':response.get('code'),'returnedFileNames':bool(response.get('fileNames'))}
    report['unexpectedWrittenFiles']=[name for name in files() if name not in before]
    check('合法图在前且非法HTML在后时整体拒绝并零落盘',response.get('code')!=200 and files()==before)
    response=upload([bad,good],token)
    check('非法项在前的批次同样拒绝且零落盘',response.get('code')!=200 and files()==before)
    response=upload([good,('document.pdf','application/pdf',b'%PDF-1.4 synthetic')],token)
    names=response.get('fileNames','').split(',')
    expected=[png,b'%PDF-1.4 synthetic']
    check('全部合法批次返回两条实际文件且字节一致',response.get('code')==200 and len(names)==2
          and all((runtime/'uploads'/name.removeprefix('/profile/')).read_bytes()==value for name,value in zip(names,expected)))
    report['exitCode']=0
except Exception as error:report.update(exitCode=1,errorType=type(error).__name__,error=str(error))
finally:
    report['endedAt']=dt.datetime.now(dt.timezone.utc).isoformat();destination.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
raise SystemExit(report['exitCode'])
