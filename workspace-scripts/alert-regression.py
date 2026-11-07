"""Actual loopback HTTP delivery, isolated filesystem and synthetic check commands.

This is not a real systemd failure or a real recipient delivery test.
"""
import datetime as dt
import hashlib
import http.server
import importlib.util
import json
import os
from pathlib import Path
import sys
import subprocess
import tempfile
import threading
import time
from unittest import mock

ROOT = Path(__file__).resolve().parent.parent
OUT = Path(os.environ.get('QICAI_EVIDENCE_DIR', ROOT/'tmp/remediation-20261017/alerts-test'))
OUT.mkdir(parents=True, exist_ok=True)
result_path = OUT/'alert-regression-results.json'
if result_path.exists():
    raise RuntimeError('Refuse to overwrite evidence')
source = Path(os.environ.get('QICAI_ALERT_MODULE', ROOT/'deploy/alert_runner.py')).resolve()
spec = importlib.util.spec_from_file_location('alerts', source)
alerts = importlib.util.module_from_spec(spec)
spec.loader.exec_module(alerts)
report = {'startedAt': dt.datetime.now(dt.timezone.utc).isoformat(), 'kind': __doc__, 'source': str(source),
          'sourceSha256': hashlib.sha256(source.read_bytes()).hexdigest(), 'command': [sys.executable, str(Path(__file__).resolve())], 'checks': []}
received = []
response_code = 204


