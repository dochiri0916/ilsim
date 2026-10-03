"""Local, noninteractive generators. Only the workflow writer applies their output."""
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import tempfile
import uuid
import time


VERSION = 1
MAX_OUTPUT_BYTES = 2 * 1024 * 1024
MODEL = re.compile(r'[A-Za-z0-9][A-Za-z0-9._:/-]{0,99}')
REASONING_EFFORTS = ('low', 'medium', 'high', 'xhigh', 'max')
TEST = re.compile(r'[A-Za-z_$][\w.$]*#[^\r\n#]+')


class OutputScopeError(ValueError):
    pass


def object_schema(properties):
    return {'type': 'object', 'properties': properties, 'required': list(properties), 'additionalProperties': False}


def array_schema(items):
    return {'type': 'array', 'items': items}


TEXT = {'type': 'string'}
TEST_IDENTIFIER = {'type': 'string', 'pattern': TEST.pattern,
                   'description': 'Fully qualified class#exact JUnit XML testcase name. For @DisplayName use its literal value; otherwise use the method name.'}
OUTPUT_SCHEMA = object_schema({
    'status': {'type': 'string', 'enum': ['COMPLETED', 'NEEDS_REVIEW']},
    'changes': array_schema(object_schema({'path': TEXT, 'content': TEXT})),
    'requirementTestMap': array_schema(object_schema({
        'requirementId': TEXT, 'scenarioId': TEXT, 'tests': array_schema(TEST_IDENTIFIER)})),
    'expectedFailures': array_schema(object_schema({
        'test': TEST_IDENTIFIER, 'kind': {'type': 'string', 'enum': ['ASSERTION', 'COMPILATION']}, 'reason': TEXT})),
    'unknowns': array_schema(TEXT),
})


def settings(value):
    allowed = {'provider', 'model', 'allowedModels', 'timeoutSeconds', 'reasoningEffort'}
    if not isinstance(value, dict) or not set(value) <= allowed or value.get('provider') not in ('codex', 'claude'):
        raise ValueError('Explicit codex or claude provider required')
    timeout = value.get('timeoutSeconds', 300)
    if type(timeout) is not int or not 1 <= timeout <= 3600:
        raise ValueError('timeoutSeconds must be an integer between 1 and 3600')
    models = value.get('allowedModels', [])
    if not isinstance(models, list) or any(not isinstance(m, str) or not MODEL.fullmatch(m) for m in models) or len(models) != len(set(models)):
        raise ValueError('Distinct valid allowedModels required')
    model = value.get('model')
    if 'model' in value and (not isinstance(model, str) or not MODEL.fullmatch(model) or model not in models):
        raise ValueError('Explicit model must be in allowedModels')
    effort = value.get('reasoningEffort')
    if 'reasoningEffort' in value and (not isinstance(effort, str) or effort not in REASONING_EFFORTS or model is None):
        raise ValueError('Reasoning effort requires an explicit model and a supported level')
    return {'provider': value['provider'], 'model': model, 'timeoutSeconds': timeout, 'allowedModels': models,
            'reasoningEffort': effort}


def parse_json(text):
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise ValueError('Duplicate JSON key')
            result[key] = value
        return result
    return json.loads(text, object_pairs_hook=pairs,
                      parse_constant=lambda _: (_ for _ in ()).throw(ValueError('Invalid JSON constant')))


def validate_output(value):
    # Validate locally too: provider-side schemas do not establish trust in CLI output.
    def check(node, schema):
        kind = schema['type']
        if kind == 'object':
            if not isinstance(node, dict) or set(node) != set(schema['properties']):
                raise ValueError('Unexpected agent output fields')
            for key, child in schema['properties'].items():
                check(node[key], child)
        elif kind == 'array':
            if not isinstance(node, list):
                raise ValueError('Expected an array')
            for item in node:
                check(item, schema['items'])
        elif not isinstance(node, str) or ('enum' in schema and node not in schema['enum']):
            raise ValueError('Invalid agent output value')
    check(value, OUTPUT_SCHEMA)
    if value['status'] == 'NEEDS_REVIEW':
        if value['changes'] or not value['unknowns'] or any(not text.strip() for text in value['unknowns']):
            raise ValueError('Review requests must not contain changes and must identify unknowns')
    elif value['unknowns']:
        raise ValueError('Unresolved unknowns cannot be completed')
    paths = set()
    for change in value['changes']:
        path = change['path']
        relative = Path(path)
        if not path or relative.is_absolute() or '..' in relative.parts or relative.as_posix() != path or '\\' in path or path in paths:
            raise OutputScopeError('Invalid or duplicate change path')
        paths.add(path)
    tests = set()
    for entry in value['expectedFailures']:
        if not TEST.fullmatch(entry['test']) or not entry['test'].split('#', 1)[1].strip() or not entry['reason'].strip() or entry['test'] in tests:
            raise ValueError('Invalid expected failure')
        tests.add(entry['test'])
    for entry in value['requirementTestMap']:
        mapped = entry['tests']
        if not mapped or len(mapped) != len(set(mapped)) or any(not TEST.fullmatch(test) or not test.split('#', 1)[1].strip() for test in mapped):
            raise ValueError('Invalid requirement test mapping')
    return value


def stop(process):
    # The group may still contain children after its leader has exited.
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        pass
    try:
        process.wait(timeout=2)
    except subprocess.TimeoutExpired:
        pass
    try:
        os.killpg(process.pid, signal.SIGKILL)
    except ProcessLookupError:
        pass
    process.wait(timeout=2)
    # SIGKILL delivery is asynchronous; wait for orphaned group members to release resources.
    deadline = time.monotonic() + 2
    while time.monotonic() < deadline:
        try:
            os.killpg(process.pid, 0)
        except ProcessLookupError:
            return
        time.sleep(0.01)


