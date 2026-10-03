"""build-convention's file templates: shown to the models as examples, and their shared global types copied into a project.

The templates are one complete context (`book` in `com.example.demo`) whose every file passes the full convention
check. Global types every context needs (ProblemDetails, BaseEntity, ...) are copied into a project that lacks them,
under the project's own base package, so a generated change never has to invent them.
"""
from pathlib import Path
import re

TEMPLATES = Path(__file__).resolve().parent.parent.parent/'build-convention'/'docs'/'templates'
BASE = 'com.example.demo'
GLOBAL = re.compile(r'src/(main|test)/java/com/example/demo/global/')
# One file per kind, so the examples stay small; the tree still lists every file.
EXAMPLES = (
    'main/java/com/example/demo/book/domain/Book.java',
    'main/java/com/example/demo/book/domain/vo/Isbn.java',
    'main/java/com/example/demo/book/domain/event/BookRegistered.java',
    'main/java/com/example/demo/book/domain/exception/BookDomainException.java',
    'main/java/com/example/demo/book/domain/exception/InvalidIsbnException.java',
    'main/java/com/example/demo/book/application/port/in/RegisterBookUseCase.java',
    'main/java/com/example/demo/book/application/port/in/RegisterBookCommand.java',
    'main/java/com/example/demo/book/application/port/in/RegisterBookResult.java',
    'main/java/com/example/demo/book/application/port/out/BookRepositoryPort.java',
    'main/java/com/example/demo/book/application/port/out/BookEventPort.java',
    'main/java/com/example/demo/book/application/exception/BookApplicationException.java',
    'main/java/com/example/demo/book/application/exception/DuplicateIsbnException.java',
    'main/java/com/example/demo/book/application/service/RegisterBookService.java',
    'main/java/com/example/demo/book/adapter/in/web/BookController.java',
    'main/java/com/example/demo/book/adapter/in/web/BookExceptionHandler.java',
    'main/java/com/example/demo/book/adapter/in/web/request/RegisterBookRequest.java',
    'main/java/com/example/demo/book/adapter/in/web/response/RegisterBookResponse.java',
    'main/java/com/example/demo/book/adapter/out/persistence/BookEntity.java',
    'main/java/com/example/demo/book/adapter/out/persistence/BookJpaRepository.java',
    'main/java/com/example/demo/book/adapter/out/persistence/BookMapper.java',
    'main/java/com/example/demo/book/adapter/out/persistence/BookPersistenceAdapter.java',
    'main/java/com/example/demo/book/adapter/out/event/BookEventAdapter.java',
    'test/java/com/example/demo/DemoApplicationTests.java',
    'test/java/com/example/demo/book/domain/BookTest.java',
    'test/java/com/example/demo/book/domain/vo/IsbnTest.java',
    'test/java/com/example/demo/book/application/service/FakeBookRepository.java',
    'test/java/com/example/demo/book/application/service/RegisterBookServiceTest.java',
    'test/java/com/example/demo/book/adapter/in/web/BookControllerTest.java',
    'test/java/com/example/demo/book/adapter/out/persistence/BookPersistenceAdapterTest.java',
)
# Imports that need a dependency the project may not declare: (import prefixes, build file pattern).
REQUIRES = (
    (('lombok.',), re.compile(r'lombok')),
    (('jakarta.persistence.', 'org.springframework.data.', 'org.springframework.dao.'), re.compile(r'starter-data-jpa')),
    (('jakarta.servlet.', 'org.springframework.web.', 'org.springframework.http.', 'org.springframework.mock.web.'),
     re.compile(r'starter-web(?:mvc)?\b(?!flux)')),
)
PROPERTIES = (
    # Application Services are final classes, so transactions need interface (JDK) proxies.
    ('spring.aop.proxy-target-class', 'false', None),
    ('spring.jpa.open-in-view', 'false', 'starter-data-jpa'),
)


def files():
    return sorted(path.relative_to(TEMPLATES).as_posix() for path in TEMPLATES.rglob('*') if path.is_file()) \
        if TEMPLATES.is_dir() else []


