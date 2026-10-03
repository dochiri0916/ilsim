# Spring Risk Sample

code-risk-analyzer의 정적 분석과 실제 Spring·DB·Gradle 실행을 검증하는 독립 프로젝트다.
Java 25, Spring Boot 4.1.1, Spring Data JPA, MySQL 8.4.8과 JUnit을 사용한다.
저장소 루트 빌드에는 포함하지 않으며, 루트 `.code-risk.yml`은 이 디렉터리를 분석에서 제외한다.

## 실행

Docker가 실행 중이어야 하며 Java 25, Python 3와 의존성 다운로드가 필요하다.
**저장소 루트**에서 다음 명령을 실행한다.

```sh
python3 integration-fixtures/spring-risk-sample/scripts/verify_mysql.py
```

이 명령은 기존 DB와 분리된 임시 MySQL 컨테이너를 생성하고, 새 경쟁 재현 테스트,
전체 테스트·프로젝트 빌드·분석기 빌드, 정적 scan, manifest 생성, 증거 연결 scan을 실행한다.
임의의 로컬 포트와 실행마다 생성한 암호를 사용한다. 암호는 임시 파일·환경변수로만 전달하고
저장소에 기록하지 않는다. 종료 시 이 실행에서 생성한 컨테이너와 임시 파일을 정리한다.
이 실행에서 만든 전용 `risk_sample` 스키마만 사용하며 테스트 종료 시 테이블은 제거된다.

17개 테스트가 MySQL을 사용하는 기존 8개 DB 시나리오, PricePolicy의 2개 시나리오,
실제 HTTP·MySQL 및 기동·종료의 5개 시나리오와 독립 lifecycle 2개 시나리오를 확인한다.
테스트 실패를 숨기지 않으며 위험 코드의 잘못된 결과 자체를 assertion한다.
DB 테스트에 테스트 전체를 감싸는 transaction은 없다.

- JUnit XML: `build/test-results/test/TEST-*.xml`
- JaCoCo XML: `build/reports/jacoco/test/jacocoTestReport.xml`
- JaCoCo HTML: `build/reports/jacoco/test/html/index.html`

## 변경 전후 비교와 실제 서비스

변경 전 기준선은 [회귀 검증 runner](../../docs/requirements-analysis/regression-workflow.md)로
새 테스트·구현 이전에 기록한다. 이 fixture의 통합 검증에 기존 기록을 연결하려면 다음을 실행한다.

```sh
python3 integration-fixtures/spring-risk-sample/scripts/verify_mysql.py --baseline /absolute/before.json
```

기본 실행은 현재 소스에서 시작 기준선을 만든 뒤 다시 전체 테스트·빌드를 실행한다.
이 결과를 과거 변경 전 실행 결과로 표현하지 않는다. `--mode SPEC_ONLY`와 `SPEC_CODE_REVIEW`는
DB 생성·테스트·서비스 실행 전에 거부한다. 기존 테스트 삭제·skip·실패를 허용하지 않고,
기존 테스트·빌드 파일 변경은 별도 runner의 해시 기반 검토 절차를 따른다.

`--sample.service.enabled=true`로 패키징한 fixture를 별도 JVM에 띄운다.
JDK HTTP 서버를 사용하므로 web starter dependency는 추가하지 않는다.
서버는 `127.0.0.1`의 임의 포트에서만 실행하며 준비 포트를 알린다.
`GET /ready`, `GET /discount?quantity=10`, `GET/POST /records?key=...`,
`POST /fail?key=...`을 제공한다. 정상 생성은 201, 중복은 409, 저장 후 실패는 500과 rollback이다.
HTTP 응답뿐 아니라 전용 MySQL의 독립 조회로 1행 commit·중복 1행·동시 요청의 1행·실패 후 0행을
확인한다. 실패 후 정상 재시도도 검사하고 성공·실패 모두 프로세스를 종료한다.

- `build/regression-baseline.json`: 기본 실행의 시작 기준선
- `build/regression-comparison.json`: 동일 기존 테스트 결과와 보호 파일 비교
- `build/service-verification.json`: 별도 JVM의 요청·DB 관측·소스/JAR 해시·종료
- `build/workflow-completion.json`: 회귀·실행 실패·미해결 정적 근거·자원 정리를 모은 최종 판정
- `build/evidence-reviews.json`: 요구사항·assertion 위치·현재 실행·소스 해시에 연결한 검토 기록
- `build/evidence-classification.json`: 원본 정적 상태와 검토자 판단을 분리한 항목별 분류

