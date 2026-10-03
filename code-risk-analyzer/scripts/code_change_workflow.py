#!/usr/bin/env python3
"""Persistent, explicit CODE_CHANGE stage controller over the existing executors."""
import argparse
import fcntl
import hashlib
import importlib.util
import json
import contextlib
import io
import os
from pathlib import Path
import shutil
import signal
import sys
import subprocess
import re
import tempfile
import time
import math


def module(name):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(name + '.py'))
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


regression = module('regression_workflow')
evidence = module('evidence_review')
agent = module('agent_runner')
router = module('model_router')
jev = module('jev_capability')
failure = module('validation_failure')
quality = module('quality_gate')
calibration = module('calibrate_jev')
service_adapter = module('service_adapter')
FIXTURE_PROJECT = Path(__file__).resolve().parents[1]/'integration-fixtures/spring-risk-sample'
IMPORT = re.compile(r'^\s*import\s+(static\s+)?([\w.]+)\s*;', re.M)
# Libraries generated tests tend to reach for, and the build-file text that shows the project declares them.
LIBRARIES = (('org.springframework.security.', re.compile(r'security')), ('org.testcontainers.', re.compile(r'testcontainers')),
             ('io.jsonwebtoken.', re.compile(r'jjwt')), ('org.springframework.data.redis.', re.compile(r'redis')),
             ('org.springframework.kafka.', re.compile(r'kafka')), ('com.querydsl.', re.compile(r'querydsl')),
             ('org.mindrot.', re.compile(r'jbcrypt')))


JAVAC_ERROR = re.compile(r'^(\S+\.java):(\d+): error: (.+)$')
PROBE_INIT = """allprojects {
    tasks.withType(JavaCompile).configureEach { options.compilerArgs += ['-Xmaxerrs', '10000'] }
}
"""


def library_misuse(output, project_types):
    """javac errors for a method or field that a non-project type lacks, e.g. an assertion AssertJ does not have.

    Tests are compiled before their production code exists, so errors about project types are expected; an unknown
    member of a library type is not, and no implementation can fix it.
    """
    lines, errors = output.splitlines(), []
    for index, line in enumerate(lines):
        match = JAVAC_ERROR.match(line.strip())
        if not match or match.group(3) != 'cannot find symbol':
            continue
        block = lines[index + 1:index + 6]
        symbol = next((item.split(':', 1)[1].strip() for item in block if item.strip().startswith('symbol:')), '')
        location = next((item.split(':', 1)[1].strip() for item in block if item.strip().startswith('location:')), '')
        if not symbol.startswith(('method ', 'variable ')) or not location:
            continue
        owner = location.rsplit(' of type ', 1)[1] if ' of type ' in location else location.split(' ', 1)[-1]
        owner = owner.split('<', 1)[0].rsplit('.', 1)[-1].strip()
        if owner and owner not in project_types:
            errors.append(f'{Path(match.group(1)).name}:{match.group(2)} {symbol} is not a member of {owner}')
    return errors


class RepairStop(ValueError):
    pass


def repair_settings(value):
    defaults = {'maxIterations': 3, 'maxModelCalls': 6, 'timeoutSeconds': 900}
    if not isinstance(value, dict) or not set(value) <= set(defaults):
        raise ValueError('Invalid repair settings')
    result = dict(defaults, **value)
    for name, maximum in (('maxIterations', 3), ('maxModelCalls', 12), ('timeoutSeconds', 3600)):
        if type(result[name]) is not int or not 1 <= result[name] <= maximum:
            raise ValueError('Invalid repair limit')
    return result


def fixture_tools():
    # Fixed repository fixture only, never a caller-selected Python script or command.
    directory = FIXTURE_PROJECT/'scripts'
    sys.path.insert(0, str(directory))
    try:
        spec = importlib.util.spec_from_file_location('workflow_fixture_tools', directory/'verify_mysql.py')
        tools = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(tools)
        return tools
    finally:
        sys.path.pop(0)


