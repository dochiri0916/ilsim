"""Keep this checkout current for `ask`: fast-forward to the tracked branch, then rebuild what the pull changed.

`ask` runs the Python scripts straight from this checkout, so a pull is enough for them. The analyzer jar and the
build-convention plugin in ~/.m2 are built artifacts: each is rebuilt only when its sources changed since the last
build this file recorded. Nothing is pulled when the checkout has uncommitted edits or has diverged from its upstream,
and a failed fetch or build leaves the previous version running. The record lives in the Git directory, never in the
working tree.

    python3 self_update.py               check for updates now and rebuild what changed
    python3 self_update.py --mark-built  record the current sources as built (install.sh calls this after building)
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import time

try:
    import fcntl
except ImportError:  # pragma: no cover - ask is installed as a POSIX shell wrapper
    fcntl = None

ROOT = Path(__file__).resolve().parents[2]
RECORD = 'ask-update.json'
LOCK = 'ask-update.lock'
# A fetch at most this often, so an offline PC does not wait on the network for every request.
INTERVAL_SECONDS = 600
FETCH_TIMEOUT = 30
BUILD_TIMEOUT = 1800
# Paths whose content decides whether a built artifact is current, and the Gradle task that rebuilds it.
BUILDS = (
    ('code-risk-analyzer', ('src', 'build.gradle', 'settings.gradle', 'gradle'), 'bootJar'),
    ('build-convention', ('src', 'config', 'build.gradle', 'settings.gradle', 'gradle'), 'publishToMavenLocal'),
)


def _say(message):
    print(message, file=sys.stderr)


def _git(root, *args, run, timeout=60):
    return run(['git', '-C', str(root), *args], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
               timeout=timeout)


def _out(result):
    return result.stdout.strip() if result.returncode == 0 else None


def build_key(root, project, paths, run=subprocess.run):
    """The committed trees of the paths that go into the artifact; a change elsewhere needs no rebuild."""
    listing = _out(_git(root, 'ls-tree', 'HEAD', '--', *(f'{project}/{path}' for path in paths), run=run))
    if not listing:
        raise OSError(f'{project}의 소스를 Git에서 찾지 못했습니다')
    return hashlib.sha256(listing.encode('utf-8')).hexdigest()


def _read(path):
    try:
        value = json.loads(path.read_text(encoding='utf-8'))
    except (OSError, ValueError):
        return {}
    return value if isinstance(value, dict) else {}


def _write(path, value):
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + '\n', encoding='utf-8')


def _clean(root, run):
    """No uncommitted edits. Python bytecode that older commits tracked is regenerated, so it is restored, not kept."""
    status = _git(root, 'status', '--porcelain', '--untracked-files=no', run=run)
    if status.returncode != 0:
        return False
    # Not stripped: the first column of a porcelain line may be a space.
    paths = [line[3:] for line in status.stdout.splitlines() if line]
    cache = [path for path in paths if '__pycache__/' in path]
    if cache and _git(root, 'checkout', '--', *cache, run=run).returncode != 0:
        return False
    return len(cache) == len(paths)


def pull(root, run=subprocess.run, say=_say):
    """Fast-forward to the upstream branch. True when new commits arrived."""
    if _out(_git(root, 'rev-parse', '--abbrev-ref', '--symbolic-full-name', '@{u}', run=run)) is None:
        say('업데이트 건너뜀: 이 도구 폴더의 브랜치에 추적 브랜치가 없습니다')
        return False
    try:
        fetched = _git(root, 'fetch', '--quiet', run=run, timeout=FETCH_TIMEOUT)
    except subprocess.TimeoutExpired:
        fetched = None
    if fetched is None or fetched.returncode != 0:
        say('업데이트 확인 실패(네트워크·인증): 지금 버전으로 실행합니다')
        return False
    head = _out(_git(root, 'rev-parse', 'HEAD', run=run))
    target = _out(_git(root, 'rev-parse', '@{u}', run=run))
    if head is None or target is None or head == target:
        return False
    if _git(root, 'merge-base', '--is-ancestor', 'HEAD', '@{u}', run=run).returncode != 0:
        say('업데이트 건너뜀: 이 도구 폴더의 커밋이 원격과 갈라졌습니다 (직접 정리 후 git pull)')
        return False
    if _git(root, 'merge', '--ff-only', '--quiet', '@{u}', run=run).returncode != 0:
        say('업데이트 실패: fast-forward하지 못했습니다. 지금 버전으로 실행합니다')
        return False
    say(f'업데이트: {head[:7]} → {target[:7]}')
    return True


def rebuild(root, record, run=subprocess.run, say=_say):
    """Rebuild each artifact whose sources differ from its last build; a known-broken source is not retried."""
    built, failed = record.setdefault('built', {}), record.setdefault('failed', {})
    for project, paths, task in BUILDS:
        key = build_key(root, project, paths, run)
        if built.get(project) == key:
            continue
        if failed.get(project) == key:
            say(f'{project}: 이 버전은 빌드에 실패했습니다. 이전 빌드로 실행합니다')
            continue
        say(f'{project} 다시 빌드 중 ({task})')
        try:
            result = run(['./gradlew', task, '--no-daemon', '-q'], cwd=root/project, stdout=subprocess.PIPE,
                         stderr=subprocess.STDOUT, text=True, timeout=BUILD_TIMEOUT)
            ok, output = result.returncode == 0, result.stdout or ''
        except (OSError, subprocess.TimeoutExpired) as error:
            ok, output = False, str(error)
        if ok:
            built[project] = key
            failed.pop(project, None)
        else:
            failed[project] = key
            tail = '\n'.join(output.strip().splitlines()[-20:])
            say(f'{project} 빌드 실패: 이전 빌드로 실행합니다' + (f'\n{tail}' if tail else ''))


def update(root=ROOT, run=subprocess.run, now=time.time, environ=os.environ, say=_say, force=False):
    """Pull and rebuild as needed. True when new commits were pulled, so the caller restarts on the new code."""
    if environ.get('ASK_NO_UPDATE'):
        return False
    git_dir = _out(_git(root, 'rev-parse', '--absolute-git-dir', run=run))
    if git_dir is None:
        return False
    git_dir = Path(git_dir)
    with open(git_dir/LOCK, 'w') as lock:
        if fcntl is not None:
            try:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except OSError:
                return False  # another ask is updating; this one runs the current version
        if not _clean(root, run):
            say('업데이트 건너뜀: 이 도구 폴더에 커밋하지 않은 수정이 있습니다')
            return False
        record = _read(git_dir/RECORD)
        pulled = False
        if force or now() - record.get('checkedAt', 0) >= INTERVAL_SECONDS:
            pulled = pull(root, run, say)
            # Recorded on failure too: offline, the next request runs without waiting on the network again.
            record['checkedAt'] = now()
            _write(git_dir/RECORD, record)
        try:
            rebuild(root, record, run, say)
        finally:
            _write(git_dir/RECORD, record)
        return pulled


def mark_built(root=ROOT, run=subprocess.run):
    git_dir = Path(_out(_git(root, 'rev-parse', '--absolute-git-dir', run=run)) or '')
    if not git_dir.is_dir():
        raise OSError('Git checkout이 아닙니다')
    record = _read(git_dir/RECORD)
    record['built'] = {project: build_key(root, project, paths, run) for project, paths, _ in BUILDS}
    record['failed'] = {}
    _write(git_dir/RECORD, record)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--mark-built', action='store_true')
    args = parser.parse_args(argv)
    try:
        if args.mark_built:
            mark_built()
        else:
            update(force=True, environ={})
    except OSError as error:
        _say(f'업데이트 실패: {error}')
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
