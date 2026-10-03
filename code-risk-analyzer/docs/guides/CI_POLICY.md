# 분석 완전성과 선택형 CI 정책

`scan`의 console, JSON, SARIF 보고서는 분석 범위와 정적 분석의 한계를 표시한다. JSON에서는 `metrics.completeness`, SARIF에서는 `runs[0].properties.metrics.completeness`에 기록한다. 기존 JSON 버전 `1`에 필드를 추가하며 기존 필드는 유지한다.

## 분석 완전성 지표

| 필드 | 의미 |
|---|---|
| `filesDiscovered` | 설정된 생산·테스트 소스 범위와 excludes 적용 후 발견한 파일 수 |
| `filesSelected` | 전체 scan 또는 `--files-file`로 선택한 seed 파일 수 |
| `filesParsedAsContext` | seed 외에 테스트 연결을 위해 추가 파싱한 파일 수. 위험 분석 범위를 확장하지 않음 |
| `methodCalls`, `unresolvedMethodCalls`, `unresolvedCallRatio` | 선택된 생산 AST의 메서드 호출 수, 심볼 해석 실패 수, 실패 수 / 전체 호출 수 |
| `testObligations`, `unknownTestObligations`, `unknownRatio` | 현재 적정성 분석 범위의 항목 수, UNKNOWN 수, UNKNOWN 수 / 전체 항목 수 |
| `limitedPatterns` | 선택된 생산 AST에서 보안 보호 제어 흐름의 입증이 제한되는 LOOP·TRY·SWITCH·SYNCHRONIZED 구문 개수 |
| `configuredSourceRoots`, `configuredExcludes`, `disabledRules` | 사용자가 지정한 소스 범위·제외 패턴·비활성 규칙 |
| `limitations` | 분석 범위와 기본 탐색 제외, 실행 안전성을 입증하지 않는다는 설명 |

분모가 0인 비율은 0이다. 호출 지표는 생성자, 테스트·추가 문맥 호출을 포함하지 않는다. 파싱할 수 없는 파일의 호출도 셀 수 없으며, 별도의 `metrics.filesFailedToParse`와 PARSE 진단을 함께 확인해야 한다. recoverable parse error는 기존 PARSE 경고를 유지한다.

`limitedPatterns`는 파일 제외나 모든 규칙의 미지원 판정이 아니다. 보안 보호 검사를 보수적으로 처리할 수 있는 구문 개수이며, 실제 위험 호출과 연결되었는지는 별도 규칙 결과를 확인한다. 메서드 간 흐름·커스텀 API 등 모든 미지원 패턴을 열거하는 지표는 아니다.

탐색은 기본적으로 `.git`, `.gradle`, `build`, `target`, `out`, `node_modules`, `generated`, `generated-sources` 하위 디렉터리와 심볼릭 링크를 제외한다. 설정한 root 자체는 탐색 대상이다. 읽기 실패 항목이 누락될 수 있다. 설정 범위 밖 파일 수나 실제 제외된 파일 전체 목록은 수집하지 않는다. 변경 파일 scan은 선택한 생산 파일에서 위험을 탐지하고 관련 문맥으로 테스트 적정성을 분석한다. 높은 완전성 수치는 실제 동시성·롤백·멱등성·부분 실패·권한의 안전성 증명이 아니다.

## 설정

대상 프로젝트의 `.code-risk.yml`에 지정한다. 새 정책은 기본적으로 모두 비활성이다. 단계별 처리 기준은 아래 표를 따른다.

| 대상 | 기본 | 선택형 차단 |
|---|---|---|
| CONFIRMED 위험 | 차단(`fail.severity`·`confidence`) | `--fail-on off`로 해제 |
| REVIEW 위험 | 통과 | `include-review` |
| Test Gap(MISSING/PARTIAL) | 통과 | `new-test-gaps` |
| UNKNOWN 비율 | 통과 | `max-unknown-ratio` |
| 파싱·규칙 실행 실패 | 항상 종료 코드 3 | - |
| JEV 실패 | 경고 | `on-jev-failure` |
| 변이 미검증(SURVIVED 등) | 통과 | `max-survived-mutations` |

```yaml
fail:
  severity: HIGH
  confidence: HIGH
  max-unknown-ratio: 0.25
  new-test-gaps: true
  max-survived-mutations: 0
  include-review: true
  on-jev-failure: true
```