def guidance(examples=True):
    """The template tree, and with examples the code of one file per kind, as Markdown for a model prompt."""
    paths = files()
    if not paths:
        return ''
    parts = ['', '## 파일 틀 (모든 파일이 컨벤션 검사를 통과함)', '',
             f'`{BASE}`는 프로젝트의 기본 패키지, `book`/`Book`은 이번 Context 이름으로 바꿔 같은 모양으로 만든다. '
             'Production 클래스마다 테스트를 둔다. `global` 아래 타입은 프로젝트에 없으면 실행 전에 도구가 넣어 주므로 '
             '만들거나 바꿀 파일에 넣지 않고 그대로 사용한다.', '', '```text']
    parts += paths + ['```']
    if examples:
        for name in EXAMPLES:
            path = TEMPLATES/'src'/name
            if path.is_file():
                parts += ['', f'`src/{name}`', '```java', path.read_text(encoding='utf-8').rstrip(), '```']
    return '\n'.join(parts)


def base_package(project):
    """The package of the @SpringBootApplication class, or None."""
    for path in sorted((project/'src/main/java').rglob('*.java')):
        text = path.read_text(encoding='utf-8', errors='replace')
        if re.search(r'@(?:[\w.]+\.)?SpringBootApplication\b', text):
            match = re.search(r'^\s*package\s+([\w.]+)\s*;', text, re.M)
            return match.group(1) if match else None
    return None


def _build_text(project):
    return '\n'.join(path.read_text(encoding='utf-8', errors='replace')
                     for path in (project/'build.gradle', project/'build.gradle.kts') if path.is_file())


def _imports(text):
    return re.findall(r'^\s*import\s+(?:static\s+)?([\w.]+)', text, re.M)


def provide(project):
    """Copy the global template types (and their tests) the project lacks; returns the written project paths.

    A file is skipped when the project already has a class of that name, when its imports need a dependency the
    build does not declare, or when it uses a global template type that was skipped.
    """
    project = Path(project)
    package = base_package(project)
    if not package or not TEMPLATES.is_dir():
        return []
    build = _build_text(project)
    existing = {path.stem for path in project.glob('src/*/java/**/*.java')}
    stems, candidates = set(), {}
    for name in files():
        if GLOBAL.match(name) and name.endswith('.java'):
            stems.add(Path(name).stem)
            text = (TEMPLATES/name).read_text(encoding='utf-8')
            imports = _imports(text)
            missing = any(any(item.startswith(prefix) for item in imports for prefix in prefixes) and not pattern.search(build)
                          for prefixes, pattern in REQUIRES)
            if not missing and Path(name).stem not in existing:
                candidates[name] = text
    # Drop files that use a skipped global type (same-package uses have no import), until nothing changes.
    changed = True
    while changed:
        kept = {Path(name).stem for name in candidates}
        skipped = [name for name, text in candidates.items()
                   if any(stem not in kept and re.search(r'\b' + stem + r'\b', text) for stem in stems - {Path(name).stem})]
        for name in skipped:
            del candidates[name]
        changed = bool(skipped)
    written = []
    for name, text in sorted(candidates.items()):
        target = project/name.replace('com/example/demo', package.replace('.', '/'), 1)
        if target.exists():
            continue
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text.replace(BASE, package), encoding='utf-8')
        written.append(target.relative_to(project).as_posix())
    return written + _properties(project, build)


def _properties(project, build):
    path = project/'src/main/resources/application.properties'
    current = path.read_text(encoding='utf-8') if path.is_file() else ''
    others = '\n'.join(item.read_text(encoding='utf-8', errors='replace')
                       for item in (project/'src/main/resources').glob('application.y*ml'))
    lines = [f'{key}={value}' for key, value, needs in PROPERTIES
             if (not needs or needs in build) and not re.search(r'^\s*' + re.escape(key) + r'\s*[=:]', current, re.M)
             and key.rsplit('.', 1)[1] not in others]
    if not lines:
        return []
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(current + ('' if not current or current.endswith('\n') else '\n') + '\n'.join(lines) + '\n', encoding='utf-8')
    return [path.relative_to(project).as_posix()]
