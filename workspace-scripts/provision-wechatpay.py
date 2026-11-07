"""Install user-supplied merchant material OUTSIDE the repository. APIv3 key via stdin only."""
import argparse
import datetime as dt
import getpass
import json
import os
from pathlib import Path
import subprocess
import sys
import zipfile
from cryptography import x509
from cryptography.hazmat.primitives import serialization
from cryptography.x509.oid import NameOID

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--certificate-zip', required=True, type=Path)
    parser.add_argument('--public-key', required=True, type=Path)
    parser.add_argument('--public-key-id', required=True)
    parser.add_argument('--destination', required=True, type=Path)
    args=parser.parse_args()
    root=Path(__file__).resolve().parent.parent
    destination=args.destination.resolve()
    if destination.is_relative_to(root) or destination.exists():
        raise ValueError('Use a fresh private directory outside the repository')
    with zipfile.ZipFile(args.certificate_zip) as archive:
        cert_bytes=archive.read('apiclient_cert.pem')
        key_bytes=archive.read('apiclient_key.pem')
    cert=x509.load_pem_x509_certificate(cert_bytes)
    private=serialization.load_pem_private_key(key_bytes,None)
    public_bytes=args.public_key.read_bytes()
    platform=serialization.load_pem_public_key(public_bytes)
    if cert.public_key().public_numbers()!=private.public_key().public_numbers() or platform.key_size!=2048:
        raise ValueError('Certificate/key mismatch or unexpected platform key')
    now=dt.datetime.now(dt.timezone.utc)
    if not cert.not_valid_before_utc<=now<cert.not_valid_after_utc:
        raise ValueError('Merchant certificate is not currently valid')
    merchant=cert.subject.get_attributes_for_oid(NameOID.COMMON_NAME)[0].value
    print('Provide APIv3 key through stdin (never logged):',flush=True)
    api_key=(getpass.getpass('APIv3 key: ') if sys.stdin.isatty() else sys.stdin.readline()).strip()
    if len(api_key.encode('utf-8'))!=32:
        raise ValueError('APIv3 key must contain exactly 32 bytes')
    destination.mkdir(parents=True)
    if os.name=='nt':
        principal=os.environ.get('USERDOMAIN','')+'\\'+os.environ.get('USERNAME','')
        subprocess.run(['icacls',str(destination),'/inheritance:r','/grant:r',principal+':(OI)(CI)F','*S-1-5-18:(OI)(CI)F'],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    else: destination.chmod(0o700)
    for name,value in [('apiclient_cert.pem',cert_bytes),('apiclient_key.pem',key_bytes),('wechatpay-public.pem',public_bytes),('api-v3.key',api_key.encode())]:
        target=destination/name
        target.write_bytes(value)
        if os.name!='nt': target.chmod(0o600)
    serial=format(cert.serial_number,'X')
    properties={
        'qicai.wechat-pay.enabled':'false', 'qicai.wechat-pay.merchant-id':merchant,
        'qicai.wechat-pay.merchant-serial-number':serial,
        'qicai.wechat-pay.private-key-path':(destination/'apiclient_key.pem').as_posix(),
        'qicai.wechat-pay.public-key-path':(destination/'wechatpay-public.pem').as_posix(),
        'qicai.wechat-pay.public-key-id':args.public_key_id,
        'qicai.wechat-pay.api-v3-key-file':(destination/'api-v3.key').as_posix(),
        'qicai.wechat-pay.app-id':'', 'qicai.wechat-pay.app-secret-file':'', 'qicai.wechat-pay.notify-url':''}
    config=destination/'wechatpay.properties'
    config.write_text('\n'.join(k+'='+v for k,v in properties.items())+'\n',encoding='utf-8')
    print(json.dumps({'merchantId':merchant,'serialNumber':serial,'privateKeyMatches':True,
        'notAfter':cert.not_valid_after_utc.isoformat(),'privateConfig':str(config),
        'enabled':False,'missing':['AppID','AppSecret file','HTTPS notify URL']},ensure_ascii=False))
    return 0
if __name__=='__main__':
    sys.exit(main())
