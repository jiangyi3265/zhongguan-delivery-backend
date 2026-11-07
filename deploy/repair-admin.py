#!/usr/bin/env python3
"""Repair only the freshly provisioned Qicai admin credential length, without logging passwords."""
import pathlib, subprocess, secrets
etc = pathlib.Path('/etc/qicai')
release = pathlib.Path('/www/qicai/current')
existing = next(line.split(': ',1)[1] for line in (etc/'credentials.txt').read_text().splitlines() if line.startswith('Admin password: '))
if len(existing) <= 20:
    raise SystemExit('Credential already within the supported length; refusing to rotate it again')
password = 'Qc!' + secrets.token_urlsafe(12)
hash_value = subprocess.check_output(['/www/server/java/jdk-17.0.8/bin/java','-cp',str(release/'deploy')+':'+str(release/'deploy/*'),'PasswordHash'],input=password+'\n',text=True)
subprocess.run(['/www/server/mysql/bin/mysql','--defaults-extra-file=/etc/qicai/mysql-root.cnf','qicai_delivery'],input="UPDATE sys_user SET password='"+hash_value+"',pwd_update_date=NOW() WHERE user_id=1; UPDATE sys_config SET config_value='"+('Qi!'+secrets.token_urlsafe(12))+"' WHERE config_key='sys.user.initPassword';",text=True,check=True)
path = etc/'credentials.txt'
lines = path.read_text().splitlines()
path.write_text('\n'.join('Admin password: '+password if line.startswith('Admin password: ') else line for line in lines)+'\n')
path.chmod(0o600)
path = etc/'acceptance.env'
path.write_text('QICAI_ADMIN_USER=admin\nQICAI_ADMIN_PASSWORD='+password+'\n')
path.chmod(0o600)
print('Admin password length repaired: '+str(len(password))+' characters; no credential printed.')
