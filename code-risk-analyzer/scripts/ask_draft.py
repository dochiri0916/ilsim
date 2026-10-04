"""Build the CODE_CHANGE inputs (requirements, plan, scope, request document) from a design file a person wrote.

Design and prompt writing stay with the person (or the assistant they work with): `ask --design design.json "..."`
reads a compact form, validates it strictly and builds the schema-valid files deterministically. No model is called
here and nothing here edits project sources. The form and how to fill it: docs/guides/DESIGN_FILE.md.
"""
import json
from pathlib import Path
import re
import subprocess
import tempfile

TEXT = {'type': 'string'}
REQUIREMENT_ID = re.compile(r'REQ-[A-Z0-9]+(?:-[A-Z0-9]+)*')
SCENARIO_ID = re.compile(r'SCN-[A-Z0-9]+(?:-[A-Z0-9]+)*')
SCENARIO_TYPES = ('NORMAL', 'DUPLICATE', 'CONCURRENCY', 'ROLLBACK', 'RETRY', 'PARTIAL_FAILURE', 'VALIDATION', 'AUTHORIZATION')
TEST_TYPES = ('DOMAIN_UNIT', 'REPOSITORY_DB', 'API_SLICE', 'INTEGRATION', 'SERVICE_E2E')
PERSPECTIVES = ('transaction', 'concurrency', 'idempotency', 'consistency')
APPROVED_ACTIONS = ['BASELINE', 'REGRESSION', 'LOCAL_SERVICE', 'COMPLETE', 'PULL_REQUEST']


def _object(properties):
    return {'type': 'object', 'properties': properties, 'required': list(properties), 'additionalProperties': False}


def _array(items):
    return {'type': 'array', 'items': items}


def _enum(values):
    return {'type': 'string', 'enum': list(values)}


PERSPECTIVE = _object({'applicability': _enum(('APPLICABLE', 'NOT_APPLICABLE')), 'reason': TEXT, 'expectations': _array(TEXT)})
DRAFT_SCHEMA = _object({
    'status': _enum(('COMPLETED', 'NEEDS_REVIEW')),
    'unknowns': _array(TEXT),
    'title': TEXT,
    'assumptions': _array(TEXT),
    'database': _enum(('MYSQL', 'OTHER', 'UNDECIDED')),
    'requirements': _array(_object({
        'id': TEXT, 'title': TEXT,
        'domain': _object({'owner': TEXT, 'aggregates': _array(TEXT), 'dataOwnership': _array(TEXT),
                           'allowedDependencies': _array(TEXT), 'entryPoints': _array(TEXT)}),
        'preconditions': _array(TEXT), 'postconditions': _array(TEXT), 'invariants': _array(TEXT), 'coreLogic': _array(TEXT),
        **{name: PERSPECTIVE for name in PERSPECTIVES},
        'scenarios': _array(_object({'id': TEXT, 'type': _enum(SCENARIO_TYPES), 'given': TEXT, 'when': TEXT,
                                     'expectedOutcomes': _array(TEXT), 'testTypes': _array(_enum(TEST_TYPES))})),
        'implementationStatus': _enum(('NOT_IMPLEMENTED', 'PARTIAL', 'IMPLEMENTED')),
        'existingCode': _array(_object({'path': TEXT, 'symbol': TEXT})),
    })),
    'files': _array(TEXT),
})


def _check(node, schema, where='design'):
    kind = schema['type']
    if kind == 'object':
        if not isinstance(node, dict):
            raise ValueError(f'{where}: 객체여야 합니다')
        missing, extra = sorted(set(schema['properties']) - set(node)), sorted(set(node) - set(schema['properties']))
        if missing or extra:
            raise ValueError(f'{where}: 필드가 맞지 않습니다' + (f" (없음: {', '.join(missing)})" if missing else '')
                             + (f" (모르는 필드: {', '.join(extra)})" if extra else ''))
        for key, child in schema['properties'].items():
            _check(node[key], child, f'{where}.{key}')
    elif kind == 'array':
        if not isinstance(node, list):
            raise ValueError(f'{where}: 배열이어야 합니다')
        for index, item in enumerate(node):
            _check(item, schema['items'], f'{where}[{index}]')
    elif not isinstance(node, str) or not node.strip() or ('enum' in schema and node not in schema['enum']):
        raise ValueError(f'{where}: 비어 있거나 허용되지 않은 값입니다'
                         + (f" (허용: {', '.join(schema['enum'])})" if 'enum' in schema else ''))


