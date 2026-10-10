"""Run build-convention on any Gradle project without editing its build files.

A generated Gradle init script puts the plugin (published to mavenLocal by install.sh) on the build classpath and
applies the language's plugin to each Java/Kotlin project. `check --continue` then writes the plugin's
report, which is saved into the session with the violations Gradle printed. Any failing check fails the run.

`validators` in the project's .code-risk.yml (read and validated by the analyzer jar) can switch off the architecture
checks or the other convention checks; those still run, but are recorded as skipped with the reason and do not fail.
"""
import argparse
import json
from pathlib import Path
import re
import subprocess
import sys

SCRIPTS = Path(__file__).resolve().parent
CONVENTION = SCRIPTS.parent.parent/'build-convention'
PLUGIN_ID = 'io.github.dochiri0916.build-convention'
JAVA_PLUGIN_ID = 'io.github.dochiri0916.build-convention.java'
KOTLIN_PLUGIN_ID = 'io.github.dochiri0916.build-convention.kotlin'
REPORT = Path('build/reports/build-convention/report.json')
NAMES = {'architecture': '아키텍처', 'archUnit': '아키텍처(ArchUnit)', 'checkstyle': 'Checkstyle', 'pmd': 'PMD',
         'spotBugs': 'SpotBugs', 'migration': 'DB 마이그레이션', 'changedCodeCoverage': '변경 코드 커버리지',
         'coverageVerification': '전체 커버리지', 'tests': '테스트', 'compilation': '컴파일', 'javaVersion': 'Java 버전',
         'conventionIntegrity': '검증 무력화 방지', 'mutationGate': '변이 테스트', 'coverageReport': '커버리지 리포트',
         'gradleExecution': 'Gradle 실행'}


def version():
    match = re.search(r"^version\s*=\s*'([^']+)'", (CONVENTION/'build.gradle').read_text(encoding='utf-8'), re.M)
    if not match:
        raise ValueError('build-convention 버전을 찾지 못했습니다')
    return match.group(1)


def init_script(plugin_version, manifest=None):
    # The plugin must sit on the root build script classpath: it applies SpotBugs, PIT and others by plugin id,
    # which an init-script classpath would not resolve.
    script = f"""beforeProject {{ project ->
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
        // Kotlin/JVM applies Java internally before its own plugin callback completes.
        // Decide after configuration so a Kotlin-only module never receives the Java source rules.
        afterEvaluate {{
            boolean kotlinProject = pluginManager.hasPlugin('org.jetbrains.kotlin.jvm')
            if (kotlinProject && !pluginManager.hasPlugin('{KOTLIN_PLUGIN_ID}')) {{
                pluginManager.apply('{KOTLIN_PLUGIN_ID}')
            }}
            def main = extensions.findByType(org.gradle.api.tasks.SourceSetContainer)?.findByName('main')
            boolean javaSources = main?.allJava?.srcDirs?.any {{ dir ->
                !fileTree(dir) {{ include '**/*.java' }}.files.isEmpty()
            }} ?: false
            if ((!kotlinProject || javaSources) && !pluginManager.hasPlugin('{PLUGIN_ID}') &&
                    !pluginManager.hasPlugin('{JAVA_PLUGIN_ID}')) {{
                pluginManager.apply('{PLUGIN_ID}')
            }}
        }}
    }}
}}
"""
    if manifest is not None:
        # Gradle supplies each module's reports; mixed modules preserve both language reports.
        destination = json.dumps(str(manifest)).replace('$', '\\$')
        script += f"""
def failedConventionTasks = java.util.Collections.synchronizedList([])
gradle.projectsEvaluated {{
    def reports = gradle.rootProject.allprojects.findAll {{
        it.pluginManager.hasPlugin('{PLUGIN_ID}') || it.pluginManager.hasPlugin('{JAVA_PLUGIN_ID}') ||
                it.pluginManager.hasPlugin('{KOTLIN_PLUGIN_ID}')
    }}.collectMany {{ project ->
        boolean mixed = (project.pluginManager.hasPlugin('{PLUGIN_ID}') || project.pluginManager.hasPlugin('{JAVA_PLUGIN_ID}')) &&
                project.pluginManager.hasPlugin('{KOTLIN_PLUGIN_ID}')
        def names = mixed ? ['report.json', 'report-kotlin.json'] : ['report.json']
        names.collect {{ name ->
            def report = project.layout.buildDirectory.file('reports/build-convention/' + name).get().asFile
            report.delete()
            [project: name == 'report-kotlin.json' ? project.path + '/kotlin' : project.path, file: report.absolutePath]
        }}
    }}
    new File({destination}).text = groovy.json.JsonOutput.toJson([reports: reports, failures: []])
}}
gradle.taskGraph.afterTask {{ task, state ->
    if (state.failure != null) failedConventionTasks.add([project: task.project.path, task: task.name])
}}
gradle.buildFinished {{
    def manifest = new File({destination})
    if (manifest.isFile()) {{
        def value = new groovy.json.JsonSlurper().parse(manifest)
        value.failures = failedConventionTasks
        manifest.text = groovy.json.JsonOutput.toJson(value)
    }}
}}
"""
    return script