분류 기준과 형식은 [미해결 근거 검토](../../docs/requirements-analysis/evidence-review.md)를 따른다.
현재 TEST_GAP 0개, STATIC_LIMITATION 21개, INTENTIONAL_DEFECT 5개다.
기동 전 반복 stop·이후 기동, 실제 점유된 포트의 bind 실패·반복 실패·미기동 상태·포트 해제 후 재시도·종료 후 포트 반환을 새 테스트로 확인한다.
bind 예외 Obligation은 분석기의 원래 판정에서 COVERED가 되어 미해결 목록에서 제거됐으며, 나머지 상태/분기 근거는 assertion 검토와 정적 PARTIAL을 분리해 보존한다.
`review-definitions.json`의 명시적 assertion 검토를 승인된 소스·테스트·요구사항 해시에 고정하고,
`fixture-review-policy.json`은 의도된 결함의 정확한 ID만 허용한다. 코드가 바뀌면 재검토가 필요하다.
완료 보고서의 verificationStatus=PASSED는 실행 성공, targetRiskStatus=UNRESOLVED는 위험 보존을 뜻한다.
선언된 검증 범위가 충족되면 evidenceReviewStatus=PASSED와 전체 status=COMPLETED다.
fixture의 의도된 결함이 안전해졌다는 뜻은 아니며 targetRiskStatus=UNRESOLVED를 유지한다.

종료 코드 0은 선언된 검증 완료, 1은 실패, 2는 모드 거부, 3은 `NEEDS_REVIEW`다.
의도된 unsafe self-invocation의 HIGH Gap은 해소하지 않으므로 테스트·서비스가 통과해도
최종 완료는 `NEEDS_REVIEW`일 수 있다. 증거를 숨기거나 완료 기준을 낮추지 않는다.

## 시나리오와 기대 동작

| 대상 | 기대 동작 | 검증 |
|---|---|---|
| UnsafeService.createIfAbsent | DB UNIQUE 없는 check → insert 경쟁 후보 | 정적 분석 CON-001 및 두 조회를 barrier로 동기화한 뒤 2행 commit 확인 |
| UnsafeService.selfInvokeThenFail | self-invocation으로 서비스 transaction이 적용되지 않아 repository commit이 남음 | 정적 분석 TX-001 및 실패 후 1행 확인 |
| UnsafeService.saveThenFail 직접 proxy 호출 | 예외 발생 시 rollback | 실패 후 0행 확인 |
| SafeService.saveThenFail | 예외 발생 시 rollback | 실패 후 0행 확인 |
| UnsafeService.handleMessage | 같은 ID를 두 번 처리하면 2행 생성 | 중복 호출 테스트 |
| SafeService.handleMessage | DB UNIQUE가 두 번째 쓰기를 거부 | 충돌 예외 및 1행 확인 |
| SafeService.create 병렬 호출 | UNIQUE로 1개 commit, 1개 conflict | CountDownLatch로 시작 동기화 및 최종 1행 확인 |
| PricePolicy | 수량 1 미만 거부, 10부터 10% 할인 | 예외·경계·결과 assertion 및 PIT 대상 |

SafeService의 중복 처리 계약은 성공 응답 재사용이 아니라 충돌 예외 반환이다.
메시지는 일반 메서드 반복 호출로 모델링하며 브로커·ack·redelivery 정책을 포함하지 않는다.
단일 DB 쓰기만 사용하므로 외부 부수 효과나 outbox의 일관성을 검증하지 않는다.
단일 MySQL 8.4.8 인스턴스의 검증이며 운영 DB 설정·다중 인스턴스 동작을 보장하지 않는다.
경쟁 재현 테스트는 실제 repository의 exists 조회 결과가 반환된 뒤 테스트 전용 proxy에서
CyclicBarrier로 두 호출을 대기시킨다. 조회·저장은 mock 없이 실제 MySQL에서 실행하며 production Java 코드를 변경하지 않는다.

## 분석기에서 사용

아래 명령은 **저장소 루트**에서 실행한다.

```sh
./gradlew bootJar --no-daemon
java -jar build/libs/code-risk-analyzer-0.0.1-SNAPSHOT.jar scan integration-fixtures/spring-risk-sample --format json --output build/scan.json --fail-on off
```

scan의 상대 output 경로는 대상 프로젝트 기준이다. 보고서는 이 프로젝트의 `build/scan.json`에 생성된다.
`--fail-on off`는 의도된 위험 사례의 보고서를 살펴보기 위한 실행 옵션이다.
회귀 테스트·빌드의 실패 정책을 변경하지 않는다.
`mutation-targets.txt`는 PIT 검증 시 PricePolicy만 선택하는 대상 목록이다.
MySQL 구성으로 전환했으므로 PIT 재실행에도 전용 MySQL 접속 환경변수가 필요하다.
현재 검증 스크립트는 요청 범위인 DB 테스트와 실행 증거 연결만 수행하며 PIT를 실행하지 않는다.
PIT 플러그인과 실행 설정은 분석기가 주입하므로 build.gradle에 중복 선언하지 않는다.

