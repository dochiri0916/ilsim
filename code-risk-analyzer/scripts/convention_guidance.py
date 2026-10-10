"""Sync consumer guidance from the same locally published artifact used by ai's convention check."""
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import tempfile
import zipfile

DESTINATION = Path('docs/build-convention')
MANIFEST = '.ai-guidance.json'
PREFIX = 'build-convention-guidance/'
START = '<!-- build-convention-guidance: start -->'
END = '<!-- build-convention-guidance: end -->'
EXCLUDED = {'.git', '.gradle', 'build', 'target', 'out', 'node_modules', '__pycache__'}


def projects(cwd, find_project):
    try:
        return [find_project(cwd)]
    except ValueError:
        result = []
        for directory, folders, _ in os.walk(cwd, followlinks=False):
            root = Path(directory)
            folders[:] = sorted(name for name in folders if name not in EXCLUDED and not (root/name).is_symlink())
            if (root/'gradlew').is_file() and any((root/name).is_file() for name in
                    ('settings.gradle', 'settings.gradle.kts', 'build.gradle', 'build.gradle.kts')):
                result.append(root)
                folders[:] = []  # Multi-module builds share the root's instructions.
        return result


def bundle(version, artifact=None):
    artifact = artifact or Path.home()/'.m2/repository/io/github/dochiri0916/build-convention'/version/f'build-convention-{version}.jar'
    with zipfile.ZipFile(artifact) as archive:
        properties = archive.read('build-convention-version.properties').decode('utf-8')
        if f'version={version}' not in properties.splitlines():
            raise ValueError('플러그인 JAR의 컨벤션 버전이 일치하지 않습니다')
        files = {}
        for entry in archive.infolist():
            if not entry.filename.startswith(PREFIX) or entry.is_dir():
                continue
            name = entry.filename[len(PREFIX):]
            relative = PurePosixPath(name)
            if relative.is_absolute() or '..' in relative.parts or '\\' in name or name == MANIFEST:
                raise ValueError('컨벤션 문서 경로가 올바르지 않습니다')
            files[name] = archive.read(entry)
        if 'conventions/README.md' not in files or 'templates/README.md' not in files:
            raise ValueError('플러그인에 컨벤션 문서가 없습니다 (install.sh로 다시 설치하세요)')
        return files


def digest(data):
    return hashlib.sha256(data).hexdigest()


def checked_path(root, relative):
    path = root/relative
    for part in [path, *path.parents]:
        if part.is_symlink():
            raise ValueError(f'심볼릭 링크에는 컨벤션 문서를 배치하지 않습니다: {part}')
        if part == root:
            break
    if not path.is_relative_to(root):
        raise ValueError('컨벤션 문서 경로가 프로젝트 밖을 가리킵니다')
    return path


def write(path, data):
    if path.is_file() and path.read_bytes() == data:
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=path.parent, delete=False) as output:
        temporary = Path(output.name)
        output.write(data)
    try:
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def agents_text(text, version):
    block = (START + '\n'
             f'- ai 컨벤션 안내 버전: {version}. 프로젝트가 별도 플러그인 버전을 적용하면 일치 여부를 먼저 확인한다.\n'
             '- Java/Spring 또는 Kotlin/JVM 작업 전 docs/build-convention/conventions/README.md를 읽는다.\n'
             '- 상세 문서와 templates 예시는 이번에 수정하는 계층·기술에 해당하는 것만 읽고, 이미 읽은 문서는 다시 읽지 않는다.\n'
             '- 기존 프로젝트 지침과 명시적인 사용자 요청을 우선한다.\n'
             '- ai 작업 뒤 자동 검사가 실행되므로 같은 검사(./gradlew check 등)를 작업 중 반복하지 않는다.\n'
             '- 검증 실패를 해결하기 위해 규칙을 완화하거나 검사를 우회하지 않는다.\n' + END)
    if START in text or END in text:
        if text.count(START) != 1 or text.count(END) != 1 or text.index(START) > text.index(END):
            raise ValueError('AGENTS.md의 컨벤션 관리 블록이 손상되어 갱신하지 않습니다')
        return text[:text.index(START)] + block + text[text.index(END) + len(END):]
    return text + ('\n\n' if text and not text.endswith('\n') else '\n' if text else '') + block + '\n'