- `scan`: `max-unknown-ratio`는 **테스트 적정성 UNKNOWN 비율**의 최대 허용치다. 0~1의 유한 숫자이며, 초과할 때 종료 코드 1과 `CI-UNKNOWN` 진단을 반환한다. baseline·suppression·TEST-001 비활성으로 분석 불확실성을 숨기지 않도록 원래 적정성 결과를 사용한다. 미해결 호출 비율과는 별도 지표다.
- `scan`: 선택형 [실행 증거 연결](EXECUTION_EVIDENCE.md) 후 `new-test-gaps: true`이면 baseline·suppression·규칙 설정 적용 후 남은 TEST-001의 MISSING/PARTIAL 항목에 대해 종료 코드 1과 `CI-TEST-GAPS` 진단을 반환한다. COVERED/UNKNOWN은 이 정책의 실패 대상이 아니다. baseline이 없으면 현재 범위의 모든 미제외 Gap을 신규로 취급한다. `baseline create`로 기존 항목을 기록할 수 있다. baseline fingerprint가 바뀐 항목은 다시 신규로 취급된다.
- `mutation`: `max-survived-mutations`는 SURVIVED·NO_COVERAGE·RUN_ERROR·MEMORY_ERROR·UNKNOWN 개수에서 검토 예외(SURVIVED만 해당)를 뺀 값의 최대 허용치다. 0 이상의 정수이며, 초과할 때 종료 코드 1을 반환한다. TIMED_OUT·NON_VIABLE은 집계하지 않는다.

- `scan`: `include-review: true`이면 `--fail-on`/`fail.severity`·`confidence` 게이트가 `CONFIRMED`뿐 아니라 `REVIEW` finding도 차단한다. 종료 코드 1. `--fail-on off`이면 적용되지 않는다.
- `scan`: `on-jev-failure: true`이면 `--jev` 실행이 실패(`JEV-001`)할 때 종료 코드 1과 `CI-JEV` 진단을 반환한다. 기본은 경고만 남기고 정적 결과를 유지한다.

- `scan`: `jev.max-calls`(0 이상 정수, 기본 무제한)는 `--jev` 실행 한 번의 JEV API 호출 상한이다. 초과분 검토는 건너뛰고 정적 결과를 유지하며 `JEV-002` 경고 진단에 건너뛴 개수를 남긴다. 종료 코드에는 영향을 주지 않는다.

임계치와 같으면 통과한다. `--fail-on off`는 기존 CONFIRMED 심각도 게이트만 끄며 새 선택형 정책은 유지한다. scan의 파싱/규칙 실행 실패는 정책 위반보다 우선하여 기존 종료 코드 3을 반환한다. mutation의 설정 오류는 실행 전에 종료 코드 2로 처리하고, 빌드/PIT 실행 실패는 기존 종료 코드를 유지한다. 두 명령의 정책은 각각 별도로 평가하며 scan이 PIT를 실행하지 않는다. mutation의 정적 문맥 파싱과 변경 seed 연결에는 설정된 root·excludes·classpath를 사용한다. 변경 seed가 없는 전체 PIT 실행은 기존 대상 빌드 설정을 따르며 analysis.excludes가 PIT 실행 자체를 제한하지는 않는다. 보고서는 정책 실패 시에도 출력된다.

## 동등 변이 검토와 예외

PIT 생존은 동등 변이일 수도 있다. 보고서의 변이·연결된 Obligation·테스트를 검토하고, 구별 가능한 동작이 있다면 테스트를 보완한다. 동등 변이 또는 명시적으로 수용한 항목만 검토 근거와 추적 번호를 남겨 예외로 등록한다.

```yaml
fail:
  max-survived-mutations: 0
  mutation-exceptions:
    - fingerprint: '여기에 보고서의 64자리 소문자 SHA-256 fingerprint'
      reason: '동등 변이 검토 근거와 추적 번호'
```

위 fingerprint 자리에는 실제 보고서 값을 넣어야 한다. 잘못된 형식·중복 fingerprint·빈 사유·알 수 없는 예외 필드는 설정 오류다. fingerprint는 현재 변이 모델의 class·method·line·mutator·description을 길이 정보와 함께 SHA-256으로 계산하며 status는 포함하지 않는다. 위치나 설명이 바뀌면 재검토가 필요하다. 현재 모델은 PIT의 method descriptor·index·module을 보존하지 않으므로 같은 fingerprint가 여러 결과에 나타나면 예외를 적용하지 않는다.

예외는 SURVIVED 결과를 삭제하거나 KILLED로 바꾸지 않는다. 각 항목에 `fingerprint`, `exceptionReason`을 남기고 `gate`에 `survived`, `excepted`, `actionableSurvived`, `maxSurvivedMutations`, `failed`, `unmatchedExceptions`를 기록한다. 매칭되지 않는 예외는 보고서에서 확인하고 제거하거나 재검토한다. 정책 게이트는 PIT가 정상 완료한 경우에만 평가한다. `NO_MUTATION_TARGETS`나 실행 실패 보고서의 `gate`는 null이며 검증 완료를 뜻하지 않는다.