class Controller:
    def __init__(self, config, state_path):
        required = {'plan', 'requirements', 'analyzer', 'scope', 'documents'}
        if not isinstance(config, dict) or not required <= set(config) or not set(config) <= required | {'fixtureAdapter', 'serviceAdapter', 'agent', 'routing', 'qualityGates', 'repair'}:
            raise ValueError('Explicit plan, requirements, analyzer, scope and documents required')
        if 'agent' in config and 'routing' in config:
            raise ValueError('Choose direct agent configuration or tier routing')
        self.config = config
        self.paths = {key: Path(config[key]).resolve() for key in ('plan', 'requirements', 'analyzer', 'scope')}
        self.plan = regression.validate_plan(regression.read_json(self.paths['plan']))
        # Reject document modes before touching target code, hashes, scope or executors.
        if regression.read_json(self.paths['requirements']).get('mode') != 'CODE_CHANGE':
            raise ValueError('CODE_CHANGE requirements required')
        self.project = Path(self.plan['project'])
        self.fixture_adapter = config.get('fixtureAdapter')
        if 'fixtureAdapter' in config and (self.fixture_adapter != 'MYSQL' or self.project != FIXTURE_PROJECT):
            raise ValueError('MYSQL adapter is restricted to the repository fixture')
        if 'fixtureAdapter' in config and 'serviceAdapter' in config:
            raise ValueError('Choose the fixture adapter or the general service adapter')
        self.service_adapter = service_adapter.settings(config['serviceAdapter']) if 'serviceAdapter' in config else None
        if self.project.is_symlink() or self.project.resolve() != self.project or not (self.project/'gradlew').is_file():
            raise ValueError('Canonical Gradle project required')
        self.state_path = Path(state_path).absolute()
        if self.state_path.resolve() != self.state_path or not self.state_path.is_relative_to(self.project/'build') or any(p.is_symlink() for p in [self.state_path, *self.state_path.parents]):
            raise ValueError('Session must be under project build without symlinks')
        self.directory = self.state_path.parent
        if self.state_path.name != 'state.json':
            raise ValueError('Use a dedicated session directory with state.json')
        documents = config['documents']
        if not isinstance(documents, list) or not documents or any(not isinstance(d, str) for d in documents) or len(set(documents)) != len(documents):
            raise ValueError('Declare distinct relevant requirement documents')
        self.scope = regression.read_json(self.paths['scope'])
        if set(self.scope) != {'project', 'files', 'actions'} or self.scope['project'] != str(self.project):
            raise ValueError('Scope project must match plan')
        for key in ('files', 'actions'):
            values = self.scope[key]
            if not isinstance(values, list) or any(not isinstance(v, str) or not v for v in values) or len(set(values)) != len(values):
                raise ValueError('Distinct scope entries required')
        self.quality = quality.settings(config['qualityGates']) if 'qualityGates' in config else None
        if self.quality and not set(self.quality['targets'].values()) <= set(self.scope['files']):
            raise ValueError('Quality targets must be approved production files')
        self.repair_config = repair_settings(config['repair']) if 'repair' in config else None
        if self.repair_config and (not self.quality or not ('agent' in config or 'routing' in config)):
            raise ValueError('Repair requires explicit quality gates and an agent or router')
        self.repair_active = False
        if 'agent' in config:
            agent.settings(config['agent'])
        if 'routing' in config:
            self.routing = router.settings(config['routing'])
            if 'capability' in self.routing and 'calibration' in self.routing['capability']:
                self.calibration_path = Path(self.routing['capability']['calibration']).resolve()
                self.calibration = calibration.validate_receipt(self.calibration_path, self.routing['capability'])
            self.routing_paths = {name: Path(self.routing[name]).resolve() for name in ('registry', 'policy')}
            contents = {name: path.read_bytes() for name, path in self.routing_paths.items()}
            self.routing_hashes = {name: hashlib.sha256(content).hexdigest() for name, content in contents.items()}
            self.registry = router.validate_registry(agent.parse_json(contents['registry'].decode('utf-8')))
            self.policy = router.validate_policy(agent.parse_json(contents['policy'].decode('utf-8')), self.registry)
            tiers = self.routing['requiredTiers'].values() if 'requiredTiers' in self.routing else router.TIERS
            for tier in tiers:
                route = self.policy['tiers'].get(tier)
                if route is None:
                    raise ValueError('Explicit routes required for every possible capability tier')
                for attempt in range(1 + len(route.get('fallbacks', []))):
                    selected, _ = router.select(self.registry, self.policy, tier, self.routing['timeoutSeconds'], attempt)
                    agent.settings(selected)

    def _agent_context_paths(self):
        paths = set(self.scope['files']) | set(self.config['documents'])
        for path in self.scope['files']:
            relative = Path(path)
            if relative.is_absolute() or '..' in relative.parts or relative.as_posix() != path or '\\' in path or not path.startswith('src/'):
                raise ValueError('Agent context must contain normalized src paths')
            target = self.project/relative
            if any(parent.is_symlink() for parent in [target, *target.parents]):
                raise ValueError('Symlink agent context forbidden')
        rules = {'.code-risk.yml', 'AGENTS.md', 'build.gradle', 'build.gradle.kts', 'settings.gradle', 'settings.gradle.kts'}
        for path in paths:
            relative = Path(path)
            if relative.is_absolute() or '..' in relative.parts:
                raise ValueError('Agent context must be project relative')
            rules.update((parent/'AGENTS.md').as_posix() for parent in relative.parents)
        return paths | rules

    def _inputs(self):
        result = {'config': evidence.fingerprint(self.config),
                'metadata': {key: regression.digest(path) for key, path in self.paths.items()},
                'documents': {path: regression.digest(evidence.safe_path(self.project, path)) for path in self.config['documents']}}
        if 'agent' in self.config or 'routing' in self.config:
            tools = ['agent_runner.py', 'code_change_workflow.py', 'model_router.py']
            if 'routing' in self.config and 'capability' in self.routing:
                tools.append('jev_capability.py')
                if 'calibration' in self.routing['capability']:
                    tools.append('calibrate_jev.py')
                    result['calibrationSha256'] = regression.digest(self.calibration_path)
            if self.quality or self.repair_config:
                tools += ['regression_workflow.py', 'validation_failure.py', 'quality_gate.py']
            result['agentTools'] = {name: regression.digest(Path(__file__).with_name(name))
                                    for name in tools}
            result['agentRules'] = {path: regression.digest(evidence.safe_path(self.project, path))
                                   if (self.project/path).exists() else 'ABSENT'
                                   for path in self._agent_context_paths() if path not in self.scope['files']}
        if 'routing' in self.config:
            result['routing'] = {name: regression.digest(path) for name, path in self.routing_paths.items()}
        if self.service_adapter:
            result['serviceFiles'] = service_adapter.scenario_files(self.project, self.service_adapter)
            result['serviceAdapterSha256'] = regression.digest(Path(__file__).with_name('service_adapter.py'))
        return result

    def _save(self):
        regression.save(self.state_path, self.state)

    def _record(self, phase, status):
        self.state['phase'] = phase
        self.state['history'].append({'phase': phase, 'status': status})
        self._save()

    def _invalidate(self, reason, kind):
        self.state['invalidations'].append({'reason': reason, 'kind': kind, 'previousPhase': self.state['phase']})
        self.state['staleKind'] = kind
        for key in ('completion', 'verified', 'reviewed', 'serviceSha256'):
            self.state.pop(key, None)
        self._record('STALE', 'NEEDS_REVIEW')

    def execute(self, stage, **kwargs):
        generation = {'generate-tests': 'test-writing', 'generate-implementation': 'implementation'}.get(stage)
        if generation:
            stage = generation
        token = hashlib.sha256(str(self.project).encode()).hexdigest()
        lock_path = Path(tempfile.gettempdir()) / ('code-risk-regression-' + token + '.lock')
        with os.fdopen(os.open(lock_path, os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600), 'w') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            if stage == 'baseline':
                return self._baseline()
            if not self.state_path.exists():
                raise ValueError('Successful baseline required first')
            self.state = regression.read_json(self.state_path)
            if self.state.get('version') != 1 or self.state.get('project') != str(self.project):
                raise ValueError('Invalid session')
            if self.state.get('baselineStatus') != 'PASSED':
                raise ValueError('Failed baseline blocks writing')
            try:
                inputs = self._inputs()
            except (ValueError, OSError):
                self._invalidate('Requirement or metadata missing', 'INPUTS')
                raise ValueError('Session inputs unavailable')
            if inputs != self.state['inputs']:
                self._invalidate('Requirement, scope, plan or analyzer changed', 'INPUTS')
                raise ValueError('Changed inputs require a new explicitly reviewed session')
            if self.state.get('staleKind') == 'INPUTS':
                raise ValueError('Input-invalidated session cannot reuse prior approvals')
            if regression.digest(self.directory/'baseline.json') != self.state['baselineSha256']:
                self._invalidate('Original baseline changed', 'INPUTS')
                raise ValueError('Original baseline cannot be replaced')
            if regression.source_hashes(self.project) != self.state['sources']:
                self.state['sources'] = regression.source_hashes(self.project)
                self._invalidate('Source or test changed outside the recorded operation', 'SOURCES')
                raise ValueError('Source drift invalidated execution and review')
            allowed = {
                'test-writing': {'BASELINED', 'TEST_WRITING', 'IMPLEMENTATION', 'VERIFIED', 'REVIEWED', 'COMPLETED', 'STALE'},
                'implementation': {'TEST_WRITING', 'IMPLEMENTATION'},
                'verification': {'IMPLEMENTATION', 'VERIFIED', 'REVIEWED', 'COMPLETED', 'STALE'},
                'repair': {'IMPLEMENTATION', 'VERIFIED'},
                'review': {'VERIFIED', 'REVIEWED'}, 'complete': {'REVIEWED'},
                'local-service': {'VERIFIED', 'REVIEWED', 'COMPLETED'},
                'pull-request': {'COMPLETED'},
            }
            if generation and self.state['phase'] == 'STALE' and self.state.get('generationStage') == stage:
                allowed[stage].add('STALE')
            if stage not in allowed or self.state['phase'] not in allowed[stage]:
                raise ValueError('Stage transition rejected')
            if stage == 'verification' and not any(h['phase'] == 'IMPLEMENTATION' and h['status'] == 'COMPLETED' for h in self.state['history']):
                raise ValueError('Implementation stage required before verification')
            if stage in ('test-writing', 'implementation'):
                if generation:
                    return self._generate(stage)
                return self._writing(stage, kwargs['changes'])
            if stage == 'verification':
                return self._verification(kwargs.get('approvals', []))
            if stage == 'repair':
                return self._repair(kwargs.get('approvals', []))
            self._fresh_execution()
            if stage == 'local-service':
                return self._local_service(kwargs.get('container'))
            if stage == 'review':
                return self._review(**kwargs)
            if stage == 'pull-request':
                return self._pull_request(kwargs.get('pull_request'))
            return self._complete()

    def _baseline(self):
        if self.state_path.exists() or (self.directory/'baseline.json').exists():
            raise ValueError('Use a new session; baseline cannot be overwritten')
        if not {'BASELINE', 'REGRESSION', 'LOCAL_SERVICE', 'COMPLETE'} <= set(self.plan['approvedActions']):
            raise ValueError('Approve the full declared verification scope')
        regression.validate_requirements(self.paths['requirements'], self.paths['analyzer'])
        inputs = self._inputs()
        snapshot = regression.snapshot(self.project, self.plan, coverage=True) if self.quality else regression.snapshot(self.project, self.plan)
        check = regression.compare(snapshot, snapshot)
        if self._inputs() != inputs:
            check = {'status': 'FAILED', 'failures': ['Inputs changed during baseline']}
        regression.save(self.directory/'baseline.json', snapshot, exclusive=True)
        self.state = {'version': 1, 'project': str(self.project), 'inputs': inputs,
                      'baselineSha256': regression.digest(self.directory/'baseline.json'),
                      'baselineStatus': check['status'], 'sources': snapshot['sources'],
                      'history': [], 'invalidations': []}
        self._record('BASELINED' if check['status'] == 'PASSED' else 'BLOCKED', check['status'])
        return check

    def _writing(self, stage, changes, expected_failures=()):
        action = 'WRITE_TESTS' if stage == 'test-writing' else 'IMPLEMENT'
        if action not in self.scope['actions']:
            raise ValueError('Write action outside approved scope')
        if not isinstance(changes, dict) or set(changes) != {'changes'} or not isinstance(changes['changes'], list) or not changes['changes']:
            raise ValueError('Nonempty changes required')
        seen = set()
        for change in changes['changes']:
            if not isinstance(change, dict) or set(change) != {'path', 'expectedSha256', 'content'} or any(not isinstance(v, str) for v in change.values()):
                raise ValueError('Invalid change record')
            path = change['path']
            if path in seen or path not in self.scope['files']:
                raise ValueError('Unapproved or duplicate change')
            seen.add(path)
            relative = Path(path)
            target = self.project/relative
            if relative.is_absolute() or '..' in relative.parts or relative.as_posix() != path or '\\' in path or any(p.is_symlink() for p in [target, *target.parents]):
                raise ValueError('Unsafe change path')
            if not path.startswith('src/test/' if action == 'WRITE_TESTS' else 'src/main/'):
                raise ValueError('Test writing may modify only src/test; implementation only src/main')
            actual = regression.digest(target) if target.is_file() else 'ABSENT'
            if actual != change['expectedSha256']:
                raise ValueError('Change source hash mismatch')
        # Clear downstream acceptance before invoking a possibly partially successful writer.
        for key in ('verified', 'reviewed', 'completion', 'staleKind', 'serviceSha256'):
            self.state.pop(key, None)
        self._record('WRITING', 'RUNNING')
        try:
            result = self._write(action, changes)
        except (ValueError, OSError, subprocess.SubprocessError):
            self.state['sources'] = regression.source_hashes(self.project)
            self._invalidate('Writer failed; inspect partial changes and submit fresh hashes', 'SOURCES')
            raise
        current_sources = regression.source_hashes(self.project)
        changed_paths = {path for path in set(current_sources) | set(self.state['sources'])
                         if current_sources.get(path) != self.state['sources'].get(path)}
        self.state['sources'] = current_sources
        if self._inputs() != self.state['inputs']:
            self._invalidate('Inputs changed during writing', 'INPUTS')
            raise ValueError('Writer input drift')
        if not changed_paths <= seen:
            self._invalidate('Files outside write plan changed during writing', 'SOURCES')
            raise ValueError('Writer source drift')
        phase = 'TEST_WRITING' if action == 'WRITE_TESTS' else 'IMPLEMENTATION'
        if result.get('status') != 'COMPLETED':
            self._invalidate('Writer did not complete; later stages blocked', 'SOURCES')
            return result
        if action == 'WRITE_TESTS':
            red = self._red_phase(expected_failures)
            if red['status'] != 'PASSED':
                self._invalidate('New tests passed before implementation', 'SOURCES')
                return {'status': 'NEEDS_REVIEW', 'failures': red['failures']}
            # Later stages may not change the accepted tests or build inputs; only test-writing re-accepts them.
            self.state['lockedTests'] = regression.protected_sources(self.state['sources'])
        self._record(phase, 'COMPLETED')
        return result

    def _red_phase(self, expected_failures):
        # Tests first: every new-feature or expected-failure test must not pass before implementation.
        tests = sorted({test for data in self.plan['newFeatures'].values() for test in data['domain'] + data['mysql']}
                       | set(expected_failures))
        snapshot = regression.snapshot(self.project, self.plan)
        if snapshot['observedSources'] != self.state['sources']:
            self._invalidate('Source changed during pre-implementation run', 'SOURCES')
            raise ValueError('Source drift during pre-implementation run')
        observed = {test: 'PASSED' if regression.successful(snapshot['tests'], test) else 'NOT_PASSED' for test in tests}
        failures = ['Passed before implementation: ' + test for test, state in observed.items() if state == 'PASSED']
        red = {'version': 1, 'status': 'NEEDS_REVIEW' if failures else 'PASSED', 'tests': observed, 'failures': failures,
               'sources': snapshot['sources'], 'buildExitCode': snapshot['buildExitCode'],
               'meaning': 'NOT_PASSED covers failed, skipped, missing or uncompiled tests; a passing test describes existing behavior'}
        regression.save(self.directory/'red.json', red)
        self.state['redSha256'] = regression.digest(self.directory/'red.json')
        return red

    def _generate(self, stage):
        if 'agent' not in self.config and 'routing' not in self.config:
            raise ValueError('Explicit agent configuration required')
        if 'routing' in self.config:
            if self.routing_hashes != self.state['inputs']['routing']:
                self._invalidate('Loaded routing differs from session inputs', 'INPUTS')
                raise ValueError('Loaded routing is stale')
        action = 'WRITE_TESTS' if stage == 'test-writing' else 'IMPLEMENT'
        prefix = 'src/test/' if stage == 'test-writing' else 'src/main/'
        allowed = [path for path in self.scope['files'] if path.startswith(prefix)]
        if action not in self.scope['actions'] or not allowed:
            raise ValueError('Agent generation requires approved files and action')
        if stage == 'test-writing' and any(h['phase'] == 'IMPLEMENTATION' for h in self.state['history']):
            raise ValueError('Test generator cannot consume later implementation context; use a new session')
        requirements = regression.read_json(self.paths['requirements'])
        for requirement in requirements['requirements'].values():
            if requirement['review']['openQuestions'] or requirement['review']['status'] in ('CONTRADICTED', 'INSUFFICIENT_EVIDENCE') \
                    or any(requirement[p]['applicability'] == 'UNKNOWN' for p in ('transaction', 'concurrency', 'idempotency', 'consistency')):
                raise ValueError('Resolve requirement ambiguities before agent generation')
        context = {}
        for path in sorted(self._agent_context_paths()):
            if (self.project/path).exists():
                context[path] = evidence.safe_path(self.project, path).read_text()
        accepted = None
        if stage == 'implementation' and 'agent-test-writing.json' in self.state.get('agentArtifacts', {}):
            path = self.directory/'agent-test-writing.json'
            if regression.digest(path) != self.state['agentArtifacts'][path.name]:
                self._invalidate('Generated test contract changed', 'ARTIFACTS')
                raise ValueError('Generated test contract changed')
            accepted = regression.read_json(path)['output']['requirementTestMap']
        instructions = ('Create implementation-independent tests for every requirement scenario. Include expected pre-implementation failures. '
                        'Test IDs must be class#exact JUnit XML reported name: use the literal @DisplayName text if present, otherwise the method name. '
                        'Return requirementTestMap and expectedFailures. Do not change production code. '
                        # Tests come first, so they define the shape of code that does not exist yet.
                        'You decide the public API of production types that do not exist yet: their package (from the approved '
                        'files), constructors or factories, method signatures, accessors, exception types and messages. Follow '
                        'the repository rules and existing code, choose conventional names, and use them consistently. This is '
                        'not ambiguity; return NEEDS_REVIEW only when a business rule or observable outcome (input rules, '
                        'status codes, results) is undecided by the requirements. allowedFiles are the test files you write; use only '
                        'production types whose files are listed in approvedProductionFiles or already exist (implementation may '
                        'create nothing else), and only the JDK and '
                        'dependencies the build already declares.' if stage == 'test-writing' else
                        'Implement the minimum change satisfying the accepted tests and requirements. Do not change any test. '
                        'Production types must match exactly the packages, signatures, exception types and messages the tests use. '
                        'Return empty requirementTestMap and expectedFailures; empty changes are allowed if no fix is needed.')
        task = {'stage': stage, 'requirements': requirements, 'allowedFiles': allowed,
                'contextFiles': context, 'acceptedTests': accepted}
        if stage == 'test-writing':
            # Tests define the production types implementation will create, so the writer must see those files.
            task['approvedProductionFiles'] = [path for path in self.scope['files'] if path.startswith('src/main/')]
        if self.repair_active:
            task['failureHistory'] = [item['classification'] for item in self.state['repair']['history']]
        prompt = ('Return JSON conforming to the supplied output schema. Propose complete file contents only; do not edit files or run tools. '
                  'Follow the provided repository rules. Do not invent observable behavior, weaken assertions, suppress findings, '
                  'or include credentials. If the contract is ambiguous, return NEEDS_REVIEW with unknowns and no changes. '
                  # A COMPLETED result with any unknown is rejected, and models tend to add notes like "tests not run".
                  'With status COMPLETED, unknowns must be empty: the tool compiles and runs the code itself, so never '
                  'note that you did not compile, run or verify something.\n'
                  + instructions + '\n' + json.dumps(task, ensure_ascii=False))
        if len(prompt.encode()) > agent.MAX_OUTPUT_BYTES:
            raise ValueError('Agent context too large; narrow the approved scope')
        sources = dict(self.state['sources'])
        for key in ('verified', 'reviewed', 'completion', 'serviceSha256'):
            self.state.pop(key, None)
        self.state['generationStage'] = stage
        self._record('STALE', 'GENERATING')
        capability = None
        tier = None
        if 'routing' in self.config:
            if 'capability' in self.routing:
                try:
                    capability_config = dict(self.routing['capability'])
                    if self.repair_active:
                        capability_config['timeoutSeconds'] = self._repair_timeout(capability_config['timeoutSeconds'])
                        self._repair_call()
                    capability = jev.judge(capability_config, task, agent.parse_json)
                except BaseException:
                    self._invalidate('Capability evaluation interrupted or failed', 'SOURCES')
                    raise
                self._generation_inputs(sources)
                if capability['status'] == 'COMPLETED' and hasattr(self, 'calibration'):
                    snapshots = {record['result'].get('modelSnapshot') for record in
                                 self.calibration['profiles'][str(self.routing['capability'].get('questionVersion', 1))]['records']}
                    capability['calibrationSha256'] = self.state['inputs']['calibrationSha256']
                    if capability.get('modelSnapshot') not in snapshots:
                        capability.update(status='NEEDS_REVIEW', failureKind='UNCALIBRATED_SNAPSHOT')
                if capability['status'] == 'COMPLETED':
                    tier = capability['requiredTier']
            else:
                tier = self.routing['requiredTiers'][stage]
        attempts = []
        maximum = self.routing['maxAttempts'] if 'routing' in self.config else 1
        artifact = self.directory/('agent-' + stage + '.json')
        for attempt in range(maximum):
            decision = None
            route_attempt = attempt
            if self.repair_active and tier is not None:
                ledger = self.state['repair']
                previous = ledger.get('requiredTier')
                if previous:
                    tier = router.TIERS[max(router.TIERS.index(tier), router.TIERS.index(previous))]
                ledger['requiredTier'] = tier
                route_attempt = ledger.get('routeAttempt', -1) + 1
                ledger['routeAttempt'] = route_attempt
                self._save()
            if 'routing' in self.config and tier is not None:
                try:
                    selected, decision = router.select(self.registry, self.policy, tier, self.routing['timeoutSeconds'], route_attempt)
                except ValueError:
                    if self.repair_active:
                        raise RepairStop('Declared routing fallback exhausted')
                    raise
                decision.update(version=router.VERSION, registrySha256=self.routing_hashes['registry'],
                                policySha256=self.routing_hashes['policy'], attempt=route_attempt + 1)
            else:
                selected = dict(self.config['agent']) if 'agent' in self.config else None
            current_prompt = prompt
            if attempts:
                current_prompt += '\nPrior output failed its response contract. Correct the reported contract failure while preserving the requirements, tests and scope.\n'
                current_prompt += json.dumps({'failureHistory': [{key: item[key] for key in ('number', 'status', 'failureKind', 'detail')
                                                                  if key in item} for item in attempts]})
            if len(current_prompt.encode()) > agent.MAX_OUTPUT_BYTES:
                self._invalidate('Retry context exceeds size limit', 'SOURCES')
                return {'status': 'NEEDS_REVIEW', 'agentStatus': 'INPUT_LIMIT'}
            if selected is None:
                result = {'status': 'NEEDS_REVIEW', 'failureKind': 'CAPABILITY', 'reason': 'Capability evaluation did not authorize routing'}
            else:
                if self.repair_active:
                    selected['timeoutSeconds'] = self._repair_timeout(selected.get('timeoutSeconds', 300))
                    self._repair_call()
                with tempfile.TemporaryDirectory(prefix='code-risk-agent-workspace-') as directory:
                    workspace = Path(directory).resolve()
                    for path, content in context.items():
                        target = workspace/path
                        target.parent.mkdir(parents=True, exist_ok=True)
                        target.write_text(content)
                    try:
                        result = agent.run(selected, workspace, current_prompt)
                    except BaseException:
                        self._invalidate('Agent invocation interrupted or failed', 'SOURCES')
                        raise
            rejected = False
            scope_rejected = False
            if result['status'] == 'COMPLETED':
                try:
                    output = agent.validate_output(result['output'])
                    if any(change['path'] not in allowed for change in output['changes']):
                        scope_rejected = True
                        raise ValueError('Agent proposed changes outside the stage scope')
                    if stage == 'test-writing':
                        self._validate_generated_tests(output, requirements)
                        self._validate_test_references(output)
                        self._compile_probe(output)
                        if not output['changes']:
                            raise ValueError('Test generation requires changes')
                    elif output['requirementTestMap'] or output['expectedFailures']:
                        raise ValueError('Implementation cannot replace the accepted test contract')
                except (ValueError, KeyError, TypeError) as error:
                    rejected = True
                    scope_rejected = scope_rejected or isinstance(error, agent.OutputScopeError)
                    result['rejectedOutput'] = result.pop('output', None)
                    result.update(status='INVALID_OUTPUT', failureKind='SCOPE' if scope_rejected else 'OUTPUT_CONTRACT',
                                  reason='Agent change contract rejected', detail=f'{type(error).__name__}: {error}'[:500])
            prompt_hash = hashlib.sha256(current_prompt.encode()).hexdigest()
            summary = {'number': attempt + 1, 'status': result['status'], 'failureKind': result.get('failureKind', ''),
                       'promptSha256': prompt_hash}
            if result.get('detail'):
                summary['detail'] = result['detail']
            for key in ('attemptId', 'provider', 'model', 'reasoningEffort', 'usage', 'durationSeconds'):
                if key in result:
                    summary[key] = result[key]
            if decision is not None:
                summary['routing'] = dict(decision)
                result['routing'] = decision
            attempts.append(summary)
            result.update(stage=stage, sourceHashes=sources, inputHashes=self.state['inputs'], promptSha256=prompt_hash,
                          attempts=attempts)
            if capability is not None:
                result['capability'] = capability
            regression.save(artifact, result)
            self.state.setdefault('agentArtifacts', {})[artifact.name] = regression.digest(artifact)
            self._save()
            self._generation_inputs(sources)
            if result['status'] == 'COMPLETED':
                break
            has_fallback = tier is not None and route_attempt < len(self.policy['tiers'][tier].get('fallbacks', []))
            if result.get('failureKind') == 'OUTPUT_CONTRACT' and result['status'] == 'INVALID_OUTPUT' \
                    and attempt + 1 < maximum and has_fallback:
                continue
            self._invalidate('Agent did not produce an accepted change', 'SOURCES')
            if scope_rejected or (rejected and maximum == 1):
                raise ValueError('Agent change contract rejected')
            return {'status': 'NEEDS_REVIEW', 'agentStatus': result['status']}
        if not output['changes']:
            self.state.pop('generationStage', None)
            self._record('IMPLEMENTATION', 'COMPLETED')
            return {'status': 'COMPLETED', 'files': []}
        changes = {'changes': [dict(change, expectedSha256=sources.get(change['path'], 'ABSENT')) for change in output['changes']]}
        expected = [entry['test'] for entry in output['expectedFailures']] if stage == 'test-writing' else []
        result = self._writing(stage, changes, expected)
        if result['status'] == 'COMPLETED':
            self.state.pop('generationStage', None)
            self._save()
        return result

    def _generation_inputs(self, sources):
        # Check every external call before applying changes or spending another attempt.
        try:
            inputs = self._inputs()
            current_sources = regression.source_hashes(self.project)
        except (ValueError, OSError):
            self._invalidate('Generation inputs unavailable', 'INPUTS')
            raise ValueError('Generation inputs unavailable')
        if inputs != self.state['inputs'] or current_sources != sources:
            self._invalidate('Inputs changed during agent generation', 'INPUTS')
            raise ValueError('Generation inputs drifted')

    def _validate_test_references(self, output):
        """Tests may only use project types the approved scope or the project provides, and declared libraries.

        Otherwise implementation is asked to satisfy tests it is not allowed to (types outside its files, a dependency
        it cannot add) and can only stop.
        """
        base = module('convention_templates').base_package(self.project)
        build = '\n'.join(path.read_text(encoding='utf-8', errors='replace')
                          for path in (self.project/'build.gradle', self.project/'build.gradle.kts') if path.is_file())
        written = {change['path'] for change in output['changes']}
        missing, libraries = set(), set()
        for change in output['changes']:
            for static, name in IMPORT.findall(change['content']):
                if name.endswith('.*'):
                    continue
                if base and name.startswith(base + '.'):
                    parts = name.split('.')
                    # A static import names a member; the type is the last capitalized segment.
                    while static and parts and not parts[-1][:1].isupper():
                        parts.pop()
                    while len(parts) > 1 and parts[-2][:1].isupper():
                        parts.pop()  # nested type: the file is named after the outer type
                    relative = '/'.join(parts) + '.java'
                    paths = ('src/main/java/' + relative, 'src/test/java/' + relative)
                    if not any((self.project/path).is_file() or path in self.scope['files'] or path in written for path in paths):
                        missing.add('.'.join(parts))
                for prefix, declared in LIBRARIES:
                    if name.startswith(prefix) and not declared.search(build):
                        libraries.add(prefix.rstrip('.'))
        problems = []
        if missing:
            problems.append('tests use project types that are neither in the project nor in the approved files '
                            '(use an approved type instead, or put the logic in one): ' + ', '.join(sorted(missing)))
        if libraries:
            problems.append('tests use libraries the build does not declare (use the JDK or a declared dependency): '
                            + ', '.join(sorted(libraries)))
        if problems:
            raise ValueError('; '.join(problems))

    def _compile_probe(self, output):
        """Compile the proposed tests in a copy of the project; reject library members that do not exist."""
        if not (self.project/'gradlew').is_file():
            return
        names = {path.stem for path in self.project.glob('src/*/java/**/*.java')}
        names |= {Path(path).stem for path in self.scope['files']} | {Path(change['path']).stem for change in output['changes']}
        with tempfile.TemporaryDirectory(prefix='code-risk-probe-') as directory:
            copy = Path(directory)/'project'
            shutil.copytree(self.project, copy, symlinks=True, ignore=shutil.ignore_patterns('build', '.gradle', '.git'))
            for change in output['changes']:
                target = copy/change['path']
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text(change['content'])
            (copy/'probe.init.gradle').write_text(PROBE_INIT)
            try:
                result = subprocess.run(['./gradlew', '-I', 'probe.init.gradle', 'compileTestJava', '--no-daemon', '-q'],
                                        cwd=copy, capture_output=True, text=True, timeout=600)
            except (OSError, subprocess.TimeoutExpired):
                return  # The probe only adds feedback; verification still compiles everything.
        errors = library_misuse(result.stdout + '\n' + result.stderr, names)
        if errors:
            raise ValueError('tests use members that do not exist in these library types (use a real API): '
                             + '; '.join(errors[:10]))

    def _validate_generated_tests(self, output, requirements):
        expected = {(req, scenario) for req, data in requirements['requirements'].items() for scenario in data['scenarios']}
        seen = set()
        tests = set()
        for entry in output['requirementTestMap']:
            key = (entry['requirementId'], entry['scenarioId'])
            if key not in expected or key in seen:
                raise ValueError('Unknown or duplicate requirement scenario mapping')
            seen.add(key)
            tests.update(entry['tests'])
        if seen != expected or any(entry['test'] not in tests for entry in output['expectedFailures']):
            raise ValueError('Incomplete requirement test map or unrelated expected failure')

    def _process(self, command, *, timeout=600, allow_failure=False):
        timeout = self._repair_timeout(timeout) if self.repair_active else timeout
        process = subprocess.Popen(command, cwd=self.project, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                   text=True, start_new_session=True)
        try:
            output, _ = process.communicate(timeout=timeout)
        except BaseException:
            agent.stop(process)
            process.communicate()
            raise
        if process.returncode and not allow_failure:
            raise ValueError('Approved executor failed; inspect partial changes or verification')
        return output

    def _write(self, action, changes):
        # Exact fixed CLI; no arbitrary shell command or caller-selected phase/action.
        regression.save(self.directory/'changes.json', changes)
        command = ['java', '-Dspring.main.banner-mode=off', '-Dlogging.level.root=OFF',
                   '-jar', str(self.paths['analyzer']), 'requirements', str(self.paths['requirements']),
                   '--scope', str(self.paths['scope']), '--actions', action,
                   '--phase', 'TEST_WRITING' if action == 'WRITE_TESTS' else 'IMPLEMENTATION',
                   '--changes', str(self.directory/'changes.json')]
        try:
            return regression.parse_json(self._process(command))
        finally:
            (self.directory/'changes.json').unlink(missing_ok=True)

    def _scan(self):
        report = self.directory/'scan.json'
        report.unlink(missing_ok=True)
        if self.fixture_adapter:
            tools = fixture_tools()
            # Reuse the explicit, pinned assertion/evidence review without changing findings.
            tools.run = lambda args, **kwargs: subprocess.run([str(arg) for arg in args],
                cwd=kwargs.get('cwd', tools.REPO), env=kwargs.get('env'), check=True,
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                timeout=self._repair_timeout(600) if self.repair_active else 600)
            with contextlib.redirect_stdout(io.StringIO()):
                tools.verify_evidence(self.paths['analyzer'], self.plan['database'].removeprefix('MySQL '))
            regression.save(report, regression.read_json(self.project/'build/scan-reviewed.json'))
            return
        self._process(['java', '-jar', str(self.paths['analyzer']), 'scan', str(self.project),
                       '--format', 'json', '--output', report.relative_to(self.project).as_posix(), '--fail-on', 'off'])
        evidence.items_from_report(regression.read_json(report))

    def _verification(self, approvals):
        if not isinstance(approvals, list) or any(not isinstance(a, dict) for a in approvals):
            raise ValueError('Approvals must be review records')
        if 'lockedTests' in self.state and regression.protected_sources(self.state['sources']) != self.state['lockedTests']:
            self._invalidate('Accepted tests or build inputs changed after test-writing', 'ARTIFACTS')
            raise ValueError('Accepted tests changed; rerun test-writing to accept them again')
        for name, sha in self.state.get('agentArtifacts', {}).items():
            if regression.digest(self.directory/name) != sha:
                self._invalidate('Agent contract changed', 'ARTIFACTS')
                raise ValueError('Agent contract changed')
        if 'agent-test-writing.json' in self.state.get('agentArtifacts', {}):
            contract = regression.read_json(self.directory/'agent-test-writing.json')['output']
            for change in contract['changes']:
                if regression.digest(self.project/change['path']) != hashlib.sha256(change['content'].encode()).hexdigest():
                    self._invalidate('Accepted generated tests changed', 'ARTIFACTS')
                    raise ValueError('Accepted generated tests changed; review a new test contract')
        for key in ('verified', 'reviewed', 'completion', 'staleKind', 'serviceSha256'):
            self.state.pop(key, None)
        # Interrupted verification must not leave an earlier VERIFIED acceptance behind.
        self._record('IMPLEMENTATION', 'VERIFYING')
        arguments = {}
        if self.quality:
            arguments['coverage'] = True
        if self.repair_active:
            arguments['timeout_seconds'] = self._repair_timeout(600)
        after = regression.snapshot(self.project, self.plan, **arguments)
        regression.save(self.directory/'after.json', after)
        before = regression.read_json(self.directory/'baseline.json')
        result = regression.compare(before, after, approvals, self.plan['newFeatures'])
        if 'agent-test-writing.json' in self.state.get('agentArtifacts', {}):
            for entry in regression.read_json(self.directory/'agent-test-writing.json')['output']['requirementTestMap']:
                for test in entry['tests']:
                    if not regression.successful(after['tests'], test):
                        result['failures'].append('Generated requirement test missing or failed: ' + test)
                        result['status'] = 'FAILED'
        self._generation_inputs(self.state['sources'])
        result['classification'] = failure.classify(before, after, result)
        regression.save(self.directory/'regression.json', result)
        if result['status'] != 'PASSED':
            self._record('IMPLEMENTATION', result['status'])
            return result
        try:
            self._scan()
        except (ValueError, OSError, KeyError, TypeError):
            self._generation_inputs(self.state['sources'])
            result.update(status='NEEDS_REVIEW', classification=failure.outcome('EVIDENCE'))
            result['failures'].append('Static execution evidence is invalid or incomplete')
            regression.save(self.directory/'regression.json', result)
            self._record('IMPLEMENTATION', result['status'])
            return result
        if self.repair_config:
            blocking = [item for item in evidence.items_from_report(regression.read_json(self.directory/'scan.json')).values()
                        if item['kind'] == 'PRODUCTION_RISK' and item['severity'] in ('HIGH', 'CRITICAL')]
            if blocking:
                repairable = all(item['staticStatus'] == 'CONFIRMED'
                                 and item['location']['file'] in self.scope['files']
                                 and item['location']['file'].startswith('src/main/') for item in blocking)
                result.update(status='FAILED', classification=failure.outcome('STATIC_ANALYSIS' if repairable else 'EVIDENCE',
                              locations=[item['location']['file'] + ':' + str(item['location']['startLine']) for item in blocking]))
                result['failures'].append('Blocking production static findings remain')
        if result['status'] == 'PASSED' and self.quality:
            gate = self._quality_evidence(after)
            if gate['status'] != 'PASSED':
                result.update(status=gate['status'], classification=failure.outcome('QUALITY'))
                result['failures'] += gate['failures']
        self._generation_inputs(self.state['sources'])
        regression.save(self.directory/'regression.json', result)
        if result['status'] != 'PASSED':
            self._record('IMPLEMENTATION', result['status'])
            return result
        if self._inputs() != self.state['inputs'] or regression.source_hashes(self.project) != self.state['sources']:
            self._invalidate('Inputs changed during verification', 'INPUTS')
            raise ValueError('Verification inputs drifted')
        self.state['verified'] = {name: regression.digest(self.directory/name) for name in ('after.json', 'regression.json', 'scan.json')}
        self.state['verified'].update(self.state.get('agentArtifacts', {}))
        if self.quality:
            self.state['verified'].update({name: regression.digest(self.directory/name) for name in ('quality.json', 'mutation.json')})
        self._record('VERIFIED', 'PASSED')
        return result

    def _quality_evidence(self, after):
        receipt = {'version': quality.VERSION, 'status': 'NEEDS_REVIEW', 'failures': [],
                   'snapshotSha256': evidence.fingerprint(after), 'sources': after['sources'],
                   'policySha256': evidence.fingerprint(self.quality)}
        try:
            expected = after.get('coverageArtifacts', {})
            if set(expected) != {quality.COVERAGE_XML, quality.COVERAGE_EXEC} \
                    or any(quality.digest(quality.safe_file(self.project, path)) != sha for path, sha in expected.items()):
                raise ValueError('Fresh final-run coverage artifacts required')
            counters = quality.coverage(self.project, self.quality)
            bytecode = quality.bytecode_hashes(self.project)
            if not bytecode:
                raise ValueError('Fresh compiled classes required')
            seeds = self.directory/'mutation-targets.txt'
            seeds.write_text('\n'.join(sorted(set(self.quality['targets'].values()))) + '\n')
            try:
                raw = self._process(['java', '-Dspring.main.banner-mode=off', '-Dlogging.level.root=OFF',
                                     '-jar', str(self.paths['analyzer']), 'mutation', str(self.project),
                                     '--files-file', str(seeds)], timeout=self.quality['timeoutSeconds'], allow_failure=True)
            finally:
                seeds.unlink(missing_ok=True)
            if len(raw.encode()) > agent.MAX_OUTPUT_BYTES:
                raise ValueError('Mutation response exceeds limit')
            mutation = agent.parse_json(raw)
            regression.save(self.directory/'mutation.json', mutation)
            receipt.update(quality.evaluate(self.quality, counters, mutation))
            regression.verified_snapshot(self.project, self.directory/'after.json', self.plan)
            if bytecode != quality.bytecode_hashes(self.project):
                raise ValueError('Compiled classes changed during mutation execution')
            receipt.update(bytecode=bytecode, artifacts=dict(expected, **{
                (self.directory/'mutation.json').relative_to(self.project).as_posix(): regression.digest(self.directory/'mutation.json')}))
        except RepairStop:
            raise
        except (ValueError, OSError, TypeError, KeyError, subprocess.SubprocessError):
            receipt.update(status='NEEDS_REVIEW', failures=['Fresh final JaCoCo/PIT evidence unavailable or changed'])
        regression.save(self.directory/'quality.json', receipt)
        return receipt

    def _repair_timeout(self, maximum):
        ledger = self.state['repair']
        if any(ledger.get(name) != value for name, value in self.repair_config.items()) \
                or any(type(ledger.get(name)) is not int or ledger[name] < 0 for name in ('iterations', 'modelCalls')):
            raise ValueError('Repair ledger limits changed')
        elapsed = time.time() - ledger['startedAt']
        if not math.isfinite(elapsed) or elapsed < 0:
            raise RepairStop('Repair clock invalid')
        remaining = ledger['timeoutSeconds'] - max(elapsed, time.monotonic() - self.repair_started)
        if remaining < 1:
            raise RepairStop('Repair time budget exhausted')
        return min(maximum, max(1, int(remaining)))

    def _repair_call(self):
        self._repair_timeout(3600)
        ledger = self.state['repair']
        if ledger['modelCalls'] >= ledger['maxModelCalls']:
            raise RepairStop('Repair model-call budget exhausted')
        ledger['modelCalls'] += 1
        self._save()

    def _repair_archive(self, classification):
        ledger = self.state['repair']
        number = len(ledger['history']) + 1
        files = {}
        for name in ('after.json', 'regression.json', 'scan.json', 'quality.json', 'mutation.json', 'agent-implementation.json'):
            path = self.directory/name
            if path.is_file():
                relative = 'repair-' + str(number) + '/' + name
                target = self.directory/relative
                regression.save(target, regression.read_json(path), exclusive=True)
                files[relative] = regression.digest(target)
        ledger['history'].append({'classification': classification, 'artifacts': files})
        self._save()

    def _repair(self, approvals):
        if not self.repair_config:
            raise ValueError('Explicit bounded repair configuration required')
        if not any(h['phase'] == 'IMPLEMENTATION' and h['status'] == 'COMPLETED' for h in self.state['history']):
            raise ValueError('Implementation required before repair')
        if 'repair' not in self.state:
            ledger = dict(self.repair_config, startedAt=time.time(), modelCalls=0, iterations=0, history=[],
                          protected=regression.protected_sources(self.state['sources']))
            artifact = self.directory/'agent-implementation.json'
            if artifact.is_file():
                decision = regression.read_json(artifact).get('routing', {})
                if decision:
                    ledger.update(requiredTier=decision['requiredTier'], routeAttempt=decision['attempt']-1)
            self.state['repair'] = ledger
            self._save()
        ledger = self.state['repair']
        self.repair_active = True
        self.repair_started = time.monotonic()
        try:
            for item in ledger['history']:
                if any(regression.digest(self.directory/path) != sha for path, sha in item['artifacts'].items()):
                    self._invalidate('Repair history evidence changed', 'ARTIFACTS')
                    raise RepairStop('Repair history changed')
            while True:
                self._repair_timeout(600)
                if regression.protected_sources(regression.source_hashes(self.project)) != ledger['protected']:
                    self._invalidate('Accepted tests or build inputs changed during repair', 'INPUTS')
                    raise RepairStop('Protected repair inputs changed')
                result = self._verification(approvals)
                classification = result['classification']
                self._repair_archive(classification)
                if result['status'] == 'PASSED':
                    ledger['status'] = 'PASSED'
                    self._save()
                    return result
                if not classification['retryable']:
                    raise RepairStop('Validation failure requires review: ' + classification['kind'])
                if ledger['iterations'] >= ledger['maxIterations']:
                    raise RepairStop('Repair iteration budget exhausted')
                ledger['iterations'] += 1
                self._save()
                generated = self._generate('implementation')
                if generated['status'] != 'COMPLETED':
                    raise RepairStop('Implementation generation requires review')
                if regression.protected_sources(regression.source_hashes(self.project)) != ledger['protected']:
                    self._invalidate('Accepted tests or build inputs changed during repair', 'INPUTS')
                    raise RepairStop('Protected repair inputs changed')
        except (RepairStop, subprocess.TimeoutExpired) as error:
            reason = str(error) if isinstance(error, RepairStop) else 'Repair executor timed out'
            ledger.update(status='NEEDS_REVIEW', stopReason=reason)
            self._save()
            return {'status': 'NEEDS_REVIEW', 'reason': reason}
        finally:
            self.repair_active = False

    def _fresh_execution(self):
        try:
            for name, sha in self.state['verified'].items():
                if regression.digest(self.directory/name) != sha:
                    raise ValueError('Verification report changed')
            regression.verified_snapshot(self.project, self.directory/'after.json', self.plan)
            if self.quality:
                receipt = regression.read_json(self.directory/'quality.json')
                if receipt.get('policySha256') != evidence.fingerprint(self.quality):
                    raise ValueError('Quality policy changed')
                quality.fresh(self.project, receipt, regression.read_json(self.directory/'after.json'), evidence.fingerprint)
        except (ValueError, OSError, KeyError):
            self._invalidate('Execution, environment, artifact or static report changed', 'ARTIFACTS')
            raise ValueError('Rerun verification and review on current inputs')

    def _service(self, container):
        import re
        if not self.fixture_adapter or not isinstance(container, str) or not re.fullmatch(r'code-risk-sample-[0-9a-f]{12}', container):
            raise ValueError('Dedicated fixture container required')
        inspected = subprocess.run(['docker', 'inspect', '--format',
            '{{ index .Config.Labels "code-risk-analyzer.fixture" }}', container],
            capture_output=True, text=True, timeout=15)
        if inspected.returncode or inspected.stdout.strip() != 'spring-risk-sample':
            raise ValueError('Container ownership label missing')
        published = subprocess.run(['docker', 'port', container, '3306/tcp'], capture_output=True, text=True, timeout=15)
        match = re.fullmatch(r'127\.0\.0\.1:(\d+)\s*', published.stdout)
        if published.returncode or not match:
            raise ValueError('Owned loopback MySQL port required')
        expected = 'jdbc:mysql://127.0.0.1:' + match.group(1) + '/risk_sample'
        url = os.environ.get('RISK_SAMPLE_MYSQL_URL', '')
        if (url != expected and not url.startswith(expected+'?')) or os.environ.get('RISK_SAMPLE_MYSQL_USER') != 'risk_sample':
            raise ValueError('Service database must match the owned fixture container')
        return fixture_tools().verify_service(dict(os.environ), container, self.directory/'service-running.json')

    def _local_service(self, container):
        if not self.fixture_adapter and not self.service_adapter:
            raise ValueError('Select the MYSQL fixture adapter or a general service adapter')
        if self.service_adapter and container is not None:
            raise ValueError('The general service adapter does not use a fixture container')
        for key in ('reviewed', 'completion', 'serviceSha256'):
            self.state.pop(key, None)
        self._record('VERIFIED', 'SERVICE_RUNNING')
        try:
            if self.service_adapter:
                if self.service_adapter['jar'] not in ('build/libs/' + name for name in regression.read_json(self.directory/'after.json').get('artifactSha256', {})):
                    raise ValueError('Service JAR must be a verified build artifact')
                result = service_adapter.verify(self.project, self.service_adapter, regression.source_hashes(self.project),
                                                launcher=service_adapter.java_launcher)
            else:
                result = self._service(container)
            regression.save(self.directory/'service.json', result)
            self._fresh_execution()
            if self._inputs() != self.state['inputs']:
                self._invalidate('Inputs changed during service verification', 'INPUTS')
                raise ValueError('Service input drift')
            snapshot = regression.read_json(self.directory/'after.json')
            validation = regression.completion(regression.read_json(self.directory/'regression.json'), snapshot, result, [])
            if validation['status'] == 'COMPLETED':
                self.state['serviceSha256'] = regression.digest(self.directory/'service.json')
                self._record('VERIFIED', 'SERVICE_PASSED')
            else:
                result['status'] = 'FAILED'
                regression.save(self.directory/'service.json', result)
                self._record('VERIFIED', 'SERVICE_FAILED')
            return result
        except BaseException:
            if self.state['phase'] != 'STALE':
                self._record('VERIFIED', 'SERVICE_INTERRUPTED_OR_FAILED')
            raise

    def _review(self, bundle, service=None, open_evidence=None, fixture_policy=None):
        for key in ('reviewed', 'completion'):
            self.state.pop(key, None)
        self._record('VERIFIED', 'REVIEWING')
        if self.fixture_adapter or self.service_adapter:
            if not self.state.get('serviceSha256'):
                raise ValueError('Successful local-service stage required')
            if regression.digest(self.directory/'service.json') != self.state['serviceSha256']:
                self._invalidate('Recorded service evidence changed', 'ARTIFACTS')
                raise ValueError('Service evidence changed')
            actual = regression.read_json(self.directory/'service.json')
            if service is not None and service != actual:
                raise ValueError('External service result cannot replace controller execution')
            service = actual
        if not isinstance(open_evidence, list) or any(not isinstance(q, str) or not q.strip() for q in open_evidence):
            raise ValueError('Explicit open-evidence array required')
        snapshot = regression.read_json(self.directory/'after.json')
        reviewed = evidence.review_evidence(self.project, snapshot, (self.directory/'scan.json').relative_to(self.project).as_posix(), bundle, fixture_policy)
        questions = list(open_evidence)
        if not (self.fixture_adapter or self.service_adapter):
            # A caller-supplied service result is a claim, not controller execution; it can never complete the session.
            questions.append('Service evidence was supplied by the caller, not executed by a controller adapter')
        result = regression.completion(regression.read_json(self.directory/'regression.json'), snapshot, service, questions, reviewed)
        regression.save(self.directory/'review-inputs.json', {'bundle': bundle, 'service': service, 'openEvidence': open_evidence, 'fixturePolicy': fixture_policy})
        regression.save(self.directory/'review-result.json', result)
        self.state['reviewed'] = {name: regression.digest(self.directory/name) for name in ('review-inputs.json', 'review-result.json')}
        self._record('REVIEWED' if result['status'] == 'COMPLETED' else 'VERIFIED', result['status'])
        return result

    def _complete(self):
        for name, sha in self.state['reviewed'].items():
            if regression.digest(self.directory/name) != sha:
                self._invalidate('Review inputs or acceptance changed', 'ARTIFACTS')
                raise ValueError('Review artifact changed')
        # Recheck requirement excerpts and test assertions, not only saved acceptance.
        inputs = regression.read_json(self.directory/'review-inputs.json')
        result = self._review(inputs['bundle'], inputs['service'], inputs['openEvidence'], inputs['fixturePolicy'])
        if result['status'] == 'COMPLETED':
            self._fresh_execution()
            if self._inputs() != self.state['inputs']:
                self._invalidate('Inputs changed during final review', 'INPUTS')
                raise ValueError('Final review input drift')
            self.state['completion'] = result
            self._record('COMPLETED', 'COMPLETED')
        return result


    def _command(self, args):
        completed = subprocess.run(args, cwd=self.project, capture_output=True, text=True, timeout=120)
        if completed.returncode:
            raise ValueError('Command failed: ' + ' '.join(args[:3]))
        return completed.stdout

    def _pull_request(self, options):
        import re
        if not isinstance(options, dict) or not set(options) <= {'title', 'base', 'submit'}:
            raise ValueError('Pull request options must be title, base and submit')
        title, base, submit = options.get('title'), options.get('base', 'main'), options.get('submit', False)
        if not isinstance(title, str) or not title.strip() or len(title) > 120 or '\n' in title or '\r' in title:
            raise ValueError('Single-line pull request title required')
        if not isinstance(base, str) or not re.fullmatch(r'[A-Za-z0-9._][A-Za-z0-9._/-]{0,199}', base):
            raise ValueError('Invalid base branch')
        if type(submit) is not bool:
            raise ValueError('Explicit boolean submit required')
        if submit and 'PULL_REQUEST' not in self.plan['approvedActions']:
            raise ValueError('Submitting requires PULL_REQUEST in approved plan actions')
        completion = self.state.get('completion')
        if not completion or completion.get('status') != 'COMPLETED':
            raise ValueError('Completed session required')
        for name, sha in self.state['reviewed'].items():
            if regression.digest(self.directory/name) != sha:
                self._invalidate('Review inputs or acceptance changed', 'ARTIFACTS')
                raise ValueError('Review artifact changed')
        body_path = self.directory/'pull-request.md'
        body_path.write_text(self._pull_request_body(completion))
        record = {'title': title, 'base': base, 'bodySha256': regression.digest(body_path),
                  'completionSha256': evidence.fingerprint(completion), 'status': 'DRAFTED'}
        if submit:
            # Only the committed, pushed tree that matches the verified sources may be proposed.
            if self._command(['git', 'status', '--porcelain', '--untracked-files=all', '--', '.']).strip():
                raise ValueError('Commit the verified sources before submitting')
            head = self._command(['git', 'rev-parse', 'HEAD']).strip()
            if self._command(['git', 'rev-parse', '@{upstream}']).strip() != head:
                raise ValueError('Push the verified commit before submitting')
            branch = self._command(['git', 'rev-parse', '--abbrev-ref', 'HEAD']).strip()
            if branch in ('HEAD', base):
                raise ValueError('Submit from a feature branch')
            if regression.source_hashes(self.project) != self.state['sources']:
                self._invalidate('Sources changed before pull request', 'SOURCES')
                raise ValueError('Source drift before pull request')
            url = self._command(['gh', 'pr', 'create', '--draft', '--base', base, '--head', branch,
                                 '--title', title, '--body-file', str(body_path)]).strip().splitlines()[-1]
            if not re.fullmatch(r'https://\S+/pull/\d+', url):
                raise ValueError('Unexpected pull request response')
            record.update(status='SUBMITTED', url=url, head=head, branch=branch)
        regression.save(self.directory/'pull-request.json', record)
        self.state['pullRequest'] = record
        self._record('COMPLETED', 'PULL_REQUEST_' + record['status'])
        return record

    def _pull_request_body(self, completion):
        after = regression.read_json(self.directory/'after.json')
        result = regression.read_json(self.directory/'regression.json')
        inputs = regression.read_json(self.directory/'review-inputs.json')
        tests = after.get('tests', {})
        passed = sorted(test for test, state in tests.items() if state == 'PASSED')
        lines = ['## 요약', '', '- 요구사항 문서: ' + ', '.join('`%s`' % d for d in self.config['documents']),
                 '- 승인된 변경 범위: ' + ', '.join('`%s`' % f for f in self.scope['files']), '',
                 '## 검증 결과', '',
                 '- 회귀 비교: %s, 테스트 %d개 중 %d개 PASSED' % (result['status'], len(tests), len(passed))]
        lines += ['  - `%s`' % test for test in passed[:50]]
        if len(passed) > 50:
            lines.append('  - 외 %d개' % (len(passed) - 50))
        for change in result.get('protectedChanges', []):
            lines.append('- 보호 파일 변경: `%s` (%s)' % (change['path'], '해시 기반 검토 완료' if change['reviewed'] else '검토 필요'))
        lines.append('- 정적 위험: ' + completion.get('targetRiskStatus', 'NOT_ASSESSED'))
        if self.quality:
            receipt = regression.read_json(self.directory/'quality.json')
            for target, counters in receipt.get('coverage', {}).items():
                score = receipt.get('mutation', {}).get(target, {}).get('score')
                lines.append('- 품질 게이트 `%s`: LINE %.0f%%, BRANCH %.0f%%, 변이 점수 %s' % (
                    target, counters['LINE']['ratio']*100, counters['BRANCH']['ratio']*100,
                    'N/A' if score is None else '%.0f%%' % (score*100)))
        else:
            lines.append('- 품질 게이트: 미적용')
        service = inputs.get('service') or {}
        scenarios = service.get('scenarios', {})
        lines.append('- 별도 서비스 실행: %s (%s)' % (service.get('status', 'NOT_PERFORMED'),
                     ', '.join('%s=%s' % item for item in sorted(scenarios.items())) or '시나리오 없음'))
        lines.append('- 근거 검토: ' + completion.get('evidenceReviewStatus', 'NOT_PERFORMED'))
        lines += ['', '## 한계', '', '- ' + completion.get('meaning', '')]
        lines += ['- ' + item for item in result.get('limitations', [])]
        lines += ['', '_`code_change_workflow.py pull-request`가 완료된 세션의 근거로 생성했다. snapshot `%s`_'
                  % evidence.fingerprint(after)[:12], '']
        return '\n'.join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('stage', choices=('baseline', 'test-writing', 'implementation', 'generate-tests', 'generate-implementation',
                                         'verification', 'repair', 'local-service', 'review', 'complete', 'pull-request'))
    parser.add_argument('--config', required=True)
    parser.add_argument('--state', required=True)
    for name in ('changes', 'approvals', 'bundle', 'service', 'open-evidence', 'fixture-policy', 'pull-request'):
        parser.add_argument('--' + name)
    parser.add_argument('--container')
    args = parser.parse_args()
    try:
        supplied = {key: value for key, value in vars(args).items() if key not in ('stage', 'config', 'state') and value is not None}
        allowed = {'baseline': set(), 'test-writing': {'changes'}, 'implementation': {'changes'},
                   'generate-tests': set(), 'generate-implementation': set(),
                   'verification': {'approvals'}, 'repair': {'approvals'}, 'local-service': {'container'}, 'review': {'bundle', 'service', 'open_evidence', 'fixture_policy'}, 'complete': set(),
                   'pull-request': {'pull_request'}}[args.stage]
        required = {'test-writing': {'changes'}, 'implementation': {'changes'}, 'review': {'bundle', 'open_evidence'},
                    'pull-request': {'pull_request'}}.get(args.stage, set())
        if not set(supplied) <= allowed or not required <= set(supplied):
            raise ValueError('Missing or inappropriate stage options')
        config = regression.read_json(args.config)
        runner = Controller(config, args.state)
        if args.stage == 'review' and not runner.fixture_adapter and not runner.service_adapter and not args.service:
            raise ValueError('Explicit service evidence required')
        data = {key: path if key == 'container' else regression.read_json(path) for key, path in supplied.items()}
        if data.get('fixture_policy'):
            policy = data['fixture_policy']
            if isinstance(policy.get('project'), str):
                policy['project'] = str((Path(args.fixture_policy).resolve().parent/policy['project']).resolve())
        result = runner.execute(args.stage, **data)
        print(json.dumps({'status': result['status'], 'state': str(runner.state_path)}))
        return 0 if result['status'] in ('PASSED', 'COMPLETED', 'DRAFTED', 'SUBMITTED') else 1 if result['status'] == 'FAILED' else 3
    except (ValueError, OSError, KeyError, TypeError, subprocess.SubprocessError):
        print('CODE_CHANGE stage rejected or failed; inspect session phase and approved evidence')
        return 2
    except KeyboardInterrupt:
        print('CODE_CHANGE interrupted; retry verification/service before review')
        return 130


if __name__ == '__main__':
    signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt()))
    raise SystemExit(main())
