"""Run build-convention on any Gradle project without editing its build files.

A generated Gradle init script puts the plugin (published to mavenLocal by install.sh) on the build classpath and
applies it to every Java project that does not apply it already. `check --continue` then writes the plugin's
report, which is saved into the session with the violations Gradle printed. Any failing check fails the run.
"""
import argparse
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import sys

SCRIPTS = Path(__file__).resolve().parent
CONVENTION = SCRIPTS.parent.parent/'build-convention'
PLUGIN_ID = 'io.github.dochiri0916.build-convention'
REPORT = Path('build/reports/build-convention/report.json')
GUIDANCE = CONVENTION/'docs'/'AGENTS_GUIDANCE.md'
NAMES = {'architecture': '아키텍처', 'archUnit': '아키텍처(ArchUnit)', 'checkstyle': 'Checkstyle', 'pmd': 'PMD',
         'spotBugs': 'SpotBugs', 'migration': 'DB 마이그레이션', 'changedCodeCoverage': '변경 코드 커버리지',
         'coverageVerification': '전체 커버리지', 'tests': '테스트', 'compilation': '컴파일', 'javaVersion': 'Java 버전',
         'conventionIntegrity': '검증 무력화 방지', 'mutationGate': '변이 테스트', 'coverageReport': '커버리지 리포트'}


def version():
    match = re.search(r"^version\s*=\s*'([^']+)'", (CONVENTION/'build.gradle').read_text(encoding='utf-8'), re.M)
    if not match:
        raise ValueError('build-convention 버전을 찾지 못했습니다')
    return match.group(1)


def init_script(plugin_version):
    # The plugin must sit on the root build script classpath: it applies SpotBugs, PIT and others by plugin id,
    # which an init-script classpath would not resolve.
    return f"""beforeProject {{ project ->
    if (project == project.rootProject) {{
        project.buildscript.repositories {{
            mavenLocal()
            gradlePluginPortal()
            mavenCentral()
        }}
        project.buildscript.dependencies {{
            classpath 'io.github.dochiri0916:build-convention:{plugin_version}'
        }}
    }}
}}

allprojects {{
    pluginManager.withPlugin('java') {{
        if (!pluginManager.hasPlugin('{PLUGIN_ID}')) {{
            pluginManager.apply('{PLUGIN_ID}')
        }}
    }}
}}
"""


def guidance(examples=True):
    """The agent guidance plus the template tree; examples adds one template file per kind (for code writers)."""
    if not GUIDANCE.is_file():
        return None
    return GUIDANCE.read_text(encoding='utf-8') + _templates().guidance(examples)