CHECKSTYLE = re.compile(r'\[ant:checkstyle\] \[(?:ERROR|WARN)\] (.+?):(\d+)(?::\d+)?: (.+)')
LISTED = re.compile(r'\s+- \[([A-Z][A-Z0-9-]*)\] (\S+) (.+)')
NO_BASE = 'changedCodeCoverage'
# Report groups and listed rule IDs of the architecture validator; every other check is a convention check.
ARCHITECTURE = ('architecture', 'archUnit')
GROUPS = {'architecture': '아키텍처', 'convention': '컨벤션'}
CONFIG = '.code-risk.yml'
JAR = SCRIPTS.parent/'build'/'libs'/'code-risk-analyzer-0.0.1-SNAPSHOT.jar'
COMPILE = ['./gradlew', 'testClasses']
ARCHITECTURE_TASKS = {'validateHexagonalArchitecture', 'validateEntityNamingConvention',
                      'validateDomainStaticFactoryConvention', 'validateArchitectureConventions',
                      'validateArchUnitArchitecture', 'validateClaudeConventions',
                      'validateKotlinDomainConventions', 'validateKotlinArchUnitArchitecture'}


class Unavailable(ValueError):
    """build-convention itself could not be loaded (not installed), as opposed to a check that failed."""


def settings(project, jar=JAR, process=subprocess.run):
    """validators of .code-risk.yml: {'architecture': bool or None, 'convention': bool or None} (None: not set)."""
    if not (Path(project)/CONFIG).is_file():
        return {name: None for name in GROUPS}
    if not Path(jar).is_file():
        raise ValueError(f'{CONFIG}의 validators 설정을 읽으려면 분석기 jar가 필요합니다 (install.sh로 빌드)')
    result = process(['java', '-jar', str(jar), 'validators', str(project)], stdout=subprocess.PIPE,
                     stderr=subprocess.DEVNULL, text=True, timeout=120)
    lines = [line for line in (result.stdout or '').splitlines() if line.strip()]
    if result.returncode or not lines:
        reason = next((line for line in lines if line.startswith('Error:')), '')
        raise ValueError(f'{CONFIG}을 읽지 못했습니다' + (f' ({reason[6:].strip()})' if reason else ''))
    value = json.loads(lines[-1])
    if set(value) != set(GROUPS) or any(item not in (True, False, None) for item in value.values()):
        raise ValueError('분석기의 validators 응답 형식이 올바르지 않습니다')
    return value


def selection(configured, requested=None):
    """Which groups run, why the others do not, and which were switched on explicitly (they may not be skipped).

    requested is the request sentence's own choice (True/False/None). A sentence that says no conventions turns both
    groups off; one that asks for them turns conventions on. Unset architecture follows convention.
    """
    if requested is False:
        return {'enabled': [], 'off': {name: '요청 문장에서 끔' for name in GROUPS}, 'required': []}
    convention = True if requested else configured['convention'] if configured['convention'] is not None else True
    architecture = configured['architecture'] if configured['architecture'] is not None else convention
    off = {}
    if not convention:
        off['convention'] = f'{CONFIG} validators.convention.enabled: false'
    if not architecture:
        off['architecture'] = (f'{CONFIG} validators.architecture.enabled: false' if configured['architecture'] is False
                               else f'{CONFIG} validators.convention.enabled: false (architecture 미설정)')
    return {'enabled': [name for name in GROUPS if name not in off], 'off': off,
            'required': [name for name in GROUPS if name not in off and (configured[name] or requested and name == 'convention')]}


def group(check):
    return 'architecture' if check in ARCHITECTURE else 'convention'


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