class Receiver(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        event = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        received.append({'event': event, 'idempotencyKey': self.headers.get('Idempotency-Key'),
                         'authorized': self.headers.get('Authorization') == 'Bearer synthetic-only-alert-token'})
        self.send_response(response_code)
        if response_code == 302:
            self.send_header('Location', 'http://127.0.0.1:' + str(self.server.server_port) + '/redirect-target')
        self.end_headers()

    def log_message(self, *args):
        pass


def check(name, condition):
    report['checks'].append({'name': name, 'passed': bool(condition)})
    print(('PASS ' if condition else 'FAIL ') + name)
    assert condition, name


server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Receiver)
thread = threading.Thread(target=server.serve_forever, daemon=True)
thread.start()
try:
    with tempfile.TemporaryDirectory(prefix='qicai-alert-test-') as folder:
        runtime = Path(folder)
        token = runtime/'token'
        token.write_text('synthetic-only-alert-token', encoding='utf-8')
        token.chmod(0o600)
        config_path = runtime/'config.json'
        config_data = {'webhook_url': 'http://127.0.0.1:' + str(server.server_port) + '/events',
                       'bearer_token_file': str(token), 'repeat_seconds': 3600, 'timeout_seconds': 2, 'command_timeout_seconds': 2}
        config_path.write_text(json.dumps(config_data), encoding='utf-8')
        config = alerts.configuration(config_path, simulation=True)
        fail = [sys.executable, '-c', 'import sys;print("synthetic-sensitive-command-output");sys.exit(7)']
        success = [sys.executable, '-c', 'import sys;sys.exit(0)']
        state = runtime/'state'
        first = alerts.run('health', fail, state, config)
        event_id = received[-1]['event']['id']
        check('失败检查保持原非零退出码且模拟HTTP确实接收', first['exitCode'] == 7 and not first['checkPassed']
              and received[-1]['authorized'] and received[-1]['idempotencyKey'] == event_id)
        count = len(received)
        alerts.run('health', fail, state, config)
        check('相同持续故障在提醒间隔内不重复发送', len(received) == count)
        recovered = alerts.run('health', success, state, config)
        check('恢复发送独立恢复事件并持久化健康状态', recovered['exitCode'] == 0 and received[-1]['event']['event'] == 'recovery'
              and json.loads((state/'health.json').read_text())['active'] is False)
        count = len(received)
        alerts.run('health', success, state, config)
        check('持续健康不发送通知', len(received) == count)
        alerts.run('reminder-check', fail, state, config)
        reminder_state = json.loads((state/'reminder-check.json').read_text())
        reminder_state['lastEventEpoch'] = time.time() - config['repeat_seconds'] - 1
        alerts.save(state/'reminder-check.json', reminder_state)
        prior_id = received[-1]['event']['id']
        reminder = alerts.run('reminder-check', fail, state, config)
        check('达到提醒间隔的持续故障产生新提醒事件', reminder['exitCode'] == 7
              and received[-1]['event']['event'] == 'reminder' and received[-1]['event']['id'] != prior_id)
        response_code = 503
        failed_delivery = alerts.run('backup', fail, state, config)
        retry_id = received[-1]['event']['id']
        check('接收端失败保留持久待送事件且不冒充送达', failed_delivery['delivery']['status'] == 'failed'
              and failed_delivery['pendingEvents'] == 1 and failed_delivery['exitCode'] == 7)
        response_code = 204
        retried = alerts.run('backup', fail, state, config)
        check('后续重试使用同事件ID且送达后清待送队列', received[-1]['event']['id'] == retry_id and retried['pendingEvents'] == 0)
        response_code = 503
        alerts.run('healthy-pending', fail, state, config)
        healthy_pending = alerts.run('healthy-pending', success, state, config)
        check('检查恢复但通知仍失败时总退出非零且保留故障与恢复', healthy_pending['checkPassed']
              and healthy_pending['exitCode'] == 2 and healthy_pending['pendingEvents'] == 2
              and [event['event'] for event in json.loads((state/'healthy-pending.json').read_text())['pending']] == ['failure', 'recovery'])
        response_code = 204
        assert alerts.run('healthy-pending', success, state, config)['pendingEvents'] == 0
        response_code = 302
        count = len(received)
        redirected = alerts.run('redirect', fail, state, config)
        check('拒绝HTTP重定向避免把凭据转送其他地址', len(received) == count + 1 and redirected['delivery']['http'] == 302
              and redirected['delivery']['status'] == 'failed')
        response_code = 204
        local = alerts.configuration(None)
        local_result = alerts.run('unconfigured', fail, state, local)
        check('无接收渠道只记录未配置并保留失败', local_result['delivery']['status'] == 'not_configured'
              and local_result['pendingEvents'] == 1 and local_result['exitCode'] == 7)
        ready = runtime/'lock-ready'
        release = runtime/'lock-release'
        rejected_command = runtime/'unexpected-second-command'
        child_code = ('import pathlib,time;pathlib.Path(' + repr(str(ready)) + ').write_text("ready");'
                      'deadline=time.monotonic()+15\nwhile not pathlib.Path(' + repr(str(release)) + ').exists() and time.monotonic()<deadline: time.sleep(.05)')
        contender = subprocess.Popen([sys.executable, str(source), '--name', 'exclusive', '--state-dir', str(state), '--', sys.executable, '-c', child_code],
                                     stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                     creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
        try:
            deadline = time.monotonic()+10
            while not ready.exists() and time.monotonic()<deadline:
                time.sleep(.05)
            assert ready.exists(), 'First operational command did not enter the real locked section'
            collision = subprocess.run([sys.executable, str(source), '--name', 'exclusive', '--state-dir', str(state), '--', sys.executable, '-c',
                                        'import pathlib;pathlib.Path(' + repr(str(rejected_command)) + ').write_text("unexpected")'],
                                       capture_output=True, timeout=10, creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
            release.write_text('release', encoding='utf-8')
            contender_output, contender_error = contender.communicate(timeout=10)
            check('跨进程互斥拒绝第二检查且不启动其命令', collision.returncode == 2
                  and not rejected_command.exists() and contender.returncode == 0 and not contender_error)
        finally:
            release.write_text('release', encoding='utf-8')
            if contender.poll() is None:
                contender.kill()
                contender.wait(timeout=5)
        config_path.write_text(json.dumps({**config_data, 'webhook_url': 'http://example.com/events'}), encoding='utf-8')
        rejected = False
        try:
            alerts.configuration(config_path, simulation=True)
        except ValueError:
            rejected = True
        check('模拟开关仍拒绝非回环HTTP', rejected)
        timed = alerts.run('timeout', [sys.executable, '-c', 'import time;time.sleep(10)'], state, config)
        check('检查超时显式失败并记录timeout', timed['exitCode'] == 124 and received[-1]['event']['reason'] == 'timeout')
        marker = runtime/'orphan-marker'
        gate = runtime/'cleanup-returned'
        # Start the observation window only after cleanup returns. Windows
        # taskkill tree enumeration itself can take >2 seconds on a busy host.
        nested = ('import time,pathlib\ngate=pathlib.Path(' + repr(str(gate)) + ')\n'
                  'deadline=time.monotonic()+25\nwhile not gate.exists() and time.monotonic()<deadline: time.sleep(.05)\n'
                  'if gate.exists(): pathlib.Path(' + repr(str(marker)) + ').write_text("orphan")\n')
        parent = 'import subprocess,sys,time;subprocess.Popen([sys.executable,"-c",' + repr(nested) + ']);time.sleep(15)'
        alerts.run('nested-timeout', [sys.executable, '-c', parent], state, config)
        gate.write_text('cleanup completed', encoding='utf-8')
        time.sleep(1)
        check('超时同时终止本次检查的子进程', not marker.exists())
        missing = alerts.run('missing', [str(runtime/'missing-executable')], state, config)
        check('检查进程无法启动不能显示健康', missing['exitCode'] != 0 and received[-1]['event']['reason'] == 'launch_failed')
        real_save = alerts.save
        def fail_evidence(path, data):
            if path.name.startswith('event-'):
                raise OSError('synthetic evidence write failure')
            return real_save(path, data)
        alerts.save = fail_evidence
        write_failed = False
        try:
            alerts.run('outbox-crash', fail, state, config)
        except OSError:
            write_failed = True
        finally:
            alerts.save = real_save
        pending = json.loads((state/'outbox-crash.json').read_text())['pending']
        check('事件证据文件写失败仍保留权威待送队列', write_failed and len(pending) == 1)
        resumed = alerts.run('outbox-crash', success, state, config)
        events = [item['event'] for item in received if item['event']['check'] == 'outbox-crash']
        check('写失败后可重放故障及恢复且未丢原事件', resumed['pendingEvents'] == 0
              and [item['event'] for item in events][-2:] == ['failure', 'recovery']
              and all(item['id'] == pending[0]['id'] for item in events if item['event'] == 'failure'))
        # Inject only the operating-system tree termination failure, then execute
        # the real timeout/fallback/outbox logic against a real owned process.
        termination = mock.patch.object(alerts.subprocess, 'run', side_effect=OSError('synthetic tree cleanup failure')) if os.name == 'nt' else mock.patch.object(alerts.os, 'killpg', side_effect=OSError('synthetic group cleanup failure'))
        with termination:
            uncertain = alerts.run('cleanup-failure', [sys.executable, '-c', 'import time;time.sleep(10)'], state, {**config, 'command_timeout_seconds': 1})
        check('清理未确认仍持久告警而非漏报或宣称清理成功', uncertain['exitCode'] == 124
              and received[-1]['event']['reason'] == 'timeout_cleanup_unconfirmed')
        serialized = json.dumps(received) + ''.join(p.read_text(encoding='utf-8') for p in state.glob('*.json'))
        check('事件与状态文件不包含凭据或原命令输出', 'synthetic-only-alert-token' not in serialized
              and 'synthetic-sensitive-command-output' not in serialized and str(token) not in serialized)
        # Preserve safe actual events for review, never the token/configuration file.
        report['receivedEvents'] = received
        report['runtimeCleaned'] = True
    report['exitCode'] = 0
except Exception as error:
    report.update(exitCode=1, errorType=type(error).__name__, error=str(error))
finally:
    server.shutdown()
    server.server_close()
    thread.join(timeout=5)
    report['endedAt'] = dt.datetime.now(dt.timezone.utc).isoformat()
    report['receivedEvents'] = received
    result_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
raise SystemExit(report['exitCode'])
