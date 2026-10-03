"""Connect this PC's `ask` to the phone: a Telegram bot, or the phone page on Vercel. Report status, take requests
and approvals.

Every run stays on this PC with its plan logins; the phone only sees status lines and the tail of `ask` output. A
request runs `ask "<text>"` (drafts, never changes code); an approval runs `ask --yes`, and only for the session the
phone was showing, after that session's plan printed that it waits for confirmation.

    ask-remote                 inside a Gradle project
                               Telegram: TELEGRAM_BOT_TOKEN and TELEGRAM_CHAT_ID in env or ~/.secrets
                               Vercel:   ASK_REMOTE_URL and ASK_RUNNER_TOKEN in env or ~/.secrets
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
TELEGRAM_SETTINGS = ('TELEGRAM_BOT_TOKEN', 'TELEGRAM_CHAT_ID')
SECRET_KEYS = SETTINGS + TELEGRAM_SETTINGS
SUMMARY_LIMIT = 4000
TELEGRAM_LIMIT = 4000
TELEGRAM_POLL = 25
RUN_TIMEOUT = 3 * 3600


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


HELP = '보낼 수 있는 말:\n- 하고 싶은 일 한 줄 (예: 회원 탈퇴 추가해줘): 초안만 만듭니다\n- 승인: 보여준 계획대로 코드를 바꿉니다\n- 상태: 지금 상태를 봅니다'


def describe(status):
    session = status.get('session') or {}
    lines = [f"프로젝트: {status.get('project')}"]
    if status.get('busy'):
        lines.append(f"실행 중: {status['busy']}")
    if session:
        lines.append(f"작업: {session.get('name')} ({session.get('phase') or '초안'})")
        if session.get('awaitingApproval'):
            lines.append('승인을 기다립니다. "승인"을 보내면 코드를 바꿉니다')
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
        session = (self.status or {}).get('session') or {}
        if word in ('start', 'help', '도움말'):
            self.send(HELP)
        elif word in ('status', '상태'):
            self.send(describe(self.status or {}))
        elif word in ('approve', '승인'):
            if not session.get('awaitingApproval'):
                self.send('승인할 작업이 없습니다. 먼저 하고 싶은 일을 보내세요')
                return None
            return {'type': 'approve', 'session': session['name']}
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
        sleep(wait)


if __name__ == '__main__':
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        sys.exit(0)
