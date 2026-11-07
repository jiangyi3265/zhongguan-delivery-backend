"""Small release-switch transaction; imported by deployment and simulated failure tests."""
import os
from pathlib import Path
import subprocess


def switch_release(current, next_link, release, previous, nginx_config, original_config, updated_config,
                   health_check, run=subprocess.run, nginx='/www/server/nginx/sbin/nginx'):
    current, next_link, release, previous, nginx_config = map(Path, [current, next_link, release, previous, nginx_config])
    if not current.is_symlink() or current.resolve() != previous.resolve() or next_link.parent != current.parent:
        raise RuntimeError('Unexpected release pointer; no changes made')
    if next_link.exists() or next_link.is_symlink():
        raise RuntimeError('Unexpected pending release link; no changes made')
    switched = False
    try:
        nginx_config.write_text(updated_config)
        run([nginx, '-t'], check=True)
        next_link.symlink_to(release, target_is_directory=True)
        os.replace(next_link, current)
        switched = True
        run(['systemctl', 'restart', 'qicai-api'], check=True)
        if not health_check():
            raise RuntimeError('New release readiness check failed')
        run([nginx, '-s', 'reload'], check=True)
    except Exception as failure:
        errors = []
        try:
            nginx_config.write_text(original_config)
            if switched:
                next_link.symlink_to(previous, target_is_directory=True)
                os.replace(next_link, current)
                run(['systemctl', 'restart', 'qicai-api'], check=True)
            elif next_link.is_symlink():
                # Only this helper's pending link can exist after the initial absence check.
                next_link.unlink()
            run([nginx, '-t'], check=True)
            run([nginx, '-s', 'reload'], check=True)
            if not health_check():
                raise RuntimeError('Previous release readiness could not be verified')
        except Exception as rollback_failure:
            errors.append(type(rollback_failure).__name__)
        if errors:
            raise RuntimeError('Release switch failed and rollback needs operator attention: ' + ', '.join(errors)) from failure
        raise RuntimeError('Release switch failed; previous release and proxy configuration restored; additive DB migration retained') from failure