기본 분석은 명시적 외부 classpath 없이 실행한다. CON-001·TX-001 이외의 탐지나 타입 해석까지 모두 보장하지 않는다.
타입 기반 분석에는 이 디렉터리에서 `./gradlew exportAnalysisClasspath`를 실행한 뒤,
`.code-risk.yml`의 `analysis.classpath`에 필요한 `build/analysis-libs/*.jar`를 개별 상대 경로로 나열한다.
분석기는 glob을 지원하지 않는다. 상세 설명은 [CLASSPATH.md](../../docs/guides/CLASSPATH.md)를 참고한다.

## 실행 증거 및 판정 검증

검증 스크립트가 현재 scan의 Obligation ID, 소스·보고서 SHA-256과 명시적 assertion 검토 기록을 사용해
`build/execution-evidence.json`을 생성한다. 해시는 테스트 실행 후 계산하며 source/report를 변경하면 다시 실행해야 한다.
manifest 검토자는 Codex이며 자동 분석기가 DB 사용이나 assertion 의미를 증명한 것으로 표현하지 않는다.
형식·신뢰 범위는 [EXECUTION_EVIDENCE.md](../../docs/guides/EXECUTION_EVIDENCE.md)를 따른다.

- `build/scan-static.json`: 증거 연결 전 결과
- `build/scan-reviewed.json`: 검토 근거를 포함한 결과
- `build/scan-unreviewed.json`: assertion 검토를 제거한 비교 결과
- `build/verification-summary.json`: 판정 변화·남은 HIGH 항목·검증 한계

스크립트는 CON-001 및 두 TRANSACTION_ROLLBACK 항목의 `PARTIAL → COVERED`, 다른 항목의 상태 유지,
production 위험 finding 보존, assertion 검토 없는 증거가 상태를 높이지 않는 것을 검사한다.
여기서 COVERED는 결함을 재현하는 테스트의 적정성 판정이다. 위험한 구현이 안전해졌다는 뜻이 아니다.

분석기는 `saveAndFlush`를 저장 동작으로 인식한다. `@Transactional` 메서드에 저장 후 예외 경로가 있거나
여러 저장 동작이 있으면 TRANSACTION_ROLLBACK 항목을 추출한다. 두 rollback 테스트는 이 항목에
실제 MySQL 실행 결과와 assertion 검토를 연결해 PARTIAL에서 COVERED로 변경되는지 확인한다.
TX-001 및 PERSISTENCE는 증거 승격 지원 대상이 아니므로 테스트가 성공해도 PARTIAL을 유지한다.
self-invocation은 내부 호출이 항상 예외를 던져 암묵적인 return 명령이 실행되지 않으며,
JaCoCo에서 해당 라인의 missed instructions가 남아 `methodLinesCovered=false`도 보존한다.
이 한계를 우회하기 위한 production 코드 변경이나 임의의 Obligation 생성은 하지 않는다.

## 작업 단계

production 코드를 먼저 준비한 뒤 테스트 코드를 별도 단계에서 작성했다.
이후에도 테스트 작성 단계에서 production 코드·기존 동작 코드·신규 동작 코드·production stub을 작성하거나 수정하지 않는다.
세부 규칙은 [AGENTS.md](AGENTS.md)를 따른다.

