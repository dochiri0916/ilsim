"""Fix mechanical style problems in the Java files a task edited, before the checks run and without a model call.

Spotless is put on the build classpath by a generated Gradle init script (the project's build files stay untouched);
a project that applies Spotless itself keeps its own configuration. Only the edited files are touched. By default the
fixes are limited to unused imports, trailing whitespace and the final newline, which cannot disagree with a project's
own style; AI_AUTOFIX_FORMAT=aosp also runs google-java-format (AOSP style: 4 spaces) over those files.
Any failure leaves the files as they were and lets the checks run.
"""
import hashlib
import os
from pathlib import Path
import re
import subprocess

SPOTLESS_VERSION = '7.0.2'
GOOGLE_JAVA_FORMAT_VERSION = '1.25.2'
DISABLE = 'AI_NO_AUTOFIX'
FORMAT = 'AI_AUTOFIX_FORMAT'


def init_script(format_style=None):
    formatter = f"googleJavaFormat('{GOOGLE_JAVA_FORMAT_VERSION}').aosp()" if format_style == 'aosp' else ''
    return f"""initscript {{
    repositories {{
        gradlePluginPortal()
        mavenCentral()
    }}
    dependencies {{
        classpath 'com.diffplug.spotless:spotless-plugin-gradle:{SPOTLESS_VERSION}'
    }}
}}

allprojects {{
    pluginManager.withPlugin('java') {{
        if (!pluginManager.hasPlugin('com.diffplug.spotless')) {{
            pluginManager.apply(com.diffplug.gradle.spotless.SpotlessPlugin)
            extensions.getByName('spotless').java {{
                target 'src/main/java/**/*.java', 'src/test/java/**/*.java'
                removeUnusedImports()
                trimTrailingWhitespace()
                endWithNewline()
                {formatter}
            }}
        }}
    }}
}}
"""


def java_files(project, edits):
    """Existing Java files of the edits that lie inside the project, as absolute paths."""
    result = []
    for path in edits:
        target = Path(path)
        target = (target if target.is_absolute() else project/target).resolve()
        if (target.suffix == '.java' and target.is_file() and target.is_relative_to(project)
                and target not in result and 'src' in target.relative_to(project).parts):
            result.append(target)
    return result


def digests(files):
    result = {}
    for path in files:
        try:
            result[path] = hashlib.sha256(path.read_bytes()).hexdigest()
        except OSError:
            result[path] = None
    return result


def apply(project, edits, say, process=subprocess.run, environ=None):
    """Run spotlessApply on the edited Java files; returns the project-relative files it changed."""
    environ = os.environ if environ is None else environ
    if environ.get(DISABLE):
        return []
    project = Path(project).resolve()
    files = java_files(project, edits)
    if not files:
        return []
    before = digests(files)
    script = project/'build'/'ai'/'autofix.init.gradle'
    try:
        script.parent.mkdir(parents=True, exist_ok=True)
        script.write_text(init_script(environ.get(FORMAT)), encoding='utf-8')
        pattern = '(' + '|'.join(re.escape(str(path)) for path in files) + ')'
        result = process(['./gradlew', '--console=plain', '-q', '--init-script', str(script), 'spotlessApply',
                          '-PspotlessFiles=' + pattern], cwd=project, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                         text=True, timeout=900)
    except (OSError, subprocess.TimeoutExpired) as error:
        say(f'자동 수정: 건너뜀 ({error.__class__.__name__})')
        return []
    changed = [path.relative_to(project).as_posix() for path, digest in digests(files).items() if digest != before[path]]
    if result.returncode:
        # Spotless writes nothing when it fails to parse or resolve; report only what actually changed.
        say('자동 수정: 일부 실행 실패' + (f', {len(changed)}개 파일 수정' if changed else ', 변경 없음'))
    elif changed:
        say(f'자동 수정: {len(changed)}개 파일 정리 (' + ', '.join(changed[:3]) + (' 외' if len(changed) > 3 else '') + ')')
    else:
        say('자동 수정: 변경 없음')
    return changed