def sync(project, version, files):
    project = Path(project).absolute()
    agents = checked_path(project, Path('AGENTS.md'))
    text = agents_text(agents.read_bytes().decode('utf-8') if agents.exists() else '', version)
    manifest = checked_path(project, DESTINATION/MANIFEST)
    previous = json.loads(manifest.read_bytes()) if manifest.exists() else {}
    if not isinstance(previous, dict) or previous and previous.get('schemaVersion') != 1:
        raise ValueError('컨벤션 문서 관리 정보가 올바르지 않습니다')
    old = previous.get('files', {})
    if not isinstance(old, dict) or any(not isinstance(name, str) or not isinstance(value, str)
                                       for name, value in old.items()):
        raise ValueError('컨벤션 문서 관리 정보가 올바르지 않습니다')
    paths = {}
    # Preflight every file before writing, preserving local edits and unrelated documents.
    for name in set(old) | set(files):
        relative = PurePosixPath(name)
        if not relative.parts or relative.is_absolute() or '..' in relative.parts or '\\' in name or name == MANIFEST:
            raise ValueError('컨벤션 문서 관리 경로가 올바르지 않습니다')
        path = checked_path(project, DESTINATION/name)
        paths[name] = path
        if path.exists():
            actual = digest(path.read_bytes())
            if actual != old.get(name) and (name not in files or actual != digest(files[name])):
                raise ValueError(f'직접 수정한 컨벤션 문서를 보존합니다: {path.relative_to(project)}')
    for name, data in files.items():
        write(paths[name], data)
    for name in set(old) - set(files):
        paths[name].unlink(missing_ok=True)
    write(agents, text.encode('utf-8'))
    value = {'schemaVersion': 1, 'pluginVersion': version,
             'files': {name: digest(data) for name, data in sorted(files.items())}}
    write(manifest, (json.dumps(value, ensure_ascii=False, indent=2) + '\n').encode('utf-8'))
    return f'{project}/docs/build-convention/conventions/README.md'


def prepare(cwd, find_project, version, say, brief=False):
    roots = projects(cwd, find_project)
    if not roots:
        return ''
    try:
        selected = version()
        files = bundle(selected)
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
        say(f'컨벤션 안내: 배치하지 못했습니다 - {error}')
        return ''  # Guidance is optional; post-task validation remains unchanged.
    guides = []
    for root in roots:
        try:
            guides.append(sync(root, selected, files))
        except (OSError, ValueError, TypeError) as error:
            say(f'컨벤션 안내: {root} 갱신하지 못했습니다 - {error}')
    if not guides:
        return ''
    if brief:
        # Plain value-object/DTO work: the entry document and one existing example are enough.
        return ('Java/Spring 또는 Kotlin/JVM 코드를 작성하기 전에 다음 컨벤션 README만 읽고, 상세 문서는 읽지 말고 같은 패키지의 기존 코드 하나를 '
                '본보기로 따라라. 기존 프로젝트 지침과 명시적 사용자 요청을 우선한다:\n' + '\n'.join(guides) + '\n\n')
    return ('Java/Spring 또는 Kotlin/JVM 코드를 작성하기 전에 다음 컨벤션 README를 읽고, 이번에 수정하는 계층·기술에 해당하는 상세 문서·템플릿만 '
            '읽어라(해당 없는 문서는 읽지 않는다). 기존 프로젝트 지침과 명시적 사용자 요청을 우선하고, 프로젝트가 별도 '
            '컨벤션 버전을 적용하면 안내 버전과 일치하는지 확인하라:\n' + '\n'.join(guides) + '\n\n')