구성 참고: [Spring Boot SQL 지원](https://docs.spring.io/spring-boot/reference/data/sql.html),
[Gradle PIT 플러그인](https://gradle-pitest-plugin.solidsoft.info/).

## 초기 H2 구성 검증 결과 (2026-09-30, MySQL 전환 전)

- 독립 프로젝트 `build`: PASS. 테스트 8개, 실패·오류·skip 0개. JUnit XML과 JaCoCo XML 생성 확인.
- 실제 `scan`: PASS. UnsafeService의 CON-001·TX-001 탐지 확인.
- PricePolicy 대상 실제 `mutation`: MUTATION_COMPLETED. 변이 4개 KILLED, SURVIVED 0개.
- 분석기 본체 `build`: PASS (기존 테스트·빌드 결과 UP-TO-DATE).

실행 증거 manifest import와 운영 DB·다중 인스턴스·실제 브로커 검증은 수행하지 않았다.

## MySQL 검증 구성 참고

[MySQL Docker 초기화 설정](https://dev.mysql.com/doc/refman/8.4/en/docker-mysql-more-topics.html),
[공식 이미지 초기화 구현](https://github.com/docker-library/mysql/blob/master/8.4/docker-entrypoint.sh).
인증정보가 포함될 수 있는 컨테이너 전체 inspect·로그는 검증 출력에 사용하지 않는다.

## MySQL 구성 검증 결과 (2026-09-30)

- 실제 MySQL 8.4.8에서 테스트 10개 PASS (실패·오류·skip 0개).
- unsafe 동시 생성: 두 실제 조회 이후 삽입을 진행해 최종 중복 행 2개 재현.
- CON-001 테스트 적정성: 정적 MISSING에서 테스트 추가 후 PARTIAL, 명시적 검토 증거 연결 후 COVERED.
- saveAndFlush 롤백 추출 누락 수정 후 TRANSACTION_ROLLBACK 2개 생성 및 검토 증거 연결 시 PARTIAL → COVERED 확인.
- TX-001 및 중복 처리 PERSISTENCE: PARTIAL 유지. production CON-001·TX-001 finding 보존.
- assertion 검토를 생략한 비교 scan: 모든 정적 판정 유지.
- 샘플과 분석기 본체 build PASS. 본체 회귀 테스트는 저장 후 예외·복수 저장 항목 추출과 단일 저장·비트랜잭션·flush 단독 호출 제외를 확인한다.
- 이번 MySQL 검증에서 PIT와 실행 증거 기반 CI 실패 정책은 실행하지 않았다.

## 회귀·서비스 검증 결과 (2026-10-01)

- 기존 10개와 신규 HTTP/MySQL·기동·종료 5개, 전체 15개 PASS(실패·오류·skip 0).
- 동일 전체 빌드 명령으로 기록한 시작 기준선 대비 비교 PASS. 과거 변경 전 10개 테스트의 소스 해시와 성공 결과도 유지되며, 당시 명령과 새 runner 명령의 차이는 별도 검토 대상으로 보존했다.
- 별도 JVM의 정상·중복·동시성·롤백 및 실패 후 재시도, 독립 MySQL 조회 PASS. JVM·전용 컨테이너 종료 확인.
- 모든 미해결 정적 테스트 근거·production 위험을 모은 최종 판정 NEEDS_REVIEW(종료 코드 3). 기동·종료 테스트는 연결됐지만 assertion 의미는 자동 확정되지 않아 PARTIAL을 유지한다. 의도된 unsafe 위험도 그대로 유지한다.

## Lifecycle 공백 보완 후 검증 결과 (2026-10-01)

- 기동 전 반복 stop·이후 start, 실제 점유 포트 bind 실패·반복 실패·stop·포트 해제 후 재시도·종료 후 포트 반환의 신규 테스트 2개 PASS. 테스트가 소유한 HTTP client도 종료한다.
- public 생성자의 loopback/임의 포트 동작은 유지하고 package-private 주소 생성자만 추가했다. 업무·DB 동작 코드는 변경하지 않았다.
- 전체 17개 테스트·패키징·별도 JVM HTTP/MySQL 검증 PASS. JVM·전용 MySQL 컨테이너 종료 확인.
- 보존한 변경 전 15개 테스트 모두 재실행 PASS. 검증 스크립트의 기대 테스트 수를 15에서 17로 높인 해시 기반 변경 검토만 필요했으며 다른 기존 테스트 변경은 없다. 기존 production 위험 finding도 동일하다.
- 검토 정의·해시·변경된 Obligation ID 갱신 후 TEST_GAP 0, STATIC_LIMITATION 21, INTENTIONAL_DEFECT 5. 원래 분석기의 bind 예외 경로 COVERED와 검토자의 나머지 판단을 구분한다.
- 완료 판정 COMPLETED, verificationStatus/evidenceReviewStatus PASSED, targetRiskStatus UNRESOLVED. 승인된 의도적 결함의 재현 성공이며 위험한 코드가 안전해졌다는 의미는 아니다.
- `build/lifecycle-pre-change-comparison.json`: 보존한 변경 전 15개 테스트와 비교한 결과.
- `build/lifecycle-cli-completion.json`: 변경 전 기준선·변경 근거·최종 실행 snapshot을 연결한 실제 complete CLI 결과.
# 새 단계 실행기와 실제 서비스 연결

저장소 루트에서 `python3 integration-fixtures/spring-risk-sample/scripts/verify_workflow.py`를 실행한다.
별도 MySQL에서 baseline → test-writing → implementation → verification → local-service → review → complete를 실제 CLI로 수행한다.
기존 가격 정책/테스트 파일의 동일 내용 재작성으로 작성 경로를 확인하며 업무 동작을 변경하지 않는다.
기존 가격 assertion의 실제 실패·기동 실패·SIGTERM 중단·실행 중 임시 소스 변경 후 차단과 재시도, 실제 JVM·컨테이너 종료를 확인한다.
검증 중 잠시 소스 변경을 유도하므로 fixture의 다른 실행/편집과 동시에 사용하지 않는다.
전체 사용법은 [단계 실행 제어](../../docs/requirements-analysis/code-change-workflow.md)를 따른다.
요약은 `build/workflow-session-summary.json`, 세션 근거는 `build/workflow-session-*/`에 기록한다.
기존 `verify_mysql.py` 검증도 최종 필수 검증으로 유지한다.
