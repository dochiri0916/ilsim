# 분석 완전성과 선택형 CI 정책

`scan`의 console, JSON, SARIF 보고서는 분석 범위와 정적 분석의 한계를 표시한다. JSON에서는 `metrics.completeness`, SARIF에서는 `runs[0].properties.metrics.completeness`에 기록한다. 기존 JSON 버전 `1`에 필드를 추가하며 기존 필드는 유지한다.

## 분석 완전성 지표

| 필드 | 의미 |
|---|---|
| `filesDiscovered` | 설정된 생산·테스트 소스 범위와 excludes 적용 후 발견한 파일 수 |
| `filesSelected` | 전체 scan 또는 `--files-file`로 선택한 seed 파일 수 |
| `filesParsedAsContext` | seed 외에 테스트 연결·호출된 생산 헬퍼 분석을 위해 추가 파싱한 파일 수. 독립 위험 진입점은 확장하지 않음 |
| `methodCalls`, `unresolvedMethodCalls`, `unresolvedCallRatio` | 선택된 생산 AST의 메서드 호출 수, 심볼 해석 실패 수, 실패 수 / 전체 호출 수 |
| `testObligations`, `unknownTestObligations`, `unknownRatio` | 현재 적정성 분석 범위의 항목 수, UNKNOWN 수, UNKNOWN 수 / 전체 항목 수 |
| `limitedPatterns` | 선택된 생산 AST에서 보안 보호 제어 흐름의 입증이 제한되는 LOOP·TRY·SWITCH·SYNCHRONIZED 구문 개수 |
| `configuredSourceRoots`, `configuredExcludes`, `disabledRules` | 사용자가 지정한 소스 범위·제외 패턴·비활성 규칙 |
| `limitations` | 분석 범위와 기본 탐색 제외, 실행 안전성을 입증하지 않는다는 설명 |

분모가 0인 비율은 0이다. 호출 지표는 생성자, 테스트·추가 문맥 호출을 포함하지 않는다. 파싱할 수 없는 파일의 호출도 셀 수 없으며, 별도의 `metrics.filesFailedToParse`와 PARSE 진단을 함께 확인해야 한다. recoverable parse error는 기존 PARSE 경고를 유지한다.

`limitedPatterns`는 파일 제외나 모든 규칙의 미지원 판정이 아니다. 보안 보호 검사를 보수적으로 처리할 수 있는 구문 개수이며, 실제 위험 호출과 연결되었는지는 별도 규칙 결과를 확인한다. 메서드 간 흐름·커스텀 API 등 모든 미지원 패턴을 열거하는 지표는 아니다.

탐색은 기본적으로 `.git`, `.gradle`, `build`, `target`, `out`, `node_modules`, `generated`, `generated-sources` 하위 디렉터리와 심볼릭 링크를 제외한다. 설정한 root 자체는 탐색 대상이다. 읽기 실패 항목이 누락될 수 있다. 설정 범위 밖 파일 수나 실제 제외된 파일 전체 목록은 수집하지 않는다. 변경 파일 scan은 선택한 생산 파일을 위험 진입점으로 삼고 관련 문맥으로 테스트 적정성을 분석한다. SQL·명령 주입은 진입점의 오염된 인자가 전달된 생산 헬퍼도 추적하므로 finding 위치가 미변경 파일일 수 있다. 관련 문맥의 독립 메서드를 전체 위험 진입점으로 추가하지는 않는다. 높은 완전성 수치는 실제 동시성·롤백·멱등성·부분 실패·권한의 안전성 증명이 아니다.

보안 sink는 해석 가능한 JDBC·JPA·Runtime API 타입을 우선 사용하고, 타입이 해석되지 않는 경우에만 이름 휴리스틱을 사용한다. 파일 copy/move는 원본과 목적지 경로를 모두 확인한다. 동시성 보호는 실제 읽기 호출의 Spring Data `@Lock` 또는 JPA 잠금 인자를 확인하며 무관한 문자열·주석·서비스 메서드의 `@Lock`은 보호 근거가 아니다. 트랜잭션 catch는 모든 정상 종료 경로에서 직접 `setRollbackOnly()`를 호출하거나 예외를 재던져야 보호로 본다. 람다 안의 throw, 조건부 rollback만 있는 경로, 보상처럼 보이는 메서드 이름은 보호를 입증하지 않는다.

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

```yaml
fail:
  severity: HIGH
  confidence: HIGH
  max-unknown-ratio: 0.25
  new-test-gaps: true
  include-review: true
  on-jev-failure: true
```

- `scan`: `max-unknown-ratio`는 **테스트 적정성 UNKNOWN 비율**의 최대 허용치다. 0~1의 유한 숫자이며, 초과할 때 종료 코드 1과 `CI-UNKNOWN` 진단을 반환한다. baseline·suppression·TEST-001 비활성으로 분석 불확실성을 숨기지 않도록 원래 적정성 결과를 사용한다. 미해결 호출 비율과는 별도 지표다.
- `scan`: 선택형 [실행 증거 연결](EXECUTION_EVIDENCE.md) 후 `new-test-gaps: true`이면 baseline·suppression·규칙 설정 적용 후 남은 TEST-001의 MISSING/PARTIAL 항목에 대해 종료 코드 1과 `CI-TEST-GAPS` 진단을 반환한다. COVERED/UNKNOWN은 이 정책의 실패 대상이 아니다. baseline이 없으면 현재 범위의 모든 미제외 Gap을 신규로 취급한다. `baseline create`로 기존 항목을 기록할 수 있다. baseline fingerprint가 바뀐 항목은 다시 신규로 취급된다.

- `scan`: `include-review: true`이면 `--fail-on`/`fail.severity`·`confidence` 게이트가 `CONFIRMED`뿐 아니라 `REVIEW` finding도 차단한다. 종료 코드 1. `--fail-on off`이면 적용되지 않는다.
- `scan`: `on-jev-failure: true`이면 `--jev` 실행이 실패(`JEV-001`)할 때 종료 코드 1과 `CI-JEV` 진단을 반환한다. 기본은 경고만 남기고 정적 결과를 유지한다.

- `scan`: `jev.max-calls`(0 이상 정수; 없으면 환경변수 `CODE_RISK_JEV_MAX_CALLS`, 그것도 없으면 무제한. `ai`는 20을 넘김)는 `--jev` 실행 한 번의 JEV API 호출 상한이다. 초과분 검토는 건너뛰고 정적 결과를 유지하며 `JEV-002` 경고 진단에 건너뛴 개수를 남긴다. 종료 코드에는 영향을 주지 않는다.

임계치와 같으면 통과한다. `--fail-on off`는 기존 CONFIRMED 심각도 게이트만 끄며 새 선택형 정책은 유지한다. scan의 파싱/규칙 실행 실패는 정책 위반보다 우선하여 기존 종료 코드 3을 반환한다. 보고서는 정책 실패 시에도 출력된다.
