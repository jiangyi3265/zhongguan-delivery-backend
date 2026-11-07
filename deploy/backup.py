#!/usr/bin/env python3
"""Verified DB and upload backups. Importable for isolated acceptance; no work on import."""
import argparse
import datetime
import gzip
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tarfile
import uuid


def digest(path):
    value = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            value.update(block)
    return value.hexdigest()


def private_file(path):
    return os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, 'O_BINARY', 0), 0o600), 'wb')


def upload_files(directory):
    directory = Path(directory)
    if directory.is_symlink() or not directory.is_dir():
        raise RuntimeError('Uploads must be an existing regular directory')
    files = []
    for folder, dirs, names in os.walk(directory, followlinks=False):
        for name in dirs + names:
            if (Path(folder) / name).is_symlink():
                raise RuntimeError('Upload symlinks are not supported in a backup')
        for name in names:
            entry = Path(folder) / name
            if not entry.is_file():
                raise RuntimeError('Only regular upload files are supported')
            files.append(entry)
    return sorted(files)


def remove_partial(path, parent):
    path, parent = Path(path), Path(parent).resolve()
    # Only remove our validated temporary directory, never a computed outside path.
    if path.parent.resolve() != parent or not path.name.endswith('.partial') or path.is_symlink():
        raise RuntimeError('Refusing unsafe partial-backup cleanup')
    if path.exists():
        shutil.rmtree(path)


def create_backup(output_dir, uploads_dir, dump_command, database='qicai_delivery'):
    if not re.fullmatch(r'[A-Za-z0-9_]+', database):
        raise ValueError('Invalid database label')
    original_uploads = Path(uploads_dir)
    if original_uploads.is_symlink():
        raise ValueError('Uploads root cannot be a symlink')
    output, uploads = Path(output_dir).resolve(), original_uploads.resolve()
    if output == uploads or output.is_relative_to(uploads):
        raise ValueError('Backup output must not be inside the uploads tree')
    output.mkdir(parents=True, exist_ok=True, mode=0o700)
    output.chmod(0o700)
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
    name = 'qicai-' + stamp + '-' + uuid.uuid4().hex[:12]
    partial, final = output / (name + '.partial'), output / name
    partial.mkdir(mode=0o700)
    try:
        files = []
        with private_file(partial / 'uploads.tar.gz') as raw:
            with tarfile.open(fileobj=raw, mode='w:gz') as archive:
                for source in upload_files(uploads):
                    content = source.read_bytes()
                    relative = source.relative_to(uploads).as_posix()
                    entry = tarfile.TarInfo(relative)
                    entry.size, entry.mode, entry.mtime = len(content), 0o600, int(source.stat().st_mtime)
                    archive.addfile(entry, io.BytesIO(content))
                    files.append({'path': relative, 'size': len(content), 'sha256': hashlib.sha256(content).hexdigest()})
        with private_file(partial / 'database.sql.gz') as raw:
            with gzip.GzipFile(fileobj=raw, mode='wb') as compressed:
                # stderr goes to a private file to prevent a pipe deadlock or secret logs.
                with private_file(partial / 'dump.stderr') as error_stream:
                    process = subprocess.Popen(dump_command, stdout=subprocess.PIPE, stderr=error_stream)
                    try:
                        shutil.copyfileobj(process.stdout, compressed)
                        process.stdout.close()
                        if process.wait() != 0:
                            raise RuntimeError('Database dump failed; no complete backup was published')
                    finally:
                        if process.poll() is None:
                            process.kill()
                            process.wait()
                (partial / 'dump.stderr').unlink()
        # Reject a changed/deleted/new file instead of publishing an inconsistent bundle.
        current = [{'path': p.relative_to(uploads).as_posix(), 'size': p.stat().st_size, 'sha256': digest(p)}
                   for p in upload_files(uploads)]
        if current != files:
            raise RuntimeError('Uploads changed during backup; retry in a quiet window')
        manifest = {
            'format': 1, 'createdAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
            'database': database, 'files': files,
            'archives': {name: {'sha256': digest(partial / name), 'size': (partial / name).stat().st_size}
                         for name in ['database.sql.gz', 'uploads.tar.gz']},
            'consistency': 'database transaction snapshot; uploads identical before and after database capture',
        }
        with private_file(partial / 'manifest.json') as stream:
            stream.write(json.dumps(manifest, ensure_ascii=False, indent=2).encode('utf-8'))
        verify_backup(partial)
        if final.exists():
            raise RuntimeError('Backup target already exists')
        partial.rename(final)
        return {'path': str(final), 'files': len(files), 'manifestSha256': digest(final / 'manifest.json')}
    except BaseException:
        remove_partial(partial, output)
        raise


