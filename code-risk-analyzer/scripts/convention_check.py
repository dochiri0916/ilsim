"""Run build-convention on any Gradle project without editing its build files.

A generated Gradle init script puts the plugin (published to mavenLocal by install.sh) on the build classpath and
applies it to every Java project that does not apply it already. `check --continue` then writes the plugin's
report, which is copied into the session. Comparing a run before the change with one after it separates checks the
change broke from checks that were already failing.
"""
import argparse
import json
from pathlib import Path
import re
import shutil
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


def guidance():
    return GUIDANCE.read_text(encoding='utf-8') if GUIDANCE.is_file() else None


def run(project, output, process=subprocess.run):
    """Run check once and copy the plugin report to `output`; returns the report."""
    project, output = Path(project), Path(output)
    output.parent.mkdir(parents=True, exist_ok=True)
    script = output.with_suffix('.init.gradle')
    script.write_text(init_script(version()), encoding='utf-8')
    report = project/REPORT
    report.unlink(missing_ok=True)
    result = process(['./gradlew', 'check', '--continue', '--init-script', str(script)], cwd=project,
                     stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, text=True, timeout=1800)
    if not report.is_file():
        lines = (result.stderr or '').splitlines()
        # Gradle's own failure headline says why check aborted before the plugin wrote its report.
        start = next((index + 1 for index, line in enumerate(lines) if line.strip() == '* What went wrong:'), None)
        cause = '\n'.join(line for line in lines[start:start + 3] if line.strip()) if start is not None else ''
        raise ValueError('build-convention 리포트가 만들어지지 않았습니다 (install.sh 설치 여부를 확인하세요)'
                         + (f'\n{cause}' if cause else ''))
    shutil.copyfile(report, output)
    value = json.loads(output.read_text(encoding='utf-8'))
    if not isinstance(value, dict) or value.get('status') not in ('PASS', 'FAIL') or not isinstance(value.get('checks'), dict):
        raise ValueError('컨벤션 검증 리포트 형식이 올바르지 않습니다')
    return value


def failing(report):
    return sorted(name for name, status in report['checks'].items() if status == 'FAIL')


def compare(before, after):
    """Checks failing now that passed (or did not run) before are the change's; the rest were already failing."""
    old = set(failing(before)) if before else set()
    return {'new': [name for name in failing(after) if name not in old], 'existing': [name for name in failing(after) if name in old]}


def label(names):
    return ', '.join(NAMES.get(name, name) for name in names)


def summary(report, before=None):
    if report['status'] == 'PASS':
        return '컨벤션 검증: 통과'
    if before is None:
        return '컨벤션 검증: 실패 - ' + label(failing(report))
    result = compare(before, report)
    lines = []
    if result['new']:
        lines.append('컨벤션 검증: 이번 변경으로 실패 - ' + label(result['new']))
    if result['existing']:
        lines.append('컨벤션 검증: 변경 전부터 실패(참고) - ' + label(result['existing']))
    return '\n'.join(lines) or '컨벤션 검증: 통과'


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('project')
    parser.add_argument('--output', required=True, help='리포트를 복사할 파일')
    parser.add_argument('--before', help='변경 전 리포트; 주면 이번 변경으로 새로 실패한 검사만 실패로 본다')
    args = parser.parse_args(argv)
    try:
        report = run(args.project, args.output)
    except (ValueError, OSError, subprocess.TimeoutExpired) as error:
        print(f'컨벤션 검증 불가: {error}')
        return 2
    before = None
    if args.before:
        try:
            before = json.loads(Path(args.before).read_text(encoding='utf-8'))
            if before.get('status') not in ('PASS', 'FAIL') or not isinstance(before.get('checks'), dict):
                raise ValueError
        except (ValueError, OSError, AttributeError):
            # Without a usable earlier report every failure counts as this change's, never as pre-existing.
            before = None
            print('변경 전 컨벤션 상태가 없어 모든 실패를 이번 결과로 봅니다')
    print(summary(report, before))
    print(f'리포트: {Path(args.output).resolve()}')
    if before is not None:
        return 1 if compare(before, report)['new'] else 0
    return 1 if report['status'] == 'FAIL' else 0


if __name__ == '__main__':
    sys.exit(main())