def _distinct(values, minimum=0):
    if len(values) < minimum or len(values) != len(set(values)):
        raise ValueError('Draft lists must be distinct and nonempty where required')


def source_path(path):
    relative = Path(path)
    return (not relative.is_absolute() and '..' not in relative.parts and relative.as_posix() == path and '\\' not in path
            and path.startswith(('src/main/', 'src/test/')))


def validate(value):
    """Mirror the requirements schema's conditional rules the provider-side schema cannot express."""
    _check(value, DRAFT_SCHEMA)
    if value['status'] == 'NEEDS_REVIEW':
        if not value['unknowns']:
            raise ValueError('A review request must name its questions')
        return value
    if value['unknowns'] or not value['requirements']:
        raise ValueError('A completed draft has requirements and no open questions')
    ids = [requirement['id'] for requirement in value['requirements']]
    _distinct(ids, 1)
    for requirement in value['requirements']:
        if not REQUIREMENT_ID.fullmatch(requirement['id']):
            raise ValueError('Requirement ids must look like REQ-1')
        domain = requirement['domain']
        for name, minimum in (('aggregates', 1), ('dataOwnership', 1), ('allowedDependencies', 0), ('entryPoints', 1)):
            _distinct(domain[name], minimum)
        for name in ('preconditions', 'postconditions', 'invariants', 'coreLogic'):
            _distinct(requirement[name], 1)
        for name in PERSPECTIVES:
            perspective = requirement[name]
            _distinct(perspective['expectations'])
            if (perspective['applicability'] == 'APPLICABLE') != bool(perspective['expectations']):
                raise ValueError('Applicable perspectives need expectations; others must have none')
        scenarios = requirement['scenarios']
        _distinct([scenario['id'] for scenario in scenarios], 1)
        for scenario in scenarios:
            if not SCENARIO_ID.fullmatch(scenario['id']):
                raise ValueError('Scenario ids must look like SCN-1')
            _distinct(scenario['expectedOutcomes'], 1)
            _distinct(scenario['testTypes'], 1)
        if (requirement['implementationStatus'] == 'NOT_IMPLEMENTED') == bool(requirement['existingCode']):
            raise ValueError('Existing code links must match the implementation status')
    files = value['files']
    _distinct(files, 2)
    if not all(source_path(path) for path in files) \
            or not any(path.startswith('src/main/') for path in files) or not any(path.startswith('src/test/') for path in files):
        raise ValueError('Files must be normalized src/main and src/test paths')
    return value


def existing_tests(project):
    """Class#name ids of existing JUnit tests as the XML report names them: the @DisplayName text, else the method."""
    result = []
    for path in sorted((project/'src/test/java').rglob('*.java')):
        text = path.read_text(encoding='utf-8', errors='replace')
        package = re.search(r'^\s*package\s+([\w.]+)\s*;', text, re.M)
        name = (package.group(1) + '.' if package else '') + path.stem
        # Annotations may be written with their full name, e.g. @org.junit.jupiter.api.DisplayName("...").
        for match in re.finditer(r'((?:@[\w.]+(?:\((?:[^()"]|"(?:[^"\\]|\\.)*")*\))?\s*)+)(?:public\s+|protected\s+)?void\s+(\w+)\s*\(', text):
            annotations = match.group(1)
            if not re.search(r'@(?:[\w.]+\.)?Test\b', annotations):
                continue
            display = re.search(r'@(?:[\w.]+\.)?DisplayName\s*\(\s*"((?:[^"\\]|\\.)*)"\s*\)', annotations)
            if re.search(r'@(?:[\w.]+\.)?DisplayName\b', annotations) and (not display or '\\' in display.group(1)):
                continue  # A computed or escaped name cannot be matched reliably; leave it to the full test run.
            label = display.group(1) if display else match.group(2)
            if label.strip() == label and label and '#' not in label:
                result.append(name + '#' + label)
    return sorted(set(result))


def code_revision(project):
    try:
        result = subprocess.run(['git', '-C', str(project), 'rev-parse', 'HEAD'], capture_output=True, text=True, timeout=10)
    except OSError:
        return 'NO_GIT_REVISION'
    return result.stdout.strip() if result.returncode == 0 and result.stdout.strip() else 'NO_GIT_REVISION'


