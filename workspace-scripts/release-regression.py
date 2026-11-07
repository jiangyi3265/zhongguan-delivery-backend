"""Local release/backup fault injection. External commands are simulated, never deployed."""
import datetime
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import uuid
from contextlib import ExitStack
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
OUT = Path(os.environ.get('QICAI_EVIDENCE_DIR', ROOT / 'tmp/acceptance-20261004/release-regression'))
OUT.mkdir(parents=True, exist_ok=True)
FIXTURES = OUT / ('release-fixtures-' + uuid.uuid4().hex[:12])
FIXTURES.mkdir()


def load(name):
    spec = importlib.util.spec_from_file_location('qa_' + name, ROOT / 'deploy' / (name + '.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


backup, switch = load('backup'), load('release_switch')
results = []


def test(name, action):
    try:
        detail = action()
        results.append({'name': name, 'passed': True, 'detail': detail})
        print('PASS ' + name)
    except Exception as error:
        results.append({'name': name, 'passed': False, 'error': str(error)})
        print('FAIL ' + name + ': ' + str(error))


uploads = FIXTURES / 'uploads'
uploads.mkdir()
(uploads / 'proof.png').write_bytes(b'synthetic-upload-bytes')
bundles = FIXTURES / 'backups'


def failed_dump():
    try:
        backup.create_backup(bundles, uploads, [sys.executable, '-c', 'import sys; print("partial SQL"); sys.exit(9)'], 'qicai_acceptance')
        raise AssertionError('Failed dump unexpectedly succeeded')
    except RuntimeError as error:
        assert 'Database dump failed' in str(error)
    assert not list(bundles.iterdir()), 'Failure left a published or partial backup'


test('模拟mysqldump失败不发布半成品并清理私有临时目录', failed_dump)


def changed_upload():
    command = [sys.executable, '-c', 'from pathlib import Path; import sys; Path(sys.argv[1]).write_bytes(b"changed"); print("SQL");', str(uploads / 'proof.png')]
    try:
        backup.create_backup(bundles, uploads, command, 'qicai_acceptance')
        raise AssertionError('Concurrent file change unexpectedly accepted')
    except RuntimeError as error:
        assert 'Uploads changed' in str(error)
    assert not list(bundles.iterdir())


test('模拟备份期间文件变化导致明确失败而非一致性通过', changed_upload)


def verified_restore():
    first = backup.create_backup(bundles, uploads, [sys.executable, '-c', 'print("synthetic SQL, not a real database test")'], 'qicai_acceptance')
    second = backup.create_backup(bundles, uploads, [sys.executable, '-c', 'print("synthetic SQL, not a real database test")'], 'qicai_acceptance')
    assert first['path'] != second['path'] and Path(first['path']).is_dir()
    destination = FIXTURES / 'restored'
    backup.restore_uploads(first['path'], destination)
    assert (destination / 'proof.png').read_bytes() == (uploads / 'proof.png').read_bytes()
    try:
        backup.restore_uploads(first['path'], destination)
        raise AssertionError('Existing restore destination was overwritten')
    except RuntimeError as error:
        assert 'Refusing to overwrite' in str(error)
    archive = Path(first['path']) / 'uploads.tar.gz'
    with archive.open('ab') as stream:
        stream.write(b'corruption')
    try:
        backup.verify_backup(first['path'])
        raise AssertionError('Corrupt archive was accepted')
    except RuntimeError as error:
        assert 'integrity check failed' in str(error)
    return {'mode': 'synthetic dump; real filesystem/hash/restore', 'uniqueBundles': 2}


test('独立备份不覆写、恢复拒绝覆写且破坏归档校验失败', verified_restore)


def failure_case_inner(stage):
    folder = FIXTURES / ('switch-' + stage)
    old, new = folder / 'releases' / 'old', folder / 'releases' / 'new'
    old.mkdir(parents=True)
    new.mkdir()
    current, pending, config = folder / 'current', folder / 'next', folder / 'nginx.conf'
    current.symlink_to(old, target_is_directory=True)
    config.write_text('original proxy')
    calls = []
    failed = False
    def run(command, check):
        nonlocal failed
        calls.append(command)
        target = 'restart' if command[:2] == ['systemctl', 'restart'] else 'reload' if command[-2:] == ['-s', 'reload'] else 'config'
        if stage == target and not failed:
            failed = True
            raise subprocess.CalledProcessError(1, command)
    try:
        readiness = lambda: stage != 'rollback-health' and (current.resolve() == old.resolve() or stage != 'health')
        switch.switch_release(current, pending, new, old, config, 'original proxy', 'new proxy', readiness, run=run, nginx='simulated-nginx')
        raise AssertionError('Injected failure unexpectedly succeeded')
    except RuntimeError as error:
        expected = 'rollback needs operator attention' if stage == 'rollback-health' else 'previous release and proxy configuration restored'
        assert expected in str(error), str(error)
    assert current.resolve() == old.resolve()
    assert config.read_text() == 'original proxy'
    assert not pending.exists() and not pending.is_symlink()
    if stage != 'config':
        assert sum(command[:2] == ['systemctl', 'restart'] for command in calls) == 2
    assert calls[-1] == ['simulated-nginx', '-s', 'reload']
    return {'simulatedExternalCommands': calls, 'restoredPointer': True, 'restoredConfig': True,
            'readinessVerified': stage != 'rollback-health', 'operatorAttentionRequired': stage == 'rollback-health'}


def failure_case(stage):
    if os.name != 'nt':
        return failure_case_inner(stage)
    # This workstation lacks SeCreateSymbolicLinkPrivilege. Model links explicitly;
    # do not claim to have exercised Linux symlink or systemd/Nginx behavior.
    links = {}
    native_resolve, native_is_link, native_unlink, native_replace = Path.resolve, Path.is_symlink, Path.unlink, os.replace
    def link(path, target, target_is_directory=False):
        if path.exists() or path in links:
            raise FileExistsError(str(path))
        path.touch()
        links[path] = Path(target)
    def resolve(path, *args, **kwargs):
        return native_resolve(links.get(path, path), *args, **kwargs)
    def is_link(path):
        return path in links or native_is_link(path)
    def unlink(path, *args, **kwargs):
        links.pop(path, None)
        return native_unlink(path, *args, **kwargs)
    def replace(source, destination):
        source, destination = Path(source), Path(destination)
        target = links.get(source)
        native_replace(source, destination)
        links.pop(source, None)
        links.pop(destination, None)
        if target is not None:
            links[destination] = target
    with ExitStack() as stack:
        for name, implementation in [('symlink_to', link), ('resolve', resolve), ('is_symlink', is_link), ('unlink', unlink)]:
            stack.enter_context(patch.object(Path, name, implementation))
        stack.enter_context(patch.object(os, 'replace', replace))
        result = failure_case_inner(stage)
    result['symlinkModel'] = 'simulated because Windows denied symlink privilege; ordinary file renames are real'
    return result


for stage in ['config', 'restart', 'health', 'reload', 'rollback-health']:
    name = '模拟旧版指针恢复但就绪仍失败时明确要求人工介入' if stage == 'rollback-health' else '模拟发布' + stage + '失败恢复旧版本和代理配置'
    test(name, lambda stage=stage: failure_case(stage))

(OUT / 'release-regression-results.json').write_text(json.dumps({
    'at': datetime.datetime.now(datetime.timezone.utc).isoformat(),
    'type': 'simulated mysqldump/systemctl/nginx and Windows symlinks; real file hash/restore; not a production deployment',
    'results': results, 'passed': all(result['passed'] for result in results)
}, ensure_ascii=False, indent=2), encoding='utf-8')
sys.exit(0 if all(result['passed'] for result in results) else 1)
