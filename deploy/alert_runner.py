"""Run an operational check and persist/deliver safe status events.

No command output, credentials, request bodies or exception messages are sent.
Webhook HTTP acceptance is not evidence that a human received the notification.
"""
import argparse
from contextlib import contextmanager
import datetime as dt
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid


def timestamp():
    return dt.datetime.now(dt.timezone.utc).isoformat()


def save(path, data):
    temporary = path.with_name(path.name + '.' + uuid.uuid4().hex + '.tmp')
    try:
        fd = os.open(temporary, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        with os.fdopen(fd, 'w', encoding='utf-8') as stream:
            json.dump(data, stream, ensure_ascii=False, indent=2)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


@contextmanager
def lock(path):
    with path.open('a+b') as stream:
        if stream.seek(0, 2) == 0:
            stream.write(b'0')
            stream.flush()
        stream.seek(0)
        if os.name == 'nt':
            import msvcrt
            msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
        else:
            import fcntl
            fcntl.flock(stream.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        try:
            yield
        finally:
            stream.seek(0)
            if os.name == 'nt':
                msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                fcntl.flock(stream.fileno(), fcntl.LOCK_UN)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def configuration(path, simulation=False):
    data = json.loads(path.read_text(encoding='utf-8')) if path else {}
    if not isinstance(data, dict) or set(data) - {'webhook_url', 'bearer_token_file', 'timeout_seconds', 'command_timeout_seconds', 'repeat_seconds'}:
        raise ValueError('Invalid alert configuration fields')
    for key, low, high, default in [('timeout_seconds', 1, 30, 10), ('command_timeout_seconds', 1, 3600, 180), ('repeat_seconds', 60, 604800, 3600)]:
        value = data.get(key, default)
        if type(value) is not int or not low <= value <= high:
            raise ValueError('Invalid time limit: ' + key)
        data[key] = value
    url = data.get('webhook_url')
    if url:
        target = urllib.parse.urlparse(url)
        if not target.hostname or target.username or target.password or target.fragment:
            raise ValueError('Invalid webhook endpoint')
        if target.scheme != 'https' and not (simulation and target.scheme == 'http' and target.hostname in ('127.0.0.1', '::1')):
            raise ValueError('Webhook requires HTTPS; only explicit local simulation accepts HTTP loopback')
    if data.get('bearer_token_file'):
        token_path = Path(data['bearer_token_file'])
        if not token_path.is_absolute():
            raise ValueError('Token file must be absolute')
        if os.name != 'nt' and token_path.stat().st_mode & 0o077:
            raise ValueError('Token file must be accessible only to its owner')
        token = token_path.read_text(encoding='utf-8').strip()
        if not token or '\r' in token or '\n' in token:
            raise ValueError('Invalid bearer token file')
        data['_token'] = token
    return data


def deliver(event, config):
    if not config.get('webhook_url'):
        return {'status': 'not_configured', 'at': timestamp()}
    headers = {'Content-Type': 'application/json', 'Idempotency-Key': event['id']}
    if config.get('_token'):
        headers['Authorization'] = 'Bearer ' + config['_token']
    request = urllib.request.Request(config['webhook_url'], data=json.dumps(event).encode(), headers=headers, method='POST')
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect)
    try:
        with opener.open(request, timeout=config['timeout_seconds']) as response:
            code = response.status
            response.read(1024)
        return {'status': 'http_accepted' if 200 <= code < 300 else 'failed', 'http': code, 'at': timestamp()}
    except urllib.error.HTTPError as error:
        return {'status': 'failed', 'http': error.code, 'at': timestamp()}
    except Exception as error:
        return {'status': 'failed', 'errorType': type(error).__name__, 'at': timestamp()}


def execute(command, timeout):
    """Own the complete check process group so timeout cannot orphan a backup."""
    try:
        child = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                                 stderr=subprocess.DEVNULL, start_new_session=os.name != 'nt',
                                 creationflags=(subprocess.CREATE_NO_WINDOW | subprocess.CREATE_NEW_PROCESS_GROUP) if os.name == 'nt' else 0)
    except OSError:
        return 127, 'launch_failed'
    try:
        return child.wait(timeout=timeout), 'exit'
    except subprocess.TimeoutExpired:
        cleanup_ok = False
        try:
            if os.name == 'nt':
                killed = subprocess.run(['taskkill', '/PID', str(child.pid), '/T', '/F'], stdout=subprocess.DEVNULL,
                                        stderr=subprocess.DEVNULL, timeout=15, creationflags=subprocess.CREATE_NO_WINDOW)
                cleanup_ok = killed.returncode == 0
            else:
                os.killpg(child.pid, signal.SIGKILL)
                cleanup_ok = True
            child.wait(timeout=15)
        except Exception:
            cleanup_ok = False
            # Best effort on this owned direct child; this does not claim its
            # entire tree was removed when group termination could not confirm.
            try:
                child.kill()
                child.wait(timeout=5)
            except Exception:
                pass
        return 124, 'timeout' if cleanup_ok else 'timeout_cleanup_unconfirmed'


