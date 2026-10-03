"""Classify observed execution failures without delegating validation to a model."""
import re
import xml.etree.ElementTree as ET


VERSION = 1
REPAIRABLE = ('ASSERTION', 'COMPILATION', 'TEST_RUNTIME', 'STATIC_ANALYSIS')
ASSERTIONS = ('AssertionError', 'AssertionFailedError', 'MultipleFailuresError', 'ComparisonFailure')
RUNTIME = ('NullPointerException', 'IndexOutOfBoundsException', 'ArrayIndexOutOfBoundsException', 'ArithmeticException')
ENVIRONMENT = re.compile(r'could not resolve|could not download|no matching (?:java|jdk)|toolchain.*(?:not found|missing)|'
                         r'connection refused|communicationsexception|unknownhostexception|pkix path|'
                         r'cannot connect to.*docker|outofmemoryerror|unable to create native thread', re.I)


def outcome(kind, tests=None, locations=None):
    return {'version': VERSION, 'kind': kind, 'retryable': kind in REPAIRABLE,
            'tests': sorted(set(tests or [])), 'locations': sorted(set(locations or []))}


def junit_failures(directory, project):
    result = []
    for path in sorted(directory.glob('TEST-*.xml')):
        for case in ET.parse(path).getroot().iter('testcase'):
            name = case.get('name', '')
            name = name[:-2] if name.endswith('()') else name
            test = case.get('classname', '') + '#' + name
            for tag in ('failure', 'error'):
                failure = case.find(tag)
                if failure is None:
                    continue
                exception = failure.get('type', '').rsplit('.', 1)[-1]
                text = ''.join(failure.itertext())
                locations = []
                for owner, filename, line in re.findall(r'at ([\w.$]+)\.[\w$]+\(([^():]+\.java):(\d+)\)', text):
                    package = owner.rpartition('.')[0].replace('.', '/')
                    relative = 'src/main/java/' + (package + '/' if package else '') + filename
                    if (project/relative).is_file():
                        locations.append(relative + ':' + line)
                kind = 'ENVIRONMENT' if ENVIRONMENT.search(text) else 'ASSERTION' if exception in ASSERTIONS \
                    else 'TEST_RUNTIME' if exception in RUNTIME and locations else 'UNKNOWN'
                result.append({'test': test, 'kind': kind, 'locations': locations})
    return result


def gradle_failure(log, project):
    if re.search(r'insufficient balance|payment required|billing quota', log, re.I):
        return outcome('BILLING')
    if re.search(r'unauthorized|authentication failed|invalid api key|\bHTTP (?:401|403)\b', log, re.I):
        return outcome('AUTHENTICATION')
    if ENVIRONMENT.search(log):
        return outcome('ENVIRONMENT')
    locations = []
    for match in re.finditer(r'([^\r\n]+\.java):(\d+):\s*error:', log):
        raw = match.group(1).strip()
        relative = raw[len(str(project))+1:] if raw.startswith(str(project)+'/') else raw
        if relative.startswith('src/main/') and '..' not in relative.split('/') and (project/relative).is_file():
            locations.append(relative + ':' + match.group(2))
    return outcome('COMPILATION', locations=locations) if locations else outcome('UNKNOWN')


def classify(before, after, regression):
    if after.get('sources') != after.get('observedSources', after.get('sources')) or after.get('evidenceError') == 'Source changed during execution':
        return outcome('INPUTS')
    if before.get('environment') != after.get('environment'):
        return outcome('ENVIRONMENT')
    if regression.get('openEvidence') or any(not item.get('reviewed') for item in regression.get('protectedChanges', [])):
        return outcome('EVIDENCE')
    external = after.get('executionFailure', {})
    if external.get('kind') in ('AUTHENTICATION', 'BILLING', 'ENVIRONMENT', 'TIMED_OUT'):
        return external
    if after.get('evidenceError') or not after.get('tests') \
            or any(state == 'SKIPPED' for state in after.get('tests', {}).values()) \
            or not set(before.get('tests', {})) <= set(after.get('tests', {})):
        # A recognized compiler diagnostic is evidence even when JUnit could not run.
        compiler = after.get('executionFailure', {})
        return compiler if compiler.get('kind') == 'COMPILATION' and not after.get('evidenceError', '').startswith('Source changed') else outcome('EVIDENCE')
    failed = {test for test, state in after['tests'].items() if state == 'FAILED'}
    details = after.get('testFailures', [])
    if failed and failed == {entry['test'] for entry in details}:
        kinds = {entry['kind'] for entry in details}
        if kinds <= {'ASSERTION', 'TEST_RUNTIME'}:
            kind = 'TEST_RUNTIME' if 'TEST_RUNTIME' in kinds else 'ASSERTION'
            return outcome(kind, failed, [path for entry in details for path in entry['locations']])
        return outcome('ENVIRONMENT' if 'ENVIRONMENT' in kinds else 'UNKNOWN', failed)
    if regression.get('status') == 'PASSED':
        return outcome('PASSED')
    return external if external.get('kind') == 'COMPILATION' else outcome('EVIDENCE' if failed else 'UNKNOWN')
