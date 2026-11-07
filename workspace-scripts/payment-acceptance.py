"""Payment regression: real isolated HTTP/MySQL; synthetic WeChat provider; no real payments."""
import argparse
import datetime as dt
import json
import os
from pathlib import Path
import sys
import shutil
import xml.etree.ElementTree as ET
from acceptance import Acceptance, ROOT, executable, now, snapshot

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',type=Path,default=ROOT/'tmp/payment-20261007'/('run-'+dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%SZ')))
    parser.add_argument('--full',action='store_true',help='also run the existing complete local acceptance suite')
    args=parser.parse_args();job=Acceptance(args.output)
    start=snapshot(None);(job.out/'source-manifest.json').write_text(json.dumps(start,ensure_ascii=False,indent=2),encoding='utf-8')
    status={'startedAt':now(),'scope':'real isolated HTTP/filters/MySQL + synthetic WeChat provider + Chromium H5/admin',
        'realWechat':'环境阻塞','release':'环境阻塞','missing':['商户通道与绑定实际核验','真实支付/关单/退款授权交易','微信真机及原生域名验收','发货后退货与部分退款不在当前实现范围'],'exitCode':1}
    try:
        job.build();job.start()
        counts=job.payment_tests()
        status['integrationCounts']=counts;status['local']='已实测通过'
        if args.full:
            job.tests(include_payment=False);status['fullLocal']='已实测通过'
        status['exitCode']=2  # Missing true provider/device conditions cannot produce a release pass.
    except Exception as e:
        status['local']='失败';status['error']=str(e)
        print(str(e),file=sys.stderr,flush=True)
        for folder in ['surefire-reports','failsafe-reports']:
            p=ROOT/'RuoYi-Vue/ruoyi-admin/target'/folder
            if p.exists():shutil.copytree(p,job.out/folder,dirs_exist_ok=True)
    finally:
        job.stop();end=snapshot(None);(job.out/'source-manifest-end.json').write_text(json.dumps(end,ensure_ascii=False,indent=2),encoding='utf-8')
        status['sourceUnchanged']=start['sha256']==end['sha256']
        if not status['sourceUnchanged']:status['local']='失败';status['exitCode']=1;status['sourceError']='Source changed during acceptance'
        status['endedAt']=now();(job.out/'summary.json').write_text(json.dumps(status,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(status,ensure_ascii=False),flush=True);return status['exitCode']
if __name__=='__main__':sys.exit(main())