def run(name, command, state_dir, config):
    if not re.fullmatch(r'[a-zA-Z0-9_-]{1,64}', name) or not command:
        raise ValueError('Safe check name and command are required')
    state_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
    with lock(state_dir / (name + '.lock')):
        path = state_dir / (name + '.json')
        state = json.loads(path.read_text(encoding='utf-8')) if path.exists() else {'active': False, 'pending': [], 'lastEventEpoch': 0}
        start = time.monotonic()
        code, reason = execute(command, config['command_timeout_seconds'])
        failed = code != 0
        event_type = ('failure' if not state['active'] else 'reminder') if failed else ('recovery' if state['active'] else None)
        # A pending notification is retried with the same id, not appended on every timer tick.
        if event_type == 'reminder' and (state['pending'] or time.time() - state['lastEventEpoch'] < config['repeat_seconds']):
            event_type = None
        if event_type:
            event = {'id': uuid.uuid4().hex, 'at': timestamp(), 'check': name, 'event': event_type,
                     'exitCode': code, 'reason': reason, 'durationMs': round((time.monotonic() - start) * 1000)}
            state['pending'].append(event)
            state['lastEventEpoch'] = time.time()
        state.update(active=failed, lastCheckedAt=timestamp(), lastExitCode=code)
        # The state file is the single durable outbox. Per-event files below are
        # derived evidence, so a crash between file writes never orphans a send.
        save(path, state)
        delivery = {'status': 'no_event'}
        for event in list(state['pending'])[:10]:
            delivery = deliver(event, config)
            save(state_dir / ('event-' + event['id'] + '.json'), {'event': event, 'delivery': delivery})
            if delivery['status'] != 'http_accepted':
                break
            state['pending'].remove(event)
            save(path, state)
        result = {'at': timestamp(), 'check': name, 'checkPassed': not failed, 'checkExitCode': code,
                  'delivery': delivery, 'pendingEvents': len(state['pending']),
                  'meaning': 'HTTP acceptance only; real receiver/human delivery requires separate verification'}
        # Preserve the check failure; a successful check with configured delivery failure is also nonzero.
        result['exitCode'] = (code if 0 < code < 126 else 1) if failed else (2 if config.get('webhook_url') and state['pending'] else 0)
        return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--name', required=True)
    parser.add_argument('--state-dir', required=True, type=Path)
    parser.add_argument('--config', type=Path)
    parser.add_argument('--simulate-loopback', action='store_true')
    parser.add_argument('command', nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = args.command[1:] if args.command[:1] == ['--'] else args.command
    try:
        config = configuration(args.config, args.simulate_loopback)
        result = run(args.name, command, args.state_dir, config)
    except Exception as error:
        result = {'at': timestamp(), 'exitCode': 2, 'errorType': type(error).__name__, 'meaning': 'check/alert configuration or state failure; no success claimed'}
    print(json.dumps(result, ensure_ascii=False))
    return result['exitCode']


if __name__ == '__main__':
    raise SystemExit(main())
