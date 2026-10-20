#!/usr/bin/env python3
import argparse
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CATALOG = ROOT / 'src/main/resources/concurrency/specification.json'
DOCUMENT = ROOT / 'docs/reference/concurrency.md'


def link(path):
    return f'[{Path(path).stem}](../../{path})'


def render(catalog):
    rules = sorted(catalog['rules'], key=lambda r: (r['priority'], r['id']))
    counts = {status: sum(r['implementationStatus'] == status for r in rules)
              for status in ('PLANNED', 'PARTIAL', 'IMPLEMENTED', 'VALIDATED')}
    lines = [
        '# 동시성 명세 통합과 구현 진행 현황', '',
        '> 이 문서는 `scripts/render_concurrency_progress.py`로 생성한다. '
        '진행 상태는 `src/main/resources/concurrency/specification.json`에서 수정한다.', '',
        '[원본 명세](java-mysql-concurrency-spec.md) · '
        '[배포 카탈로그](../../src/main/resources/concurrency/specification.json)', '',
        catalog['scope'], '',
        '## 조회', '',
        '`CRA`는 `java -jar build/libs/code-risk-analyzer-0.0.1-SNAPSHOT.jar`다.', '',
        '```bash', 'CRA rules --spec', 'CRA rules --spec --format json',
        'CRA rules DB-CONC-009', 'CRA rules CON-002', '```', '',
        '기존 `rules` 목록·규칙 ID·설정·기준선·CI 게이트는 유지한다. 명세 ID는 조회·보고용 대응 정보이며 '
        '`.code-risk.yml`의 `rules` 키에 사용하는 실행 규칙 ID를 대신하지 않는다. '
        '`rules --spec`의 상태는 이 분석기 버전의 구현 진행 상태이며, 대상 프로젝트에서 규칙이 활성화되거나 '
        '실행되었다는 뜻이 아니다.', '',
        '## 상태와 우선순위', '',
        '| 상태 | 기준 |', '|---|---|',
        '| PLANNED | 자체 분석 구현을 아직 연결하지 않았다. 보조 도구의 기본 검사도 완료로 세지 않는다. |',
        '| PARTIAL | 실제 실행 규칙·구현 파일·기존 테스트 근거가 있으나 명세의 남은 요건이 있다. |',
        '| IMPLEMENTED | 요구된 frontend·트리거·보호 조건·판정·증거 경로를 구현했다. 전체 동적 검증은 남을 수 있다. |',
        '| VALIDATED | IMPLEMENTED 요건에 더해 명세 7절의 fixture와 실제 동시 실행 검증을 통과했다. |', '',
        '| 우선순위 | 작업 순서 |', '|---|---|',
        '| P0 | 공통 입력·판정·호환성 기반을 먼저 준비한다. |',
        '| P1 | 기존 핵심 대응 유형을 보강하고 DCL·락 해제 검사를 분석기에 연결한다. |',
        '| P2 | JVM 공유성·원자성 및 SQL 기반 불변식·bulk DML·잠금 유지 구간을 구현한다. |',
        '| P3 | 운영·격리·다중 행 조건에 따른 후보 및 독립 리뷰 흐름을 구현한다. |',
        '| P4 | 가치가 낮고 환경 의존적인 반복 조회 검사를 마지막에 검토한다. |', '',
        '우선순위는 구현 순서이며 finding의 severity가 아니다. 상태를 IMPLEMENTED 또는 VALIDATED로 올릴 때는 '
        '전체 명세 요건을 확인하고 구현·테스트 근거와 남은 작업을 함께 갱신한다. '
        '기존 fixture 통과만으로 MySQL·JMM·다중 인스턴스 검증 완료를 표시하지 않는다.', '',
        f"현재 22개: 부분 구현 {counts['PARTIAL']}개, 미구현 {counts['PLANNED']}개, "
        f"구현 완료 {counts['IMPLEMENTED']}개, 전체 검증 완료 {counts['VALIDATED']}개.", '',
        '## 공통 기반과 운영 흐름', '',
        '| 항목 | 우선순위 | 진행 상태 | 현재 범위 | 남은 작업 |', '|---|---|---|---|---|',
    ]
    for item in sorted(catalog['workItems'], key=lambda w: w['priority']):
        todo = ' / '.join(item['remainingWork']) or '이 통합 범위의 구현 완료'
        lines.append(f"| {item['id']} | {item['priority']} | {item['implementationStatus']} | {item['scope']} | {todo} |")
    lines += ['', '## 22개 규칙 대응표', '',
              '| 명세 ID | 문제 | 우선순위 | 진행 상태 | 기존 실행 규칙 |', '|---|---|---|---|---|']
    for rule in rules:
        bindings = ', '.join(b['ruleId'] + ('¹' if b['requiredEvidenceTypes'] else '') for b in rule['bindings']) or '없음'
        lines.append(f"| {rule['id']} | {rule['title']} | {rule['priority']} | {rule['implementationStatus']} | {bindings} |")
    lines += ['', '¹ JAVA-CONC-001은 `SHARED_FIELD_MUTATION`, DIST-CONC-020은 `JVM_LOCAL_LOCK` 증거가 있을 때만 연결한다.', '',
              '## 구현 근거와 남은 작업', '']
    for rule in rules:
        lines += [f"### {rule['id']} — {rule['title']}", '',
                  f"- 우선순위·상태: {rule['priority']} · {rule['implementationStatus']}",
                  f"- 현재 범위: {rule['implementedScope']}",
                  '- 구현 근거: ' + (', '.join(map(link, rule['implementationEvidence'])) or '없음'),
                  '- 테스트 근거: ' + (', '.join(map(link, rule['testEvidence'])) or '없음'),
                  '- 남은 작업: ' + '; '.join(rule['remainingWork']),
                  '- 검증 목표: ' + rule['recommendedTest'], '']
    lines += ['## 보고서와 판정 호환성', '',
              'JSON의 `concurrencySpecification`, SARIF run의 같은 이름 properties는 전체 구현 진행표다. '
              'finding의 `concurrency`에는 실제 증거에 맞는 명세 ID, 대표 ID, outcome, 우선순위, '
              '구현 상태, 한계, 권장 테스트가 들어간다. 적용되지 않는 finding에는 이 필드를 붙이지 않는다.', '',
              'CON-002 한 finding에 DB-CONC-009와 JPA-CONC-018을 함께 연결하고 009를 대표로 선택한다. '
              '추가 검사를 실행하거나 같은 finding을 복제하지 않는다. 서로 다른 실행 규칙의 finding 병합은 후속 작업이다.', '',
              'outcome은 기존 CONFIRMED/REVIEW를 일괄 치환하지 않는다. SHARED_FIELD_MUTATION 증거가 있는 '
              'CON-003은 명세의 좁은 패턴을 확인한 DEFINITE_PATTERN, TX-003은 실제 잠금·대기 검증이 필요한 '
              'REQUIRES_VALIDATION으로 연결한다. TX-001을 포함한 나머지 연결은 POTENTIAL_RISK다. '
              'CON-005는 Java DCL의 실제 증거로 DEFINITE_PATTERN·UNKNOWN·SAFE_BY_PROOF를 판정한다. '
              '다른 규칙의 실제 판정과 outcome 기반 게이트는 후속 작업이다.', '',
              'JSON의 `concurrencyAssessments`, SARIF run properties의 같은 이름 배열에는 경고와 별도의 '
              '규칙별 감사 기록을 남긴다. SAFE_BY_PROOF는 해당 공개 경로에 한정된 보호 입증이며 finding으로 '
              '만들거나 경고 수·CI 게이트에 포함하지 않는다. UNKNOWN은 누락 근거를 가진 REVIEW finding이다. '
              '감사 기록은 suppression·baseline 적용 전 판정이고, 최종 경고 상태는 findings/results를 따른다.', '',
              'CON-005의 finding과 감사 기록에는 선언·대상 필드·증거 경로 기반 `v2` 지문이 있다. '
              '줄 이동·주석 추가에는 유지되고 오버로드·공개 경로 변경에는 달라진다. 새 기준선은 기존 v1 '
              '`fingerprint`와 선택적 `stableFingerprint`를 병행 저장하며, 분석기는 기존 v1 기준선을 계속 읽는다. '
              'v2가 있는 항목은 v2로 비교해 같은 줄의 다른 경로를 억제하지 않는다. 기존 v1 항목은 '
              '재생성 전까지 줄 번호를 사용한다. 다른 규칙의 지문은 기존 방식이다.', '',
              '기존 finding status·severity·confidence 및 suppression·baseline 처리와 CI 종료 코드는 유지한다. '
              '따라서 현재 CI 게이트가 명세 6절의 DEFINITE_PATTERN 전용 게이트와 같다고 해석하지 않는다. '
              '억제 finding은 기존 SUPPRESSED 상태와 대응 정보를 함께 보존한다.', '',
              '## 진행표 검증', '', '```bash', 'python3 scripts/render_concurrency_progress.py --check',
              '../gradlew -p .. :code-risk-analyzer:clean :code-risk-analyzer:build --no-daemon', '```', '',
              '`check`는 진행 문서가 카탈로그와 일치하는지 검사한다. 테스트는 22개 ID의 완전성, '
              '실행 규칙·구현 파일·테스트 근거의 존재, 조건부 매핑, 중복 없는 대응, CLI·JSON·SARIF '
              '호환성을 확인한다. 이 검사들은 개별 동시성 규칙의 전체 런타임 검증을 대신하지 않는다.', '']
    return '\n'.join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    expected = render(json.loads(CATALOG.read_text(encoding='utf-8')))
    if args.check:
        if not DOCUMENT.is_file() or DOCUMENT.read_text(encoding='utf-8') != expected:
            parser.exit(1, 'Concurrency progress document is stale; run python3 scripts/render_concurrency_progress.py\n')
        return
    DOCUMENT.write_text(expected, encoding='utf-8')


if __name__ == '__main__':
    main()
