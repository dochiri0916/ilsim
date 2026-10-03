"""Bind final JaCoCo and freshly executed PIT evidence to the verified snapshot."""
import hashlib
import math
from pathlib import Path
import re
import xml.etree.ElementTree as ET


VERSION = 1
COVERAGE_XML = 'build/reports/jacoco/test/jacocoTestReport.xml'
COVERAGE_EXEC = 'build/jacoco/test.exec'
COVERAGE_INIT = '''
allprojects { p ->
    p.plugins.withId('java') {
        def configured = p.plugins.hasPlugin('jacoco')
        p.pluginManager.apply('jacoco')
        if (!configured) p.extensions.configure('jacoco') { extension -> extension.toolVersion = '0.8.14' }
        p.tasks.named('jacocoTestReport') { task ->
            task.dependsOn(p.tasks.named('test'))
            task.reports.xml.required.set(true)
        }
    }
}
gradle.projectsEvaluated {
    def report = gradle.rootProject.tasks.findByName('jacocoTestReport')
    if (report == null) throw new GradleException('Root Java JaCoCo report task required')
    gradle.rootProject.tasks.register('codeRiskCoverage') { task -> task.dependsOn(report) }
}
'''


def settings(value):
    defaults = {'minimumLineCoverage': 0.9, 'minimumBranchCoverage': 0.8, 'minimumMutationScore': 0.9,
                'maxSurvivingMutations': 0, 'timeoutSeconds': 600}
    if not isinstance(value, dict) or 'targets' not in value or not set(value) <= {'targets', *defaults}:
        raise ValueError('Explicit quality targets required')
    result = dict(defaults, **value)
    targets = result['targets']
    if not isinstance(targets, dict) or not targets:
        raise ValueError('Nonempty class-to-production-file targets required')
    for name, path in targets.items():
        if not isinstance(name, str) or not re.fullmatch(r'[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*', name) \
                or not isinstance(path, str) or not path.startswith('src/main/') or not path.endswith('.java') \
                or Path(path).as_posix() != path or '..' in Path(path).parts or '\\' in path:
            raise ValueError('Exact Java classes and normalized src/main files required')
    for name in ('minimumLineCoverage', 'minimumBranchCoverage', 'minimumMutationScore'):
        number = result[name]
        if type(number) not in (int, float) or not 0 <= number <= 1 or not math.isfinite(number):
            raise ValueError('Quality ratios must be between 0 and 1')
    if type(result['maxSurvivingMutations']) is not int or not 0 <= result['maxSurvivingMutations'] <= 1000:
        raise ValueError('Invalid mutation limit')
    if type(result['timeoutSeconds']) is not int or not 1 <= result['timeoutSeconds'] <= 1800:
        raise ValueError('Invalid quality execution timeout')
    return result


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def safe_file(project, path):
    relative = Path(path)
    if relative.is_absolute() or '..' in relative.parts or relative.as_posix() != path:
        raise ValueError('Normalized project-relative quality path required')
    target = project/relative
    if not target.is_file() or any(parent.is_symlink() for parent in [target, *target.parents]):
        raise ValueError('Missing or symlink quality evidence')
    return target


def bytecode_hashes(project):
    return {path.relative_to(project).as_posix(): digest(safe_file(project, path.relative_to(project).as_posix()))
            for kind in ('main', 'test') for path in sorted((project/('build/classes/java/'+kind)).rglob('*.class'))}


