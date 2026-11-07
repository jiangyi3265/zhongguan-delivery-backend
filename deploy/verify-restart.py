import pathlib, subprocess, sys, time, urllib.request
unit = pathlib.Path('/etc/systemd/system/qicai-verify-20260926.service')
content = unit.read_text()
assert 'verification-20260926' in content
if '--server.port=18140' in content:
    listeners = subprocess.check_output(['ss', '-lntH'], text=True)
    if any(row.split()[3].endswith(':19186') for row in listeners.splitlines()):
        sys.exit('QA port 19186 occupied; no changes made')
    unit.write_text(content.replace('--server.port=18140', '--server.port=19186'))
    subprocess.run(['systemctl', 'daemon-reload'], check=True)
subprocess.run(['systemctl', 'restart', 'qicai-verify-20260926'], check=True)
for _ in range(30):
    try:
        with urllib.request.urlopen('http://127.0.0.1:19186/qicai/mobile/health', timeout=2) as response:
            print(response.read().decode()); break
    except Exception:
        time.sleep(1)
else:
    sys.exit('QA health check failed')