def write_inputs(project, session, request, draft, analyzer, routing, guidance=None):
    """Build the schema-valid requirements, plan, scope and request document plus a config that names them.

    Convention guidance becomes a second requirement document, so the generation stages see it as repository rules.
    """
    session.mkdir(parents=True, exist_ok=True)
    document = session/'request.md'
    relative = document.relative_to(project).as_posix()
    documents = [relative]
    if guidance:
        (session/'conventions.md').write_text(guidance, encoding='utf-8')
        documents.append((session/'conventions.md').relative_to(project).as_posix())
    lines = ['# ' + draft['title'], '', '## Request', '', request, '']
    if draft['assumptions']:
        lines += ['## Assumptions', ''] + ['- ' + item for item in draft['assumptions']] + ['']
    document.write_text('\n'.join(lines), encoding='utf-8')
    review = {'status': 'EVIDENCE_SUPPORTED', 'reason': 'Drafted from the request and approved by the requester before execution',
              'evidence': [{'kind': 'REQUIREMENT', 'path': relative, 'locator': 'Request', 'observation': request}],
              'openQuestions': []}
    requirements = {}
    for item in draft['requirements']:
        requirements[item['id']] = {
            'title': item['title'],
            'source': {'document': relative, 'version': '1', 'section': 'Request', 'excerpt': request},
            'domain': item['domain'],
            **{name: item[name] for name in ('preconditions', 'postconditions', 'invariants', 'coreLogic', *PERSPECTIVES)},
            'scenarios': {scenario['id']: {key: scenario[key] for key in ('type', 'given', 'when', 'expectedOutcomes', 'testTypes')}
                          for scenario in item['scenarios']},
            'mappings': {'implementationStatus': item['implementationStatus'], 'code': item['existingCode'], 'tests': [], 'findings': []},
            'review': review}
    tests = existing_tests(project)
    if not tests:
        raise ValueError('기존 테스트가 없어 회귀 기준을 만들 수 없습니다. 테스트를 하나 이상 추가한 뒤 다시 실행하세요')
    files = {
        'requirements.json': {'version': 1, 'mode': 'CODE_CHANGE', 'project': {'name': project.name, 'database': draft['database']},
                              'codeRevision': code_revision(project), 'requirements': requirements},
        'plan.json': {'mode': 'CODE_CHANGE', 'project': str(project), 'approvedActions': APPROVED_ACTIONS,
                      'features': {'existing-tests': tests}, 'newFeatures': {},
                      'database': 'NOT_APPLICABLE' if draft['database'] == 'UNDECIDED' else draft['database']},
        'scope.json': {'project': str(project), 'files': draft['files'], 'actions': ['WRITE_TESTS', 'IMPLEMENT']},
    }
    for name, value in files.items():
        (session/name).write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')
    config = {'plan': 'plan.json', 'requirements': 'requirements.json', 'scope': 'scope.json', 'analyzer': str(analyzer),
              'documents': documents, 'routing': routing}
    path = session/'draft-config.json'
    path.write_text(json.dumps(config, ensure_ascii=False, indent=2), encoding='utf-8')
    (session/'draft.json').write_text(json.dumps(draft, ensure_ascii=False, indent=2), encoding='utf-8')
    return path


def check_requirements(path, analyzer, run=subprocess.run):
    result = run(['java', '-jar', str(analyzer), 'requirements', str(path), '--actions', 'DOCUMENT_ANALYSIS'],
                 capture_output=True, text=True, timeout=120)
    if result.returncode:
        message = next((line for line in reversed(result.stdout.splitlines()) if line.startswith('Error')), 'schema validation failed')
        raise ValueError(message)


def load(path):
    """The design file as a validated form; status and unknowns may be left out of a finished design."""
    try:
        value = json.loads(Path(path).read_text(encoding='utf-8'))
    except OSError as error:
        raise ValueError(f'설계 파일을 읽지 못했습니다: {path} ({error.strerror or error})')
    except ValueError as error:
        raise ValueError(f'설계 파일이 올바른 JSON이 아닙니다: {error}')
    if isinstance(value, dict):
        value = {'status': 'COMPLETED', 'unknowns': [], **value}
    validate(value)
    if value['status'] != 'COMPLETED':
        raise ValueError('설계 파일에 정하지 못한 질문이 남아 있습니다: ' + '; '.join(value['unknowns']))
    return value


def prepare(project, session, request, design, analyzer, routing, check=check_requirements, rules=None):
    """Write the session inputs from a loaded design and have the analyzer accept the requirements; returns the config."""
    config = write_inputs(project, session, request, design, analyzer, routing, rules)
    check(session/'requirements.json', analyzer)
    return config