def run(config, workspace, prompt):
    config = settings(config)
    root = Path(workspace)
    if not root.is_absolute() or root.is_symlink() or root.resolve() != root or not root.is_dir() or not isinstance(prompt, str) or not prompt.strip():
        raise ValueError('Canonical workspace and nonempty prompt required')
    result = {'version': VERSION, 'attemptId': str(uuid.uuid4()), 'provider': config['provider'],
              'model': config['model'], 'reasoningEffort': config['reasoningEffort'],
              'status': 'FAILED', 'failureKind': 'INVOCATION', 'reason': 'CLI invocation failed'}
    binary = shutil.which(config['provider'])
    if not binary:
        return dict(result, status='UNAVAILABLE', failureKind='ENVIRONMENT', reason='Provider CLI is not installed')
    with tempfile.TemporaryDirectory(prefix='code-risk-agent-output-') as directory, \
            tempfile.TemporaryFile() as stdout, tempfile.TemporaryFile() as stderr:
        schema = Path(directory)/'schema.json'
        final = Path(directory)/'result.json'
        schema.write_text(json.dumps(OUTPUT_SCHEMA))
        if config['provider'] == 'codex':
            command = [binary, 'exec', '--cd', str(root), '--sandbox', 'read-only', '--ephemeral',
                       '--ignore-user-config', '--skip-git-repo-check', '--json', '--color', 'never',
                       '-c', 'shell_environment_policy.inherit="none"',
                       '--output-schema', str(schema), '--output-last-message', str(final)]
        else:
            command = [binary, '--print', '--output-format', 'json', '--no-session-persistence',
                       '--safe-mode', '--tools', '', '--strict-mcp-config', '--mcp-config', '{"mcpServers":{}}',
                       '--permission-mode', 'dontAsk', '--json-schema', json.dumps(OUTPUT_SCHEMA)]
        if config['model']:
            command.extend(['--model', config['model']])
        if config['reasoningEffort']:
            command.extend(['-c', 'model_reasoning_effort=' + json.dumps(config['reasoningEffort'])]
                           if config['provider'] == 'codex' else ['--effort', config['reasoningEffort']])
        if config['provider'] == 'codex':
            command.append('-')
        try:
            process = subprocess.Popen(command, cwd=root, stdin=subprocess.PIPE, stdout=stdout, stderr=stderr,
                                       text=True, encoding='utf-8', start_new_session=True,
                                       env={key: value for key, value in os.environ.items() if key != 'TYPESAFE_API_KEY'})
        except OSError:
            return result
        try:
            process.communicate(input=prompt, timeout=config['timeoutSeconds'])
        except subprocess.TimeoutExpired:
            stop(process)
            return dict(result, status='TIMED_OUT', failureKind='TIMEOUT', reason='Provider exceeded timeoutSeconds')
        except BaseException:
            stop(process)
            raise
        finally:
            if process.stdin and not process.stdin.closed:
                process.stdin.close()
        stop(process)
        if process.returncode != 0:
            if config['provider'] == 'claude' and stdout.tell() <= MAX_OUTPUT_BYTES:
                try:
                    stdout.seek(0)
                    error = parse_json(stdout.read().decode('utf-8'))
                    if isinstance(error, dict) and error.get('is_error') is True \
                            and 'insufficient balance' in str(error.get('result', '')).lower():
                        return dict(result, status='UNAVAILABLE', failureKind='BILLING',
                                    reason='Provider account balance is insufficient', exitCode=process.returncode)
                except (ValueError, UnicodeError):
                    pass
            return dict(result, exitCode=process.returncode)
        if stdout.tell() > MAX_OUTPUT_BYTES or (final.exists() and final.stat().st_size > MAX_OUTPUT_BYTES):
            return dict(result, status='INVALID_OUTPUT', failureKind='OUTPUT_LIMIT', reason='Provider output exceeds size limit')
        failure = 'PROVIDER_FAILURE'
        payload = None
        try:
            stdout.seek(0)
            output = stdout.read().decode('utf-8')
            if config['provider'] == 'codex':
                events = [parse_json(line) for line in output.splitlines() if line.strip()]
                if not events or any(not isinstance(event, dict) for event in events) or events[-1].get('type') != 'turn.completed' \
                        or any(event.get('type') in ('error', 'turn.failed') for event in events):
                    raise ValueError('Codex turn did not complete successfully')
                failure = 'OUTPUT_CONTRACT'
                payload = parse_json(final.read_text())
            else:
                envelope = parse_json(output)
                if not isinstance(envelope, dict) or envelope.get('type') != 'result' or envelope.get('subtype') != 'success' \
                        or envelope.get('is_error') is not False:
                    raise ValueError('Claude turn did not complete successfully')
                failure = 'OUTPUT_CONTRACT'
                payload = envelope['structured_output']
            validate_output(payload)
            return dict(result, status=payload['status'], failureKind='' if payload['status'] == 'COMPLETED' else 'AMBIGUITY',
                        reason='', output=payload)
        except (ValueError, OSError, KeyError, UnicodeError) as error:
            if isinstance(error, OutputScopeError):
                failure = 'SCOPE'
            elif isinstance(payload, dict) and payload.get('status') == 'NEEDS_REVIEW':
                failure = 'AMBIGUITY'
            return dict(result, status='INVALID_OUTPUT', failureKind=failure, reason='Provider returned an invalid or incomplete result')
