"""Connect this PC's `ai` to the phone: a Telegram bot, or the phone page on Vercel. Report status and take requests.

Every run stays on this PC with its plan logins; the phone only sees status lines and the tail of the `ai` output. A
request runs `ai "<text>"` in the project folder: the CLI the limits favor does the task and the checks run after it,
as on the PC. There is no approval step.

    ask-remote                 inside a Gradle project
                               Telegram: TELEGRAM_BOT_TOKEN and TELEGRAM_CHAT_ID in env or ~/.secrets
                               Vercel:   ASK_REMOTE_URL and ASK_RUNNER_TOKEN in env or ~/.secrets
    ask-remote --once          one exchange, for a quick connection check

When a request's `ask` pulled new code for this runner too, the runner starts again on it between exchanges and keeps
the session that was waiting for approval.
"""
import argparse
import hashlib
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
TELEGRAM_SETTINGS = ('TELEGRAM_BOT_TOKEN', 'TELEGRAM_CHAT_ID')
SECRET_KEYS = SETTINGS + TELEGRAM_SETTINGS
SUMMARY_LIMIT = 4000
TELEGRAM_LIMIT = 4000
TELEGRAM_POLL = 25
RUN_TIMEOUT = 3 * 3600
# Carries the session awaiting approval into the restarted runner.
RESUME = 'ASK_REMOTE_AWAITING'