def git_base(project, requested=None):
    """Resolve the same default refs as the plugin; lack of a ref is not lack of a repository."""
    def git(*args):
        return subprocess.run(['git', '-C', str(project), *args], stdout=subprocess.PIPE,
                              stderr=subprocess.DEVNULL, text=True, timeout=30)
    inside = git('rev-parse', '--is-inside-work-tree')
    if inside.returncode:
        if any((folder/'.git').exists() for folder in [project, *project.parents]):
            raise ValueError('Git 저장소 상태를 확인하지 못했습니다')
        return requested, False
    if requested:
        return requested, True
    for candidate in ('origin/main', 'origin/master', 'main', 'master'):
        if git('rev-parse', '--verify', candidate + '^{commit}').returncode == 0:
            return candidate, True
    return None, True


def run(project, output, base_ref=None, process=subprocess.run, off=None):
    """Run check once, copy the plugin report to `output` with the printed violations; returns the report.

    off maps a switched-off group to its reason: its checks are recorded as skipped and its violations left out.
    """
    project, output = Path(project).resolve(), Path(output).resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    base_ref, git_repository = git_base(project, base_ref)
    manifest = output.with_suffix('.projects.json')
    manifest.unlink(missing_ok=True)
    script = output.with_suffix('.init.gradle')
    script.write_text(init_script(version(), manifest), encoding='utf-8')
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
    if not manifest.is_file():
        # Gradle's own failure headline says why check aborted before the plugin wrote its report.
        start = next((index + 1 for index, line in enumerate(lines) if line.strip() == '* What went wrong:'), None)
        cause = '\n'.join(line for line in lines[start:start + 3] if line.strip()) if start is not None else ''
        if any('build-convention' in line and ('Could not find' in line or 'Could not resolve' in line) for line in lines):
            raise Unavailable('build-convention을 찾지 못했습니다. install.sh로 설치하세요')
        raise ValueError('컨벤션 검사가 리포트를 만들기 전에 실패했습니다' + (f'\n{cause}' if cause else ''))
    inventory = json.loads(manifest.read_text(encoding='utf-8'))
    if not isinstance(inventory, dict) or not isinstance(inventory.get('reports'), list) or not isinstance(inventory.get('failures'), list):
        raise ValueError('컨벤션 프로젝트 목록 형식이 올바르지 않습니다')
    if not inventory['reports']:
        raise Unavailable('컨벤션 검증 대상 Java/Kotlin JVM 프로젝트가 없습니다')
    projects = {}
    for entry in inventory['reports']:
        path = Path(entry['file'])
        if not path.is_file():
            raise ValueError(f"컨벤션 검증 리포트가 없습니다: {entry['project']}")
        value = json.loads(path.read_text(encoding='utf-8'))
        if (not isinstance(value, dict) or value.get('status') not in ('PASS', 'FAIL')
                or not isinstance(value.get('checks'), dict)
                or any(status not in ('PASS', 'FAIL', 'SKIPPED', 'NOT_APPLIED') for status in value['checks'].values())):
            raise ValueError('컨벤션 검증 리포트 형식이 올바르지 않습니다')
        projects[entry['project']] = value
    if len(projects) != len(inventory['reports']):
        raise ValueError('컨벤션 검증 리포트 형식이 올바르지 않습니다')
    value = dict(projects.get(':') or next(iter(projects.values())))
    ranks = {'NOT_APPLIED': 0, 'PASS': 1, 'SKIPPED': 2, 'FAIL': 3}
    checks = {}
    for item in projects.values():
        for name, status in item['checks'].items():
            checks[name] = max(checks.get(name, 'NOT_APPLIED'), status, key=ranks.get)
    value['checks'] = checks
    reviews = [(name, item['conventionReview']) for name, item in projects.items() if 'conventionReview' in item]
    if reviews:
        if any(not isinstance(review, dict) or review.get('status') not in ('REVIEW', 'NOT_APPLIED')
               or not isinstance(review.get('requirements'), list) or not isinstance(review.get('errors'), list)
               for _, review in reviews):
            raise ValueError('컨벤션 실행 의미 검토 리포트 형식이 올바르지 않습니다')
        value['conventionReview'] = {
            'status': 'REVIEW' if any(review['status'] == 'REVIEW' for _, review in reviews) else 'NOT_APPLIED',
            'scope': 'RUNTIME_SEMANTICS',
            'requirements': [dict(item, project=name) for name, review in reviews for item in review['requirements']],
            'errors': [f'{name}: {error}' for name, review in reviews for error in review['errors']],
        }
    off = off or {}
    failures = inventory['failures']
    unexpected = [item for item in failures
                  if ('architecture' if item['task'] in ARCHITECTURE_TASKS else 'convention') not in off
                  and not (item['task'] == 'validateChangedCodeCoverage' and not git_repository and not base_ref)]
    # A nonzero Gradle exit is only ignorable when its recorded failures are explicitly switched off or need Git.
    value['skipped'] = {name: off[group(name)] for name in checks if group(name) in off and name != 'gradleExecution'}
    value['violations'] = [item for item in violations(project, '\n'.join(lines))
                           if ('architecture' if item.get('rule', '').startswith('ARCH-') else 'convention') not in off]
    value['baseRef'] = base_ref
    value['gitRepository'] = git_repository
    value['gradleExitCode'] = result.returncode
    if result.returncode and (unexpected or not failures) and not blocking(value):
        value['checks']['gradleExecution'] = 'FAIL'
    value['taskFailures'] = failures
    value['projects'] = projects
    value['status'] = 'FAIL' if blocking(value) else 'PASS'
    output.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')
    return value