def verify_backup(bundle):
    bundle = Path(bundle)
    if bundle.is_symlink() or not bundle.is_dir():
        raise RuntimeError('Backup bundle must be a regular directory')
    if (bundle / 'manifest.json').is_symlink():
        raise RuntimeError('Backup manifest cannot be a symlink')
    manifest = json.loads((bundle / 'manifest.json').read_text(encoding='utf-8'))
    if manifest.get('format') != 1 or set(manifest.get('archives', {})) != {'database.sql.gz', 'uploads.tar.gz'}:
        raise RuntimeError('Unsupported backup format')
    for name, info in manifest['archives'].items():
        archive = bundle / name
        if archive.is_symlink() or not archive.is_file() or archive.stat().st_size != info['size'] or digest(archive) != info['sha256']:
            raise RuntimeError('Backup archive integrity check failed: ' + name)
    with gzip.open(bundle / 'database.sql.gz', 'rb') as stream:
        while stream.read(1024 * 1024):
            pass
    expected = {entry['path']: entry for entry in manifest['files']}
    if len(expected) != len(manifest['files']):
        raise RuntimeError('Duplicate manifest path')
    with tarfile.open(bundle / 'uploads.tar.gz', 'r:gz') as archive:
        seen = set()
        for entry in archive:
            if not entry.isfile() or entry.name not in expected or entry.name in seen:
                raise RuntimeError('Unexpected backup file entry')
            if entry.name.startswith('/') or any(part in ('', '.', '..') for part in entry.name.split('/')) or '\\' in entry.name or ':' in entry.name:
                raise RuntimeError('Unsafe backup file path')
            content = archive.extractfile(entry).read()
            if entry.size != expected[entry.name]['size'] or hashlib.sha256(content).hexdigest() != expected[entry.name]['sha256']:
                raise RuntimeError('Upload integrity check failed')
            seen.add(entry.name)
        if seen != set(expected):
            raise RuntimeError('Backup upload entries are incomplete')
    return manifest


def restore_uploads(bundle, destination):
    verify_backup(bundle)
    destination = Path(destination).absolute()
    if destination.exists() or destination.is_symlink():
        raise RuntimeError('Refusing to overwrite an existing restore destination')
    destination.parent.mkdir(parents=True, exist_ok=True)
    partial = destination.parent / (destination.name + '-' + uuid.uuid4().hex[:12] + '.partial')
    partial.mkdir(mode=0o700)
    try:
        with tarfile.open(Path(bundle) / 'uploads.tar.gz', 'r:gz') as archive:
            for entry in archive:
                target = partial / entry.name
                if not target.resolve().is_relative_to(partial.resolve()):
                    raise RuntimeError('Unsafe restore path')
                target.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
                with private_file(target) as stream:
                    shutil.copyfileobj(archive.extractfile(entry), stream)
        partial.rename(destination)
        return str(destination)
    except BaseException:
        remove_partial(partial, destination.parent)
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--verify', type=Path, help='verify a complete bundle without restoring')
    args = parser.parse_args()
    if args.verify:
        manifest = verify_backup(args.verify)
        print(json.dumps({'verified': True, 'files': len(manifest['files'])}))
        return
    command = ['/www/server/mysql/bin/mysqldump', '--defaults-extra-file=/etc/qicai/mysql-root.cnf',
               '--single-transaction', '--routines', '--triggers', '--hex-blob', '--no-tablespaces',
               '--set-gtid-purged=OFF', 'qicai_delivery']
    result = create_backup('/www/qicai/backups/complete', '/www/qicai/shared/uploads', command)
    print(json.dumps(result, ensure_ascii=False))


if __name__ == '__main__':
    main()