def coverage(project, config):
    raw = safe_file(project, COVERAGE_XML).read_bytes()
    if len(raw) > 16*1024*1024 or b'<!ENTITY' in raw:
        raise ValueError('Invalid coverage XML')
    try:
        root = ET.fromstring(raw)
    except ET.ParseError as error:
        raise ValueError('Malformed coverage XML') from error
    if root.tag != 'report':
        raise ValueError('JaCoCo report required')
    classes = {}
    for node in root.findall('package/class'):
        name = node.get('name', '').replace('/', '.')
        if name in classes:
            raise ValueError('Duplicate coverage class')
        classes[name] = node
    result = {}
    for target in config['targets']:
        if target not in classes:
            raise ValueError('Coverage target missing')
        counters = {}
        for node in classes[target].findall('counter'):
            kind = node.get('type')
            if kind in counters:
                raise ValueError('Duplicate coverage counter')
            missed, covered = int(node.get('missed', '-1')), int(node.get('covered', '-1'))
            if min(missed, covered) < 0:
                raise ValueError('Invalid coverage counts')
            counters[kind] = {'missed': missed, 'covered': covered,
                              'ratio': covered/(missed+covered) if missed+covered else 1.0}
        if 'LINE' not in counters or counters['LINE']['missed']+counters['LINE']['covered'] == 0:
            raise ValueError('Executable coverage evidence required')
        # JaCoCo omits the BRANCH counter for straight-line methods.
        result[target] = {'LINE': counters['LINE'], 'BRANCH': counters.get('BRANCH', {'missed': 0, 'covered': 0, 'ratio': 1.0})}
    return result


def evaluate(config, coverage_counts, mutation):
    failures = []
    if set(coverage_counts) != set(config['targets']):
        raise ValueError('Coverage must cover every declared target')
    for target, counters in coverage_counts.items():
        for kind, minimum in (('LINE', config['minimumLineCoverage']), ('BRANCH', config['minimumBranchCoverage'])):
            if counters[kind]['ratio'] < minimum:
                failures.append(kind + ' coverage below threshold: ' + target)
    if not isinstance(mutation, dict) or mutation.get('outcome') != 'MUTATION_COMPLETED' or not isinstance(mutation.get('mutations'), list) \
            or set(mutation.get('targetClasses', [])) != set(config['targets']):
        return {'status': 'NEEDS_REVIEW', 'failures': ['Fresh completed PIT execution required'], 'coverage': coverage_counts}
    counts = {target: {'total': 0, 'killed': 0, 'surviving': 0, 'unresolved': 0} for target in config['targets']}
    for item in mutation['mutations']:
        if not isinstance(item, dict) or not isinstance(item.get('evidence'), dict):
            raise ValueError('Invalid mutation evidence')
        data = item.get('evidence', {})
        name, status = data.get('mutatedClass'), data.get('status')
        if name not in counts:
            raise ValueError('Unexpected mutation target')
        count = counts[name]
        count['total'] += 1
        count['killed'] += status == 'KILLED'
        count['surviving'] += status in ('SURVIVED', 'NO_COVERAGE')
        count['unresolved'] += status not in ('KILLED', 'SURVIVED', 'NO_COVERAGE')
    for target, count in counts.items():
        count['score'] = count['killed']/count['total'] if count['total'] else 0.0
        if not count['total']:
            failures.append('No mutations for target: ' + target)
        if count['unresolved']:
            failures.append('Unresolved PIT status: ' + target)
        if count['score'] < config['minimumMutationScore']:
            failures.append('Mutation score below threshold: ' + target)
    if sum(count['surviving'] for count in counts.values()) > config['maxSurvivingMutations']:
        failures.append('Surviving or uncovered mutations exceed limit')
    return {'status': 'FAILED' if failures else 'PASSED', 'failures': failures, 'coverage': coverage_counts, 'mutation': counts}


def fresh(project, receipt, snapshot, fingerprint):
    if receipt.get('version') != VERSION or receipt.get('status') != 'PASSED' \
            or receipt.get('snapshotSha256') != fingerprint(snapshot) or receipt.get('sources') != snapshot['sources']:
        raise ValueError('Quality evidence is not bound to final execution')
    if not receipt.get('bytecode') or not {COVERAGE_XML, COVERAGE_EXEC} <= set(receipt.get('artifacts', {})):
        raise ValueError('Incomplete quality execution receipt')
    for path, sha in receipt['artifacts'].items():
        if digest(safe_file(project, path)) != sha:
            raise ValueError('Quality evidence changed')
    if receipt.get('bytecode') != bytecode_hashes(project):
        raise ValueError('Quality bytecode changed')