def _module(name):
    spec = importlib.util.spec_from_file_location(name, SCRIPTS/(name + '.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _secrets(environ=os.environ):
    """The remote keys from the environment, else KEY=value lines in ~/.secrets (or a folder)."""
    found = {key: environ[key].strip() for key in SECRET_KEYS if environ.get(key, '').strip()}
    path = Path(environ.get('CODE_RISK_SECRETS') or '~/.secrets').expanduser()
    files = [path] if path.is_file() else sorted(p for p in path.iterdir() if p.is_file()) if path.is_dir() else []
    pattern = r'\s*(?:export\s+)?(' + '|'.join(SECRET_KEYS) + r')\s*=\s*(.*?)\s*'
    for file in files:
        if file.stat().st_size > 65536:
            continue
        for line in file.read_text(encoding='utf-8', errors='replace').splitlines():
            match = re.fullmatch(pattern, line)
            if match and match.group(2).strip('"\'') and match.group(1) not in found:
                found[match.group(1)] = match.group(2).strip('"\'')
    return found


def telegram_settings(environ=os.environ):
    """(bot token, chat id or None) when TELEGRAM_BOT_TOKEN is set, else None."""
    found = _secrets(environ)
    if 'TELEGRAM_BOT_TOKEN' not in found:
        return None
    chat = found.get('TELEGRAM_CHAT_ID')
    if chat is not None and not re.fullmatch(r'-?\d{1,20}', chat):
        raise ValueError('TELEGRAM_CHAT_ID는 숫자여야 합니다')
    return found['TELEGRAM_BOT_TOKEN'], chat


def settings(environ=os.environ):
    """ASK_REMOTE_URL and ASK_RUNNER_TOKEN for the Vercel page."""
    found = _secrets(environ)
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
    """ask does not need the runner's own secrets."""
    return {key: value for key, value in environ.items() if key not in SECRET_KEYS}


def telegram_api(token, timeout=TELEGRAM_POLL + 15):
    """Call a Bot API method. Errors never carry the URL, which holds the token."""
    def call(method, **payload):
        request = urllib.request.Request(f'https://api.telegram.org/bot{token}/{method}',
                                         data=json.dumps(payload).encode(), method='POST',
                                         headers={'Content-Type': 'application/json'})
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                body = json.loads(response.read())
        except urllib.error.HTTPError as error:
            raise TelegramError(error.code) from None
        except urllib.error.URLError as error:
            raise OSError(f'텔레그램에 연결하지 못했습니다: {error.reason}') from None
        if not body.get('ok'):
            raise TelegramError(body.get('error_code'))
        return body['result']
    return call


class TelegramError(OSError):
    def __init__(self, code):
        super().__init__(f'텔레그램 오류 {code}')
        self.code = code


HELP = '보낼 수 있는 말:\n- 하고 싶은 일 한 줄 (예: 회원 탈퇴 추가해줘): PC의 ai가 바로 작업하고 결과를 보냅니다\n- 상태: 지금 상태를 봅니다'
NO_APPROVAL = 'ai는 승인 단계 없이 요청을 바로 실행합니다. 하고 싶은 일을 보내세요'


def describe(status):
    lines = [f"프로젝트: {status.get('project')}"]
    if status.get('busy'):
        lines.append(f"실행 중: {status['busy']}")
    return '\n'.join(lines)


class TelegramLink:
    """Turns the bot chat into the runner's post(status) -> commands. Only the owner's chat is read."""

    def __init__(self, call, chat, poll=TELEGRAM_POLL):
        self.call, self.chat, self.poll = call, str(chat), poll
        self.offset = None
        self.status = None
        self.reported = None

    def skip_old(self):
        """Messages sent while the runner was off are not run."""
        updates = self.call('getUpdates', offset=-1, timeout=0)
        if updates:
            self.offset = updates[-1]['update_id'] + 1

    def send(self, text):
        text = text if len(text) <= TELEGRAM_LIMIT else text[:TELEGRAM_LIMIT - 4] + '\n...'
        self.call('sendMessage', chat_id=self.chat, text=text)

    def report(self, status):
        last, busy = status.get('last'), status.get('busy')
        if busy and busy != (self.reported or {}).get('busy'):
            self.send(f'시작했습니다: {busy}')
        if last and last is not (self.reported or {}).get('last'):
            mark = '완료' if last.get('exitCode') == 0 else f"실패 (코드 {last.get('exitCode')})"
            self.send(f"{last.get('command')}: {mark}\n\n{last.get('summary')}\n\n{describe(status)}")
        self.reported = {'busy': busy, 'last': last}

    def __call__(self, status):
        self.status = status
        self.report(status)
        if status.get('busy'):
            return []
        options = {'timeout': self.poll, 'allowed_updates': ['message']}
        if self.offset is not None:
            options['offset'] = self.offset
        commands = []
        for update in self.call('getUpdates', **options):
            self.offset = update['update_id'] + 1
            message = update.get('message') or {}
            if str((message.get('chat') or {}).get('id')) != self.chat or not isinstance(message.get('text'), str):
                continue
            command = self.command(message['text'].strip())
            if command:
                commands.append(command)
        return commands

    def command(self, text):
        word = text.lower().lstrip('/')
        if word in ('start', 'help', '도움말'):
            self.send(HELP)
        elif word in ('status', '상태'):
            self.send(describe(self.status or {}))
        elif word in ('approve', '승인'):
            self.send(NO_APPROVAL)
        elif text:
            return {'type': 'request', 'text': text[:2000]}
        return None


def find_chat(call):
    """Chat ids of people who messaged the bot, for the first setup."""
    return sorted({str(update['message']['chat']['id']) for update in call('getUpdates', timeout=0)
                   if isinstance(update.get('message'), dict)})


class Runner:
    def __init__(self, project, post, run=subprocess.run, now=time.time):
        self.project, self.post, self.run, self.now = project, post, run, now
        self.busy = None
        self.awaiting = None  # nothing waits for approval with ai; kept for the restart hand-over
        self.last = None
        self.pending = []

    def status(self):
        return {'project': self.project.name, 'busy': self.busy, 'session': None, 'last': self.last}

    def exchange(self):
        self.pending.extend(self.post(self.status()))

    def ai(self, text):
        self.busy = '요청: ' + text[:80]
        try:
            self.exchange()
        except (OSError, ValueError, KeyError):
            pass
        # One argument, never through a shell.
        command = [sys.executable, str(SCRIPTS/'agent_pick.py'), text]
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
            code, output = self.ai(text)
            self.last = {'command': '요청: ' + text[:200], 'exitCode': code, 'summary': summary(output)}
        elif kind == 'approve':
            self.last = {'command': '승인', 'exitCode': 2, 'summary': NO_APPROVAL}
        else:
            self.last = {'command': '알 수 없는 명령', 'exitCode': 2, 'summary': '알 수 없는 명령이라 건너뛰었습니다'}

    def step(self):
        self.exchange()
        while self.pending:
            self.handle(self.pending.pop(0))
            self.exchange()


def fingerprint(path=Path(__file__).resolve()):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def restart_if_changed(started, runner, argv, environ=os.environ, current=fingerprint, execve=os.execve):
    """Run the runner's new code after an update replaced it; only between exchanges, never during an `ai` run."""
    try:
        if current() == started:
            return False
    except OSError:
        return False
    print('ask-remote 코드가 업데이트되어 새 코드로 다시 시작합니다')
    environment = {key: value for key, value in environ.items() if key != RESUME}
    if runner.awaiting:
        environment[RESUME] = runner.awaiting
    sys.stdout.flush()
    execve(sys.executable, [sys.executable, str(Path(__file__).resolve()), *argv], environment)
    return True


def main(argv=None, sleep=time.sleep):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--project')
    parser.add_argument('--interval', type=int, default=15, help='폰 명령 확인 간격(초)')
    parser.add_argument('--once', action='store_true')
    args = parser.parse_args(argv)
    try:
        telegram = telegram_settings()
        project = Path(args.project).expanduser().resolve() if args.project else _module('ask').find_project(Path.cwd().resolve())
        if telegram:
            call = telegram_api(telegram[0])
            if telegram[1] is None:
                chats = find_chat(call)
                print('TELEGRAM_CHAT_ID가 없습니다. 봇에게 아무 메시지나 보낸 뒤 다시 실행하세요.' if not chats else
                      '~/.secrets에 넣으세요: TELEGRAM_CHAT_ID=' + chats[-1])
                return 2
            link = TelegramLink(call, telegram[1], poll=0 if args.once else TELEGRAM_POLL)
            link.skip_old()
            post, target, wait = link, '텔레그램 봇', 1
        else:
            url, token = settings()
            post, target, wait = poster(url, token), url, max(5, args.interval)
    except TelegramError as error:
        print('텔레그램 봇 토큰이 맞지 않습니다' if error.code == 401 else f'시작할 수 없습니다: {error}')
        return 2
    except (ValueError, OSError) as error:
        print(f'시작할 수 없습니다: {error}')
        return 2
    runner = Runner(project, post)
    # Set only by restart_if_changed; the approval still has to match the current session.
    runner.awaiting = os.environ.pop(RESUME, None) or None
    started = fingerprint()
    if telegram:
        link.send(f'연결됐습니다: {project.name}\n\n{HELP}')
    print(f'폰 연결 시작: {project.name} → {target} (Ctrl+C로 종료)')
    while True:
        try:
            runner.step()
        except TelegramError as error:
            if error.code == 401:
                print('텔레그램 봇 토큰이 맞지 않습니다')
                return 2
            print(f'연결 오류 {error.code}, 다시 시도합니다')
        except urllib.error.HTTPError as error:
            if error.code == 401:
                print('러너 토큰이 Vercel의 ASK_RUNNER_TOKEN과 다릅니다')
                return 2
            print(f'연결 오류 {error.code}, 다시 시도합니다')
        except (OSError, ValueError, KeyError) as error:
            print(f'연결 오류, 다시 시도합니다: {error}')
        if args.once:
            return 0
        restart_if_changed(started, runner, sys.argv[1:] if argv is None else argv)
        sleep(wait)


if __name__ == '__main__':
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        sys.exit(0)
