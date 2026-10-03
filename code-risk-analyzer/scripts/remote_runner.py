"""Connect this PC's `ask` to the phone page on Vercel: report status, take queued requests and approvals.

Every run stays on this PC with its plan logins; the page only sees status lines and the tail of `ask` output. A
request runs `ask "<text>"` (drafts, never changes code); an approval runs `ask --yes`, and only for the session the
phone was showing, after that session's plan printed that it waits for confirmation.

    ask-remote                 inside a Gradle project (ASK_REMOTE_URL and ASK_RUNNER_TOKEN in env or ~/.secrets)
    ask-remote --once          one exchange, for a quick connection check
"""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request

SCRIPTS = Path(__file__).resolve().parent
SETTINGS = ('ASK_REMOTE_URL', 'ASK_RUNNER_TOKEN')
SUMMARY_LIMIT = 4000
RUN_TIMEOUT = 3 * 3600


def _module(name):
    spec = importlib.util.spec_from_file_location(name, SCRIPTS/(name + '.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def settings(environ=os.environ):
    """ASK_REMOTE_URL and ASK_RUNNER_TOKEN from the environment, else KEY=value lines in ~/.secrets (or a folder)."""
    found = {key: environ[key].strip() for key in SETTINGS if environ.get(key, '').strip()}
    path = Path(environ.get('CODE_RISK_SECRETS') or '~/.secrets').expanduser()
    files = [path] if path.is_file() else sorted(p for p in path.iterdir() if p.is_file()) if path.is_dir() else []
    for file in files:
        if file.stat().st_size > 65536:
            continue
        for line in file.read_text(encoding='utf-8', errors='replace').splitlines():
            match = re.fullmatch(r'\s*(?:export\s+)?(ASK_REMOTE_URL|ASK_RUNNER_TOKEN)\s*=\s*(.*?)\s*', line)
            if match and match.group(2).strip('"\'') and match.group(1) not in found:
                found[match.group(1)] = match.group(2).strip('"\'')
    missing = [key for key in SETTINGS if key not in found]
    if missing:
        raise ValueError(', '.join(missing) + ' 이(가) 없습니다. 환경 변수나 ~/.secrets에 KEY=값으로 넣으세요')
    if not found['ASK_REMOTE_URL'].startswith('https://'):
        raise ValueError('ASK_REMOTE_URL은 https:// 주소여야 합니다')
    return found['ASK_REMOTE_URL'].rstrip('/'), found['ASK_RUNNER_TOKEN']


def poster(url, token, timeout=30):
    def post(status):
        request = urllib.request.Request(url + '/api/runner', data=json.dumps({'status': status}).encode(), method='POST',
                                         headers={'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json'})
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return json.loads(response.read())['commands']
    return post


def summary(output, limit=SUMMARY_LIMIT):
    """The end of the output, where ask prints the plan and the result."""
    text = output.strip()
    return text if len(text) <= limit else '...\n' + text[-limit:].split('\n', 1)[-1]


def child_env(environ=os.environ):
    """ask does not need the runner's own secret."""
    return {key: value for key, value in environ.items() if key not in SETTINGS}


class Runner:
    def __init__(self, project, post, run=subprocess.run, now=time.time):
        self.project, self.post, self.run, self.now = project, post, run, now
        self.busy = None
        self.awaiting = None
        self.last = None
        self.pending = []

    def session(self):
        try:
            session = _module('ask').current_session(self.project)
        except (ValueError, OSError):
            return None
        try:
            phase = json.loads((session/'state.json').read_text(encoding='utf-8')).get('phase')
        except (OSError, ValueError, AttributeError):
            phase = None
        return {'name': session.name, 'phase': phase, 'awaitingApproval': self.awaiting == session.name}

    def status(self):
        return {'project': self.project.name, 'busy': self.busy, 'session': self.session(), 'last': self.last}

    def exchange(self):
        self.pending.extend(self.post(self.status()))

    def ask(self, arguments, label):
        self.busy = label
        try:
            self.exchange()
        except (OSError, ValueError, KeyError):
            pass
        command = [sys.executable, str(SCRIPTS/'ask.py'), '--project', str(self.project), *arguments]
        try:
            result = self.run(command, cwd=self.project, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                              timeout=RUN_TIMEOUT, env=child_env())
            code, output = result.returncode, result.stdout or ''
        except subprocess.TimeoutExpired:
            code, output = 1, '시간 초과로 멈췄습니다'
        self.busy = None
        return code, output

    def handle(self, command):
        kind = command.get('type') if isinstance(command, dict) else None
        if kind == 'request' and isinstance(command.get('text'), str) and command['text'].strip():
            text = command['text'].strip()
            code, output = self.ask([text], '요청: ' + text[:80])
            current = self.session()
            ask = _module('ask')
            self.awaiting = current['name'] if current and code == 0 and ask.NOT_CONFIRMED in output else None
            self.last = {'command': '요청: ' + text[:200], 'exitCode': code, 'summary': summary(output)}
        elif kind == 'approve':
            current = self.session()
            if not current or command.get('session') != current['name'] or self.awaiting != current['name']:
                self.last = {'command': '승인', 'exitCode': 2,
                             'summary': '승인하지 않았습니다: 폰에 보이던 작업이 지금 PC의 작업과 다르거나 승인 대기 중이 아닙니다. 새로 고친 뒤 다시 확인하세요'}
                return
            self.awaiting = None
            code, output = self.ask(['--yes'], '승인 실행: ' + current['name'])
            if code == 0 and _module('ask').NOT_CONFIRMED in output:
                self.awaiting = current['name']
            self.last = {'command': '승인 실행: ' + current['name'], 'exitCode': code, 'summary': summary(output)}
        else:
            self.last = {'command': '알 수 없는 명령', 'exitCode': 2, 'summary': '알 수 없는 명령이라 건너뛰었습니다'}

    def step(self):
        self.exchange()
        while self.pending:
            self.handle(self.pending.pop(0))
            self.exchange()


def main(argv=None, sleep=time.sleep):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--project')
    parser.add_argument('--interval', type=int, default=15, help='폰 명령 확인 간격(초)')
    parser.add_argument('--once', action='store_true')
    args = parser.parse_args(argv)
    try:
        url, token = settings()
        project = Path(args.project).expanduser().resolve() if args.project else _module('ask').find_project(Path.cwd().resolve())
    except (ValueError, OSError) as error:
        print(f'시작할 수 없습니다: {error}')
        return 2
    runner = Runner(project, poster(url, token))
    print(f'폰 연결 시작: {project.name} → {url} (Ctrl+C로 종료)')
    while True:
        try:
            runner.step()
        except urllib.error.HTTPError as error:
            if error.code == 401:
                print('러너 토큰이 Vercel의 ASK_RUNNER_TOKEN과 다릅니다')
                return 2
            print(f'연결 오류 {error.code}, 다시 시도합니다')
        except (OSError, ValueError, KeyError) as error:
            print(f'연결 오류, 다시 시도합니다: {error}')
        if args.once:
            return 0
        sleep(max(5, args.interval))


if __name__ == '__main__':
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        sys.exit(0)