def failing(report):
    """Failing checks of the groups that are switched on."""
    skipped = report.get('skipped') or {}
    return sorted(name for name, status in report['checks'].items() if status == 'FAIL' and name not in skipped)


def blocking(report):
    """Only a verified non-Git directory can exempt changed-code coverage."""
    skipped = report.get('skipped') or {}
    incomplete = [name for name, status in report['checks'].items() if status == 'SKIPPED' and name not in skipped]
    return [name for name in sorted(set(failing(report) + incomplete))
            if name != NO_BASE or report.get('baseRef') or report.get('gitRepository') is not False]


def label(names):
    return ', '.join(NAMES.get(name, name) for name in names)


def summary(report, before=None):
    names = blocking(report)
    lines = ['컨벤션 검증: ' + ('통과' if not names else '실패 - ' + label(names))]
    review = report.get('conventionReview') or {}
    if review.get('status') == 'REVIEW':
        if not names:
            lines[0] = '컨벤션 검증: 구조·품질 검사 통과 (실행 의미 검토 필요)'
        requirements = review.get('requirements') or []
        missing = sum(item.get('status') == 'MISSING' for requirement in requirements
                      for item in requirement.get('evidence', []))
        lines.append(f'실행 의미 검토(REVIEW): {len(requirements)}개 대상, 테스트 실행 근거 누락 {missing}개')
        lines.extend('- ' + str(error) for error in review.get('errors', []))
    if before is not None and names:
        existing = [name for name in names if name in failing(before)]
        if existing:
            lines.append('그중 변경 전부터 실패하던 검사: ' + label(existing))
    reasons = {}
    for name, reason in (report.get('skipped') or {}).items():
        reasons.setdefault(reason, []).append(name)
    for reason, names in reasons.items():
        lines.append(f'설정으로 건너뜀(SKIP): {label(names)} - {reason}')
    if NO_BASE in failing(report) and not report.get('baseRef'):
        if report.get('gitRepository') is False:
            if not blocking(report):
                lines[0] = '컨벤션 검증: 완료 (변경 코드 커버리지 확인 불가)'
            lines.append('Git 저장소가 아니어서 변경 코드 커버리지는 확인하지 못했습니다')
        else:
            lines.append('Git 비교 기준을 찾지 못해 변경 코드 커버리지를 검증하지 못했습니다')
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
        chosen = selection(settings(args.project))
    except (ValueError, OSError, subprocess.TimeoutExpired) as error:
        print(f'컨벤션 검증 거부: {error}')
        return 1
    if not chosen['enabled']:
        print('컨벤션 검증: 설정으로 건너뜀(SKIP) - ' + '; '.join(sorted(set(chosen['off'].values()))))
        return 0
    try:
        report = run(args.project, args.output, args.base_ref, **({'off': chosen['off']} if chosen['off'] else {}))
    except (Unavailable, OSError) as error:
        print(f'컨벤션 검증 불가: {error}')
        # Switched on explicitly in .code-risk.yml: not being able to run it is a failure, never a skip.
        return 1 if chosen['required'] else 2
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