def _templates():
    spec = importlib.util.spec_from_file_location('convention_templates', SCRIPTS/'convention_templates.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


CHECKSTYLE = re.compile(r'\[ant:checkstyle\] \[(?:ERROR|WARN)\] (.+?):(\d+)(?::\d+)?: (.+)')
LISTED = re.compile(r'\s+- \[([A-Z][A-Z0-9-]*)\] (\S+) (.+)')
NO_BASE = 'changedCodeCoverage'
COMPILE = ['./gradlew', 'testClasses']


class Unavailable(ValueError):
    """build-convention itself could not be loaded (not installed), as opposed to a check that failed."""


def violations(project, output):
    """file/message pairs Gradle printed, so a fixer knows what to change; checks still decide pass or fail."""
    result = []
    for line in output.splitlines():
        match = CHECKSTYLE.fullmatch(line.strip())
        if match:
            path = Path(match.group(1))
            relative = path.relative_to(project).as_posix() if path.is_absolute() and path.is_relative_to(project) else match.group(1)
            result.append({'file': relative, 'line': int(match.group(2)), 'message': match.group(3)})
            continue
        match = LISTED.fullmatch(line.rstrip())
        if match:
            result.append({'file': match.group(2), 'rule': match.group(1), 'message': match.group(3)})
    unique = {json.dumps(item, sort_keys=True, ensure_ascii=False): item for item in result}
    return list(unique.values())


def run(project, output, base_ref=None, process=subprocess.run):
    """Run check once, copy the plugin report to `output` with the printed violations; returns the report."""
    project, output = Path(project).resolve(), Path(output)
    output.parent.mkdir(parents=True, exist_ok=True)
    script = output.with_suffix('.init.gradle')
    script.write_text(init_script(version()), encoding='utf-8')
    report = project/REPORT
    report.unlink(missing_ok=True)
    if (project/'build/classes/java/main').is_dir():
        # Earlier class files may predate new sources; compile so the plugin's coverage-input guard sees current ones.
        process(COMPILE, cwd=project, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=1800)
    command = ['./gradlew', 'check', '--continue', '--init-script', str(script)]
    if base_ref:
        command.append('-PchangedCoverageBaseRef=' + base_ref)
    result = process(command, cwd=project, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=1800)
    lines = (result.stdout or '').splitlines()
    if not report.is_file():
        # Gradle's own failure headline says why check aborted before the plugin wrote its report.
        start = next((index + 1 for index, line in enumerate(lines) if line.strip() == '* What went wrong:'), None)
        cause = '\n'.join(line for line in lines[start:start + 3] if line.strip()) if start is not None else ''
        if any('build-convention' in line and ('Could not find' in line or 'Could not resolve' in line) for line in lines):
            raise Unavailable('build-convention을 찾지 못했습니다. install.sh로 설치하세요')
        raise ValueError('컨벤션 검사가 리포트를 만들기 전에 실패했습니다' + (f'\n{cause}' if cause else ''))
    value = json.loads(report.read_text(encoding='utf-8'))
    if not isinstance(value, dict) or value.get('status') not in ('PASS', 'FAIL') or not isinstance(value.get('checks'), dict):
        raise ValueError('컨벤션 검증 리포트 형식이 올바르지 않습니다')
    value['violations'] = violations(project, '\n'.join(lines))
    value['baseRef'] = base_ref
    output.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')
    return value


def failing(report):
    return sorted(name for name, status in report['checks'].items() if status == 'FAIL')


def blocking(report):
    """Every failing check, except changed-code coverage when there was no Git revision to compare against."""
    return [name for name in failing(report) if name != NO_BASE or report.get('baseRef')]


def label(names):
    return ', '.join(NAMES.get(name, name) for name in names)


def summary(report, before=None):
    names = blocking(report)
    lines = ['컨벤션 검증: ' + ('통과' if not names else '실패 - ' + label(names))]
    if before is not None and names:
        existing = [name for name in names if name in failing(before)]
        if existing:
            lines.append('그중 변경 전부터 실패하던 검사: ' + label(existing))
    if NO_BASE in failing(report) and not report.get('baseRef'):
        lines.append('Git 저장소가 아니어서 변경 코드 커버리지는 확인하지 못했습니다')
    for item in report.get('violations', [])[:10]:
        lines.append(f"- {item['file']}{':' + str(item['line']) if 'line' in item else ''} {item['message']}")
    if len(report.get('violations', [])) > 10:
        lines.append(f"- 외 {len(report['violations']) - 10}건")
    return '\n'.join(lines)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('project')
    parser.add_argument('--output', required=True, help='리포트를 저장할 파일')
    parser.add_argument('--base-ref', help='변경 코드 커버리지의 비교 기준 커밋')
    parser.add_argument('--before', help='변경 전 리포트; 실패 중 원래부터 실패하던 검사를 알려준다')
    args = parser.parse_args(argv)
    try:
        report = run(args.project, args.output, args.base_ref)
    except (Unavailable, OSError) as error:
        print(f'컨벤션 검증 불가: {error}')
        return 2
    except (ValueError, subprocess.TimeoutExpired) as error:
        # The plugin ran and stopped the build: that is a failed convention check, never a skipped one.
        print(f'컨벤션 검증 실패: {error}')
        return 1
    before = None
    if args.before:
        try:
            before = json.loads(Path(args.before).read_text(encoding='utf-8'))
            failing(before)
        except (ValueError, OSError, AttributeError, KeyError, TypeError):
            before = None
    print(summary(report, before))
    print(f'리포트: {Path(args.output).resolve()}')
    return 1 if blocking(report) else 0


if __name__ == '__main__':
    sys.exit(main())
