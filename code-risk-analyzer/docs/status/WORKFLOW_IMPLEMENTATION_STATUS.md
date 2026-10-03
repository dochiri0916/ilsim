# AI Agent 개발·검증 절차 구현 현황

검토일: 2026-10-03

## 결론

현재 `code-risk-analyzer`는 **정적 분석, 테스트 적정성, JEV Capability, 로컬 Agent 생성과 제한된 구현 수정·검증**을 제공한다. 구조화된 요구사항을 받은 CODE_CHANGE 세션에서 테스트·구현 생성, 실패 분류, 테스트 보존, JaCoCo/PIT 완료 게이트를 연결했다. 자연어 요구사항의 완전한 자동 정규화, 개발 모델의 평가·승격과 PR 자동 제출은 구현되어 있지 않다.

**1단계의 요구사항 해석은 별도 skill 등에서 수행할 외부 절차로, 정적 분석 도구의 구현·검증 평가에서 제외한다.** 구조화 입력 검증·mode 제한, 명시적 코드 연결과 선택적 JEV 검토는 실행 경로를 제공한다. 승인된 파일 교체, Gradle 검증, 로컬 Codex·Claude 생성과 제한된 production 수정 반복을 지원한다. 문서 해석의 완전성이나 모든 업무의 자동 완수를 보장하지 않는다. 단계별 작업량이 다르므로 이를 단순 구현률 백분율로 환산하지 않는다.

정적 분석·검증 도구에서 다음 두 가지는 현재 보장할 수 없다.

- Test Adequacy 및 모든 위험 영역에 대한 JEV 의미 검증
- Critical/High Risk와 Test Gap이 모두 해소된 경우에만 PR 제출을 허용하는 품질 게이트

## 검토 기준과 범위

[REVIEW.md](REVIEW.md)를 출발점으로 현재 `src/main`, 직접 관련 `src/test`, 빌드 설정을 대조했다. 최초 현황 문서 작성 이후 REVIEW.md의 개선 우선순위 구현 결과를 반영했다.

1단계의 기능 요구사항·제약사항·예외 조건·비즈니스 규칙 식별과 핵심 Test Obligation 도출은 외부 skill 등의 책임으로 둔다. 요구사항 해석 결과의 완전성을 정적 분석기가 증명하도록 요구하지 않으며, 외부 skill의 구현 여부는 이번 문서에서 평가하지 않는다. 요구사항 CLI는 구조화 입력·작업 계획 검증, TX/CON/IDEM 근거 연결과 문서/코드 JEV 후보 검토를 제공한다. 도메인 경계 검증과 일반 업무 계약 대조는 추가 구현 후보다.

판정 기준:

| 판정 | 의미 |
|---|---|
| 구현 | 해당 세부 기능의 실행 코드가 존재함. 업무적 정확성이나 운영 안전성의 증명을 의미하지 않음 |
| 부분 구현 | 일부 기능은 있지만 요청한 범위 또는 완료 조건에 미달함 |
| 미구현 | 검토한 저장소에서 해당 실행 기능을 확인하지 못함 |
| 확인 불가 | 소스와 문서만으로 실행 결과 또는 과거 작업 순서를 확정할 수 없음 |
| 평가 제외 | 외부 절차의 책임으로 지정되어 이 도구의 구현 평가 대상이 아님 |

`REVIEW.md`에 연결된 `README.md`는 현재 작업 디렉터리에 없다. 설명을 확인할 수 없는 부분은 실행 코드를 기준으로 판정했다. 현재 디렉터리는 Git 저장소로 인식되지 않아 커밋 이력으로 테스트 선행 여부나 PR 제출 이력을 검증할 수 없다.

## 단계별 요약

### Capability Tier 기반 모델 라우팅 추가 (2026-10-03)

[Capability Tier 기반 모델 라우팅](../requirements-analysis/model-routing.md)을 추가했다. 단계별 Tier를 명시적으로 받아 Registry·Policy의 ACTIVE profile과 지원 reasoning 조합을 선택하고 Codex·Claude CLI에 전달한다. 선택 결과와 Registry/Policy 해시를 기존 생성 artifact에 기록하며 파일·설정·Router 코드 변경과 생성 중 정책 변경은 과거 검증·완료 재사용 및 적용을 차단한다. 직접 provider 선택은 유지한다. 이후 같은 날 JEV 판단과 제한된 생성 오류 escalation을 아래와 같이 추가했다. 평가 기반 비용 최적화·Shadow/Canary는 구현하지 않았다.

- 초기 명시적 Tier 구현에서 관련 Python 63개와 전체 Python 98개, Java 25 전체 Gradle build PASS. 기존 Java 테스트 233개 결과는 UP-TO-DATE로 재사용했다.
- 실제 Codex `gpt-6.1-sol / medium`과 Claude `sonnet / medium`의 구조화 응답·요청 모델/effort 기록을 확인했다. Claude 호출에서는 잔존 DeepSeek endpoint·token·모델 override 환경변수를 제외했다. 전체 개발 품질 및 실제 모델 snapshot 확인을 의미하지 않는다.

### JEV Capability·제한된 생성 오류 escalation 추가 (2026-10-03)

JEV에 단계·승인된 context·요구사항을 전달해 Tier와 위험도를 한 번의 Choice 요청으로 판단한다. 모델 후보와 정책은 전달하지 않고 Router가 실제 모델을 선택한다. UNKNOWN·낮은 confidence·JEV API 실패는 CLI 실행을 차단하며, 위험도에 따라 코드에서 Tier·confidence 최소 기준을 적용한다. 키는 실행 환경에만 두고 artifact·prompt·CLI 자식 프로세스에 전달하지 않는다.

정상 완료 후 응답 계약 오류만 Policy의 명시적 fallback으로 최대 세 번의 총 CLI 호출 안에서 재시도한다. 인증·잔액·환경·timeout·범위 밖 변경·해시 오류에는 재시도하지 않는다. 각 시도와 JEV 판단은 기존 해시 보호 artifact에 기록하고 호출 중 입력 변경은 후속 호출·적용을 차단한다. 이후 별도의 `repair` 단계에서 실제 JUnit·빌드·정적 검증 실패를 분류하고 제한된 구현 수정·재검증을 연결했다.

- 실제 JEV `jev-1.13.0` → Router → Codex `gpt-6.1-sol / medium` 구조화 응답과 LOW_CONFIDENCE 차단을 합성 설명으로 확인했다. `build/jev-capability-smoke-summary.json`에 근거를 기록했다.
- 관련 Python 86개, 전체 Python 121개와 Java 25 전체 Gradle build PASS. 기존 Java 테스트 233개 결과는 UP-TO-DATE로 재사용했다.
- 코드 포함 시험 호출은 자동 승인 검토에서 외부 전송의 명시적 승인 부족으로 거절돼 실제 프로젝트 코드 전송·업무 판단 정확성은 검증하지 않았다.

이후 사용자 승인으로 실제 검증 실행기·회귀 비교 코드와 MySQL fixture의 코드·계약을 각각 JEV API에 전달했다. 두 응답 모두 `jev-1.13.0`의 계약 검증은 PASS였으나 confidence 0.35/0.65가 고위험 기준 0.9보다 낮아 NEEDS_REVIEW로 종료됐다. 후속 모델 호출·원본 변경은 없었다. `build/jev-approved-api-test/summary.json`에 근거를 기록했다. 실제 작업 수행 품질은 미검증이며, 질문·context·confidence 보정이 다음 수정 루프 연결에 앞선 확인 항목이다.

### 로컬 Agent 생성 연결 추가 (2026-10-02)

[로컬 Agent 생성 연결](../requirements-analysis/agent-generation.md)은 독립적인 비영속 Codex·Claude 세션으로 테스트·구현 변경을 생성한 뒤 기존 scope·해시 검증과 작성 실행기를 사용한다. 요구사항 시나리오별 테스트 연결, JUnit 표시 이름을 포함한 실제 최종 테스트 PASS, 수락한 테스트 내용·artifact 해시를 보호한다. CLI 실패·비정상 응답·timeout·취소·원본 변경은 적용을 차단한다. 자동 수정 반복과 JaCoCo/PIT 통합 최종 게이트는 후속 작업이다.

- 실제 Codex로 격리 Java 프로젝트의 테스트 생성 → assertion 실패로 경계 버그 재현 → 구현 생성 → JUnit·Gradle·정적 검증 PASS를 확인했다. 별도 서비스 근거가 없으면 review/완료가 차단되는 것도 확인했다.
- Claude의 호출 계약은 대체 CLI 테스트와 위 실제 구조화 응답으로 검증했다. 초기 `402 Insufficient Balance`는 잔존 DeepSeek 환경변수를 사용한 호출에서 발생했으며 변경을 적용하지 않았다. Claude의 전체 버그 수정 흐름은 검증하지 않았다.
- provider 원시 응답·오류 로그를 실행 artifact에 저장하지 않는다. 임시 디렉터리 분리는 별도 컨테이너 sandbox와 같지 않다.

기준선 → 테스트 작성 → 구현 → 회귀/정적 검증 → 근거/서비스 검토 → 완료를 연결하는 Python [단계 실행기](../../scripts/code_change_workflow.py)를 추가했다. [사용법과 복구 절차](../requirements-analysis/code-change-workflow.md)에 따라 승인된 파일 작성과 기존 검증 실행기를 호출하며, 순서 위반과 오래된 근거를 거부한다. 독립 CLI·외부 편집기를 통제하는 sandbox나 assertion 의미 자동 증명은 아니다. 일반 프로젝트 완료 경로와 회귀·skip·위험·근거 변경 차단은 Python 테스트로 검증한다.

MySQL fixture에는 `fixtureAdapter=MYSQL`과 `local-service` 단계를 연결했다. 최종 JAR의 별도 JVM·HTTP·독립 DB 상태·종료를 직접 검증하고 새 검증/서비스 실행 때 과거 수락을 제거한다. [전체 CLI 검증](../../integration-fixtures/spring-risk-sample/scripts/verify_workflow.py)은 동일 내용 재작성으로 단계 연결을 확인하고 기동 실패·SIGTERM·소스 변경·재시도를 검증한다. fixture 외 프로젝트의 자동 서비스 adapter는 추가 구현 대상이다.

### 작업 모드와 적용 범위

작업 시작 시 사용자 요청의 목적에 따라 모드를 기록한다. 정의서에 코드 이름이 등장하거나 저장소에 코드가 있다는 이유만으로 코드 탐색·검증을 시작하지 않는다.

| 모드 | 허용 작업 | 실행하지 않는 작업 |
|---|---|---|
| `SPEC_ONLY` | 제공된 정의서·요구사항 분석, 도메인·불변 규칙·실패 시나리오·질문 정리, 산출물 형식 검증, 문서 근거의 JEV 검토 | production·테스트 소스 탐색, 정적 코드 scan, 테스트·빌드·서비스 실행, 코드 변경, 코드 근거의 JEV 검토 |
| `SPEC_CODE_REVIEW` | 명시적으로 요청한 정의서와 코드 대조, 관련 코드·테스트 읽기, 정적 scan | 코드 변경 및 별도 요청 없는 테스트·빌드·서비스 실행 |
| `CODE_CHANGE` | 승인된 구현·수정·테스트·분석기 개발 및 관련 검증 | 요청 범위 밖 변경과 테스트 작성 단계의 production 코드 변경 |

정의서만 분석하는 요청은 `SPEC_ONLY`로 수행한다. 코드를 보지 않았으면 구현되지 않았다고 단정하지 않으며 구현 상태 자체를 평가하지 않는다. 작업 목적이 불명확하면 문서 분석까지만 진행하고 코드 대조·변경 범위를 확인한다. JEV 판단이나 schema 통과로 모드를 변경하거나 도구 실행 권한을 확대하지 않는다.

아래 기준선 테스트·정적 코드 검증·구현·로컬 서비스 실행 절차는 `CODE_CHANGE`에 적용하며, `SPEC_CODE_REVIEW`에는 요청된 대조 범위의 정적 분석만 적용한다. `SPEC_ONLY`에 코드 검증 단계를 자동 적용하지 않는다. `requirements` CLI는 mode·phase·명시적 파일/작업 범위를 전체 계획에 대해 먼저 검사한다. 독립적인 scan/mutation 명령이나 외부 Agent 도구까지 통제하는 sandbox는 아니다.

| 단계 | 판정 | 현재 지원하는 것 | 완료를 위해 남은 핵심 항목 |
|---|---|---|---|
| 1. 요구사항 및 정의서 분석 | 해석은 평가 제외, 실행 연계는 부분 구현 | 입력 schema·중복 키 검증, mode/phase 제한, 매핑 메서드의 TX/CON/IDEM 연결, 문서/코드 JEV 분리 | 원문 검증·일반 업무 계약·도메인 경계 대조, 실제 JEV 의미 정확성은 별도 검토 |
| 2. AI Agent 작업 프롬프트 작성 | 부분 구현 | `prompt` 명령: CODE_CHANGE 요구사항 산출물에서 범위·시나리오·완료 조건·필수 검증을 담은 압축 프롬프트를 결정적으로 생성, 불완전 입력 거부 | 프로젝트 규칙 내용 검증(경로만 참조), 프롬프트 실행 연계 |
| 3. 테스트 코드 우선 작성 | 부분 구현 | TEST_WRITING phase의 승인된 테스트 파일 교체, 별도 runner의 기준선 실행·테스트 보호·변경 후 비교 | 요구사항 기반 생성·예상 원인의 실패 확인·assertion 의미 자동 증명 |
| 4. 1차 Test Adequacy 검증 | 부분 구현 | 코드 기반 Obligation 추출, 정적 적정성 분석, 네 가지 상태 보고 | 구현 전 테스트 검증의 한계, 적정성 JEV 검토·테스트 보완 |
| 5. 동작 코드 구현 | 부분 구현 | IMPLEMENTATION phase의 승인된 파일 교체와 명시적 TEST/BUILD, 별도 runner의 회귀 비교 | 최소 구현 자동 생성·assertion 의미 증명·전체 개발 반복 절차 |
| 6. 2차 Production Risk 검증 | 부분 구현 | 19개 위험 규칙, 제한된 JEV 연계 | 권한·Tenant·정합성 검증 확대, 수정·재검증 반복 |
| 7. PR 생성 및 최종 검증 | 부분 구현 | 전체/지정 파일 스캔, 보고서·실패 정책·변이 검증, 회귀·서비스·미해결 근거 완료 판정 | 다른 서비스 adapter·전 영역 의미 검토·미신고 근거 탐지·PR 제출 |

## 1. 요구사항 및 정의서 분석

**판정: 평가 제외 — 별도 skill 등에서 수행할 외부 절차다.**

기능 요구사항, 제약사항, 예외 조건, 비즈니스 규칙 식별 및 핵심 Test Obligation 도출은 외부에서 수행한다. 프롬프트 지시만으로 완료했다고 판단하지 않고, 요구사항을 구조화한 산출물과 코드·테스트 근거 및 미확인 사항을 남긴다. 문서 해석의 완전성이나 운영 안전성을 정적 분석·JEV로 증명했다고 표현하지 않는다.

현재 분석기의 코드 기반 Obligation 추출은 4단계의 테스트 적정성 분석 기능으로 평가한다. 외부에서 도출한 요구사항 기반 Obligation의 관리·검토는 해당 skill 등의 책임이며, 이번 검토에서는 그 구현 상태를 판정하지 않는다.

구조화 형식과 실행 방법은 [요구사항 분석 산출물 v1](../requirements-analysis/README.md), [JSON Schema](../requirements-analysis/requirements-analysis.schema.json), [주문·재고 정의서](../requirements-analysis/order-inventory.definition.md)와 예제를 사용한다. `SPEC_ONLY`는 코드·실행 근거와 mappings를 금지한다. 코드 대조 모드는 codeRevision과 mappings를 요구하며 코드 미제공 상태는 UNKNOWN으로 기록한다. schema와 실행기 테스트는 구조적 누락·모드 혼합 및 실행 전 차단을 확인한다. 업무적 충돌은 schema로 탐지하지 않으며 선택적 JEV 검토 후보로 남긴다.

### 필수 분석 관점과 정적 근거의 범위

| 분석 관점 | 요구사항·정의서에서 확인할 내용 | 정적 분석으로 수집·대조할 수 있는 근거 | 현재 지원과 한계 |
|---|---|---|---|
| 도메인 경계 | 업무 책임, aggregate와 데이터 소유자, 허용 의존 방향 | 패키지·타입·호출 의존성, 다른 도메인의 Repository 접근, 공유 엔티티 사용 | 도메인 경계 위반을 검증하는 전용 기능은 미구현. 패키지 구조만으로 업무 경계를 확정하지 않음 |
| 트랜잭션 경계 | 함께 성공·실패해야 하는 업무 단위와 쓰기 | transaction annotation, DB 쓰기, self-invocation, async·외부 호출 경계 | 기존 TX 규칙으로 일부 위험 후보 탐지. 전체 호출 경로의 실제 proxy·propagation·commit·rollback은 실행 확인 필요 |
| 불변 규칙 | 항상 유지할 조건, 사전·사후 조건, 금지 상태 | 조건 검사·상태 변경·DB 제약 선언과 명시된 규칙의 대조 | 일반적인 업무 불변 규칙 대조는 미구현. 코드에 보이는 조건이 요구사항의 전부라고 가정하지 않음 |
| 핵심 로직 | 중요 업무 정책, 허용 상태 전이, 계산·분기 기대 결과 | 분기·계산·저장·부수 효과와 연결된 코드·테스트 | 일부 Obligation 추출 지원. 업무 중요도와 기대 결과의 정확성은 요구사항 검토 필요 |
| 동시성 | 동시에 발생 가능한 업무와 유지할 최종 결과 | check→act, read→modify→write, 공유 상태, 잠금·원자적 연산·UNIQUE 후보 | 기존 CON 규칙으로 일부 후보 탐지. 모든 경쟁 경로·다중 인스턴스 안전성은 입증하지 않음 |
| 멱등성 | 중복의 정의, 비즈니스 키 범위·유효기간, 재처리 응답 계약 | 재시도·메시지 경계, 키·중복 확인·부수 효과·DB 제약 후보 | 기존 IDEM 규칙으로 일부 후보 탐지. 키의 원자적 저장·전달·외부 중복 방지 계약은 별도 확인 필요 |
| 정합성·부분 실패 | 데이터 간 제약, 시스템 간 정합성, 허용 지연·보상·복구 정책 | 다중 쓰기, DB와 외부 호출 결합, 제약 및 outbox·보상 구현 후보 | 일부 DB UNIQUE 보호·TX·REL 위험 탐지 지원. 일반 제약·outbox·보상 정책과 요구사항의 대조는 미구현 |

신규 기능에 코드가 없으면 정적 분석은 도메인 의도나 적절한 transaction 경계를 결정할 수 없다. 정의서에서 먼저 조건을 명시하고 구현 이후 코드 근거와 대조한다. 분석하지 못한 항목이나 근거 부족을 위험 없음으로 처리하지 않는다.

### 요구사항 분석 산출물

업무별로 다음 정보를 기록하고 2단계 프롬프트 및 3단계 테스트 설계에 전달한다.

- 요구사항 ID, 문서 버전·원문 위치와 해당 조건의 근거
- 도메인·aggregate·데이터 소유자·업무 진입점과 허용 의존 방향
- 사전·사후 조건, 상태 전이, 불변 규칙과 핵심 로직의 기대 결과
- transaction 업무 단위, 함께 처리할 DB 쓰기와 외부 부수 효과
- 동시 실행·중복·재시도·순서·부분 실패·복구 시나리오와 기대 결과
- 연결된 코드 위치·정적 finding·기존 테스트 및 필요한 Test Obligation
- 문서와 코드의 불일치, 미확인 사항, 부족한 근거와 확인할 질문

현재 동작과 요구되는 동작은 별도 기록한다. 판정은 `근거 있음`, `충돌`, `근거 부족`, `해당 없음`으로 구분하고, `해당 없음`에도 사유를 남긴다. 이 분류는 제안된 요구사항 검토 형식이며 현재 분석기의 TEST-001 상태나 자동 출력 형식으로 구현된 것은 아니다.

### 정적 분석과 JEV의 역할 및 작업 순서

`SPEC_ONLY`는 정의서 구조화 → 문서 근거 대조 → 필요한 문서 대상 JEV 검토 → 모순·부족한 정보·질문·Test Obligation 계획 도출로 끝낸다. 이 과정에서 코드 scan이나 테스트를 실행하지 않는다.

`SPEC_CODE_REVIEW`는 요구사항 구조화 → 정적 코드 근거 수집 → 요구사항과 코드 대조 → 필요한 JEV 검토 → 부족한 근거·질문·Test Obligation 계획 도출 순서로 진행한다. 실제 실행 검증과 구현은 별도 요청된 `CODE_CHANGE` 작업에서 수행한다.

정적 분석은 구현된 사실과 위험 후보를 수집하는 역할이다. 도메인 의도와 업무 불변 규칙은 정의서에서 가져온다. 현재 requirements 경로는 명시적 파일·메서드 mapping으로 기존 TX/CON/IDEM 근거를 연결한다. mapping 미해결·파싱 실패·관련 finding 부재는 근거 부족으로 남긴다. 자연어 조건 전체를 자동 증명하는 대조 규칙은 없다.

JEV는 구조화된 요구사항과 코드 근거를 받아 좁은 질문을 검토하는 보조 수단으로 사용한다. 질문 예시는 다음과 같다.

- 정의서의 중복 처리 계약과 수집된 구현 근거가 충돌하는가?
- 불변 규칙을 확인할 실패·경계 시나리오가 검증 항목에서 빠졌는가?
- 함께 rollback되어야 하는 DB 쓰기 사이에 비동기 경계가 있는가?

질문마다 요구사항 원문·판정 기준·코드 근거·미확인 경계를 연결한다. 실제 코드에서 관측한 사실, 모델의 판단, 검토자의 결론을 구분한다. 근거가 부족하면 판단을 보류하고 추가 질문이나 테스트로 연결한다. 높은 응답 확률은 안전성 증명이 아니며, 여러 모델의 동의도 독립적인 실행 증거를 대신하지 않는다. 현재 증거에 없는 transaction·locking·멱등성 보호를 추정해 판정하지 않는다.

기존 [JevSemanticJudge.java](../../src/main/java/com/example/coderiskanalyzer/production/judge/jev/JevSemanticJudge.java)는 IDEM-001 질문을 유지한다. 새 [JevRequirementsJudge.java](../../src/main/java/com/example/coderiskanalyzer/requirements/JevRequirementsJudge.java)는 문서 충돌·시나리오 누락과 정적 관측의 구현 불일치를 별도 질문으로 검토한다. 모든 확률은 REVIEW_REQUIRED 후보이며 호출 실패는 INSUFFICIENT_EVIDENCE로 남긴다. 기존 finding 승격 정책을 요구사항 분석의 완료 판정으로 사용하지 않는다.

JEV 확장 용도는 모드에 따라 제한한다. 문서 충돌·시나리오 누락과 정적 관측 기반 구현 불일치 검토는 CLI에서 선택 실행할 수 있다. 용어 모호함·질문 우선순위·테스트 assertion 충분함·동등 변이 후보 검토는 추가 확장 대상이다. 근거를 모르는 사항은 판단을 보류하며 점수만으로 실행 검증을 생략하거나 mutation 예외를 자동 등록하지 않는다. 실제 외부 API의 의미 판정 정확성은 검증하지 않았다.

예를 들어 주문 업무는 `재고는 음수가 될 수 없음`, `동일 주문 재처리는 재고를 추가 차감하지 않음`, `주문 저장과 재고 차감은 함께 성공하거나 rollback됨`처럼 조건을 명시한다. 정적 분석은 재고 변경·중복 키·transaction·async 경계·원자적 갱신 또는 잠금 후보를 대조하고, 최종 확인은 실제 MySQL의 병렬 실행·중복 처리·rollback 테스트로 연결한다.

## 2. AI Agent 작업 프롬프트 작성

**판정: 부분 구현 — `prompt <input.json> [--rules file]`이 JEV·도구 실행 없이 프롬프트를 만든다.**

입력은 `mode: CODE_CHANGE`의 요구사항 산출물이다. 출력은 요구사항별 범위·사전/사후 조건·불변 규칙·적용되는 transaction/concurrency/idempotency/consistency 기대와 시나리오, 고정 워크플로(테스트 선행 → 최소 구현 → scan·mutation)와 완료 조건이다. 시나리오를 한 줄로 압축해 토큰을 줄였다. `UNKNOWN` 관점이나, APPLICABLE인데 대응 시나리오(ROLLBACK/CONCURRENCY/DUPLICATE)가 없는 경우는 추측하지 않고 종료 코드 2로 거부한다. `--rules`는 경로만 참조하며 내용을 검증하거나 포함하지 않는다.

짧은 수정에는 `prompt --change <text> --files <a,b>` 경량 모드를 쓴다. 변경 설명을 해석 없이 인용하고 대상 파일로 범위를 제한하며, 기준선·변경 파일 scan 절차와 TX/CON/IDEM finding·고위험 변경 시 전체 요구사항 입력으로 승격하라는 안내를 붙인다. 변경 설명은 1000자, 파일은 `..` 없는 상대 경로만 허용한다.

`route --change <text> --files <a,b>`는 JEV 질문 하나로 변경이 돈·재고·상태 전이·재시도·메시지·트랜잭션·동시성 영역을 건드리는지 분류해 경량/전체 경로를 안내한다. 확률 0.3 미만일 때만 `LIGHT`이며, 키 누락·호출 실패·비정상 응답은 항상 `FULL`이다. 분류는 더 엄격한 경로로만 보낼 수 있고 검증을 생략하는 근거가 아니다. 임계값은 실제 사례로 검증하지 않았다. 이 도구의 범위는 안정성 검증·판단이며 아키텍처 검증은 포함하지 않는다. 흐름은 `.claude/skills/risk-workflow/SKILL.md`에 정리했다.

## 3. 테스트 코드 우선 작성

**판정: 부분 구현 — 테스트 생성과 전체 TDD 자동 반복은 없고, 승인된 파일 적용·실행 경로를 제공한다.**

requirements의 WRITE_TESTS는 TEST_WRITING phase 및 승인된 src/test/ 경로만 허용한다. 각 교체에 원본 SHA-256 또는 ABSENT를 요구하며 production 코드 변경을 거부한다. 명시적으로 승인한 TEST action으로 대상 Gradle 테스트를 실행한다. 테스트 자동 생성·업무적 assertion 보호·예상 실패 원인 판정은 외부 절차의 책임이다.

### 변경 전 기준선과 기존 동작 보호 규칙

다음은 Agent 작업에 적용하는 절차 규칙이며, 분석기가 이 절차를 자동 실행하거나 통과 여부를 강제한다는 의미는 아니다.

1. 테스트 코드 작성 전에 변경 대상과 호출자·호출 대상, 공유 데이터, 트랜잭션·비동기·재시도 및 부수 효과 경계를 확인한다. 유지할 기존 동작과 요구사항에 따라 변경할 동작을 구분하고 관련 테스트를 연결한다.
2. 새 테스트를 작성하기 전에 영향받는 기존 테스트 → 관련 모듈 → 저장소가 요구하는 전체 검증을 실행한다. 소스 버전 또는 변경 파일 해시, 실행 명령, 환경·DB 버전, 테스트 수와 실패·오류·skip 및 빌드 결과를 변경 전 기준선으로 기록한다. 테스트를 실제 재실행했는지, 기존 결과를 재사용했는지도 구분한다.
3. 기존 실패가 있으면 원인과 영향 범위를 먼저 확인한다. 정상 기준선이 확보되지 않은 상태를 명시하고, 기존 실패를 새 변경의 성공으로 덮거나 검증 규칙을 낮추지 않는다.
4. 중요한 기존 동작에 테스트 공백이 있으면 production 코드를 변경하지 않고 회귀 테스트를 먼저 추가한다. 반환값·예외뿐 아니라 필요한 DB 상태, 실패 후 상태, 부수 효과 횟수, 동시·중복·재시도 결과를 검증하고 현재 구현에서 통과하는지 확인한다. 기존 동작 자체가 요구사항을 위반하면 그 사실을 기록하고 버그 재현 테스트로 구분한다.
5. 기존 동작 보호 테스트를 확보한 뒤 신규·수정 동작의 테스트를 작성한다. 예상한 미구현·결함 원인으로 실패하는지 확인하고, 영향받는 기존 회귀 테스트는 계속 통과해야 한다. 컴파일 오류나 fixture 오류는 동작 실패 검증과 구분한다.
6. 테스트 작성 단계를 마친 뒤 별도 동작 코드 구현 단계에서 최소 변경한다. 기존 테스트는 계약으로 유지하며 요구사항 변경 또는 잘못된 테스트라는 명확한 근거가 있을 때만 수정한다.
7. 최종 구현 후 새 테스트와 영향받는 기존 테스트 → 관련 모듈 → 전체 검증을 다시 실행한다. 동일한 명령·환경·검증 범위의 기준선과 비교하며, 의도한 차이와 예상 밖 차이를 구분한다. 환경이 달라 직접 비교할 수 없으면 그 한계를 기록한다. 테스트 삭제, assertion 약화, skip·ignore 추가 등 검증 우회가 없는지도 변경 내역에서 확인한다.

테스트 통과는 실행한 시나리오에 대한 근거다. 영향 범위 밖 동작이나 테스트되지 않은 동시성·트랜잭션·멱등성·부분 실패까지 보장된다고 표현하지 않는다. 해당 의미가 중요하면 실제 DB·Spring proxy 등 필요한 실행 환경에서 확인한다.

### 테스트 작성 단계의 작업 규칙

- 이 단계에서는 테스트 코드만 작성·수정한다. production 코드의 작성·수정, 기존 동작 코드의 변경, 신규 동작 코드의 추가를 금지한다.
- 테스트를 컴파일하거나 통과시키기 위한 production 클래스·메서드·stub 추가도 금지한다. 테스트 전용 fixture·mock·helper는 테스트 소스 범위에서만 작성한다.
- 구현 부재로 인한 컴파일 오류나 테스트 실패는 원인을 기록한다. 이를 해결하기 위한 동작 코드 변경은 5단계인 동작 코드 구현에서 수행한다.

### 정적 분석으로 보완할 수 있는 회귀 검증

| 확인 대상 | 현재 분석기 지원 | 추가 구현 또는 별도 확인이 필요한 부분 |
|---|---|---|
| 변경 범위와 관련 테스트 | `--files-file`로 변경 파일을 seed로 선택하고, 메서드 심볼로 테스트의 직접 호출을 연결 | 전이적인 호출자·공유 데이터 영향 분석과 회귀 테스트 실행 대상 자동 선정은 미구현. 변경 파일 scan을 전체 영향 분석으로 취급하지 않음 |
| 기존 동작의 테스트 공백 | 분기·경계·예외·저장·상태·권한·롤백 및 위험 Obligation과 MISSING/PARTIAL/UNKNOWN 보고 | 요구사항상 유지해야 할 업무 계약과 assertion의 충분함은 검토 필요 |
| 변경 후 새로운 위험 | 동시성·트랜잭션·멱등성·보안·부분 실패 규칙과 baseline 비교, 선택형 신규 Test Gap 정책 | 정적 baseline은 finding 비교용이며 변경 전 실행 성공 기준선을 대신하지 않음 |
| public API·annotation·DB 선언 변경 | 소스에서 확인·비교 가능한 항목 | 메서드 시그니처, transaction·권한 annotation, UNIQUE·NOT NULL 등의 변경을 비교해 계약 영향으로 보고하는 전용 기능은 미구현. 실제 DB 제약 적용 여부는 실행 확인 필요 |
| 테스트 보호 장치 변경 | assertion 없는 연결 테스트 후보, 별도 runner의 기존 test identity 누락·skip·실패 차단과 테스트/빌드 파일 해시 변경 검토 | assertion 의미의 약화·동등성과 전이적인 영향 범위는 자동 증명하지 않음. 해시에 연결된 변경 근거 검토 필요 |

정적 분석은 실행할 회귀 테스트와 위험 검토 대상을 찾는 데 사용한다. 기존 기능이 동일하게 동작하는지의 최종 확인은 변경 전후 테스트 실행 결과와 필요한 통합 검증을 함께 사용한다.

정적 분석은 다음 순서로 작업에 포함한다.

1. 테스트 작성 전 현재 코드의 전체 scan과 변경 예정 영역의 관련 결과를 보관하고 위험·Obligation·연결된 테스트를 확인한다. 미구현 신규 동작은 요구사항에서 검증 항목을 별도로 도출한다.
2. 호출자·공유 데이터, public API, transaction·권한 annotation 및 DB 제약 선언의 변경 영향을 소스에서 검토한다. 현재 자동 지원하지 않는 항목은 수동 검토로 기록하며, 위험이나 테스트 연결을 확인하지 못한 영역을 안전하다고 판정하지 않는다.
3. 테스트 보완 후 scan을 다시 실행해 누락·부족·불확실 항목을 확인한다. 이후 production 구현을 마친 최종 소스에서도 전체 scan과 필요한 변경 범위 scan을 실행하고 이전 결과와 비교한다.
4. 새 위험·Test Gap·UNKNOWN 및 분석 실패를 검토하고 처리 근거를 남긴다. 미지원 항목은 필요한 실행 테스트에 연결한다. baseline·suppression으로 위험을 숨기거나 필수 검증을 통과한 것으로 취급하지 않는다.

### 검증 대상에 따른 테스트 작성 규칙

도메인 로직과 구성 요소 간 연결을 구분해 필요한 테스트를 작성한다. 모든 변경에 모든 종류를 추가하는 대신, 영향받는 동작과 실패 위험에 맞는 수준을 선택하고 제외한 중요한 검증의 이유를 기록한다.

| 테스트 종류 | 작성 대상 | 필수 확인 사항 |
|---|---|---|
| 도메인·단위 테스트 | 업무 정책, 계산, 유효성, 상태 전이, 경계 및 예외 | 정상·거부·경계 입력의 반환값과 최종 상태. 도메인 테스트는 가능한 한 Spring·DB 없이 실행하며 내부 호출 순서만 검증하지 않음 |
| Repository·DB 테스트 | 매핑, 쿼리, UNIQUE·NOT NULL·FK 등 데이터 제약과 저장 결과 | 대상 DB가 MySQL이면 전용 MySQL에서 검증. 다른 DB나 mock의 성공을 MySQL 의미 검증으로 대체하지 않음 |
| API·계층 테스트 | Controller가 있는 서비스의 요청 매핑, validation, 응답, 인증·인가 경계 | 실제 요구사항의 성공·실패 응답과 접근 제어. 소유권·tenant 격리가 관련되면 거부 경로도 확인. 계층 테스트의 성공을 전체 서비스 실행 검증으로 대체하지 않음 |
| 통합 테스트 | Service·Repository·Spring proxy·DB 및 필요한 외부 연동 | 실제 구성 요소 경계의 commit·rollback, 예외 이후 DB 상태, 동시 충돌·중복·재시도 및 부분 실패. mock으로 대체한 외부 경계는 미검증 범위로 기록 |
| 서비스 실행·종단 간 검증 | 빌드한 애플리케이션을 별도 로컬 프로세스로 실행한 실제 진입점 | 요청·명령·이벤트 입력부터 응답·DB 상태·부수 효과까지 확인. 절차는 7단계의 최종 로컬 서비스 실행 검증을 따름 |

테스트 이름이나 annotation만으로 종류와 적정성을 판단하지 않는다. 각 테스트를 요구사항·기존 동작·Obligation에 연결하고 assertion 내용을 확인한다. transaction 검증은 테스트 자체의 transaction이 서비스의 실제 경계를 가리지 않도록 하며, 동시성 검증은 latch/barrier 등으로 필요한 충돌 구간을 제어한다. 준비·실행·정리 과정에서도 기존 DB·다른 작업의 데이터와 프로세스를 건드리지 않는다.

테스트 작성 단계에서는 위 테스트 종류와 관계없이 production 코드 작성·수정 금지 규칙을 유지한다. 필요한 endpoint나 동작이 없으면 그 사실을 기록하고 별도 구현 단계에서 처리한다.

| 시나리오 | 현재 분석 지원 | 테스트 우선 작성 절차 |
|---|---|---|
| 정상 흐름 | 반환값·분기 관련 Obligation | 자동 생성 없음 |
| 예외 흐름 | throw/`orElseThrow`, 예상 예외 타입 검사 | 자동 생성·실패 원인 확인 없음 |
| 경계값 | 일부 비교 조건과 정수 리터럴 인자로 경계 미만/동일/초과 등의 사례 식별 | 자동 생성 없음 |
| 상태 변경 | 필드 대입·일부 변경 호출·저장 호출 식별 | 자동 생성 없음 |
| 권한 | 일부 annotation에서 허용/거부 항목 추출 | 실제 인증·인가 테스트 생성 없음 |
| Tenant | 전용 추출/검증 기능 없음 | 자동 생성 없음 |
| 트랜잭션 | 일부 다중 쓰기·예외 경로의 롤백 항목 추출 | 실제 DB 롤백 테스트 생성 없음 |
| 동시성·멱등성 | 위험 후보를 `RISK` 항목으로 연결 | 병렬 실행·중복 처리·재시도 테스트 생성 없음 |

분석기 자체의 회귀 테스트와 PIT용 `mutation-killed`/`mutation-survived` fixture는 존재한다. 그러나 이를 대상 프로젝트의 요구사항에 맞는 테스트를 먼저 생성하는 기능으로 볼 수 없다. 과거 구현이 테스트 작성보다 뒤에 이루어졌는지도 현재 근거로 확인할 수 없다.

PIT가 실행 전에 테스트 실패를 감지하는 기능은 있지만, 이는 변이 분석의 사전 조건이다. 새 기능 테스트가 구현 부재라는 예상 원인으로 실패하는지 확인하는 TDD의 실패 단계는 아니다.

근거: [TestAdequacyAnalyzerTests.java](../../src/test/java/com/example/coderiskanalyzer/testanalysis/TestAdequacyAnalyzerTests.java), [MutationRunner.java](../../src/main/java/com/example/coderiskanalyzer/mutation/MutationRunner.java)

## 4. 1차 Test Adequacy 검증

**판정: 부분 구현 — 정적 판정과 보고는 구현되어 있다.**

`scan`은 생산 코드 위험 분석과 Test Adequacy를 함께 실행한다. 테스트의 호출을 생산 메서드 심볼에 연결하고, Obligation별 관련 테스트·확인된 사례·누락 사례·판정 이유를 `TEST-001` finding으로 출력한다.

현재 코드 기반 추출 유형은 `BRANCH`, `BOUNDARY`, `EXCEPTION_PATH`, `PERSISTENCE`, `STATE_CHANGE`, `VALIDATION`, `AUTHORIZATION`, `TRANSACTION_ROLLBACK`, `RETURN_VALUE`, `RISK`, `WEAK_TEST`다. 본문이 있는 비공개가 아닌 메서드를 중심으로 분석하며 단순 accessor는 제외한다. `AUTHORIZATION`은 메서드의 `PreAuthorize`, `Secured`, `RolesAllowed`에서 추출하고, 동시성·멱등성은 탐지된 위험을 `RISK` 항목으로 연결한다. Tenant 전용 Obligation은 없다.

외부 skill에서 도출한 Obligation을 입력받아 테스트와 연결하는 기능은 현재 확인되지 않는다. 외부 결과를 이 도구에 직접 연계할지는 별도 설계 선택이며, 요구사항 분석 기능의 필수 구현 항목으로 보지 않는다. 현재 분석은 생산 코드에 기반하므로 구현 전 테스트만으로 적정성을 판정하는 데에는 한계가 있다.

| 상태 | 현재 판정의 의미 |
|---|---|
| `COVERED` | 연결된 테스트가 예상 예외 타입을 assertion하거나 해당 저장 동작을 verify하는 등 제한된 패턴을 인식함 |
| `PARTIAL` | 관련 assertion은 있지만 해당 분기·상태·권한·롤백 등의 기대 결과와 연결하지 못함 |
| `MISSING` | 연결된 테스트가 없거나 인식할 수 있는 assertion/검증이 없음 |
| `UNKNOWN` | 파싱·호출 연결이 불완전하거나 assertion 의미를 정적으로 판정하지 못함 |

경계 입력을 모두 찾았더라도 각 입력의 결과 assertion을 증명하지 못하면 `PARTIAL`로 남는다. 기본 정적 `COVERED`는 테스트 실행 성공이나 전체 업무 의미의 검증 완료를 의미하지 않는다.

선택형 `scan --execution-evidence`는 JUnit 실행 결과와 JaCoCo 집계 라인 커버리지, 실제 DB 시나리오 기록 및 assertion 검토를 별도 증거로 연결한다. 지원하는 롤백·동시성·중복 메시지 `PARTIAL` 항목만 모든 근거가 충족될 때 `COVERED`로 전환한다. DB 사용과 assertion 충분함은 검토자의 명시적 기록이며 자동 증명이 아니다. 입력 형식과 한계는 [EXECUTION_EVIDENCE.md](../guides/EXECUTION_EVIDENCE.md)를 참고한다.

미완료 항목:

- `MISSING` 항목과 assertion 의미에 대한 JEV 검토(현재는 `PARTIAL`/`UNKNOWN`의 참고 의견만 제공)
- 누락·부족 판정에서 테스트 보완으로 이어지는 자동 실행
- 1차 검증 통과 후에만 구현 단계로 진행하는 제어

`--jev` 호출 대상은 `IDEM-001`과, `PARTIAL`/`UNKNOWN` 상태의 `TEST-001`이다. `TEST-001`의 JEV 결과는 `JEV_TEST_REVIEW` 근거(적정 확률, 정적 상태, 필요 증거, 미확인 사항)로만 추가하며 상태·심각도·신뢰도는 바꾸지 않는다. `MISSING`·`COVERED`는 호출하지 않는다. `IDEM-001`은 확률 0.9 이상이면 `CONFIRMED`로 승격한다. `IDEM-002`·`IDEM-003`·`IDEM-004`·`CON-001`·`CON-002`는 `JEV_RISK_REVIEW` 근거(위험 확률, 정적 상태, 미확인 사항)만 추가하고 상태·심각도·신뢰도는 바꾸지 않는다. 그 외 위험 규칙은 호출하지 않는다.

근거: [ScanCommand.java](../../src/main/java/com/example/coderiskanalyzer/production/cli/ScanCommand.java), [TestAdequacyAnalyzer.java](../../src/main/java/com/example/coderiskanalyzer/testanalysis/TestAdequacyAnalyzer.java), [TestObligation.java](../../src/main/java/com/example/coderiskanalyzer/testanalysis/TestObligation.java), [TestAdequacyFindings.java](../../src/main/java/com/example/coderiskanalyzer/testanalysis/TestAdequacyFindings.java), [CoverageStatus.java](../../src/main/java/com/example/coderiskanalyzer/testanalysis/CoverageStatus.java)

## 5. 동작 코드 구현

**판정: 부분 구현 — 명시적 변경 계획 적용·검증 경로가 있고 자동 구현 Agent는 없다.**

requirements의 IMPLEMENT action은 IMPLEMENTATION phase와 별도로 승인된 파일·작업 scope를 요구한다. 제공한 교체 내용을 원본 해시 확인 후 적용하고 승인된 TEST/BUILD action을 실행할 수 있다. 실행기 writer 간 advisory lock, 파일별 atomic 교체, 실패 시 적용 목록과 후속 중단을 제공한다. 여러 파일의 원자적 transaction이나 외부 편집기와의 경합 방지는 보장하지 않는다. 최소 구현 자동 생성과 assertion 약화·테스트 삭제·skip 추가를 감지하여 거부하는 기능은 없다.

`mutation`은 대상 Gradle 빌드와 테스트를 실행하는 기능이지만 구현 코드를 작성하지 않으며, 일반적인 구현 후 테스트 실행 절차를 대신하지 않는다.

`REVIEW.md`의 보호 장치 판정 개선과 회귀 테스트 추가는 이 저장소에 반영된 개별 개발 결과다. 이를 대상 프로젝트를 자동 구현하는 기능의 완료로 계산하지 않는다.

## 6. 2차 Production Risk 검증

**판정: 부분 구현 — 19개 정적 위험 규칙과 제한된 의미 검토를 제공한다.**

| 영역 | 현재 범위 | 미지원 또는 제한 |
|---|---|---|
| Security | `SEC-001~005`: SQL/Command Injection, Path Traversal, SSRF, 하드코딩 비밀값 | 메서드 간 전체 데이터 흐름 및 미지원 제어 흐름은 검증하지 못함. SEC-003/004의 블록·분기에서는 호출별 검증 선행·값 일치를 확인 |
| Authorization | 일부 권한 Test Obligation 추출 | 소유권·리소스 접근 제어 전용 생산 위험 규칙 없음 |
| Transaction | `TX-001~005`: self-invocation, async 경계, 외부 호출, 다중 쓰기, 예외 삼키기 | 실제 Spring proxy·propagation·커밋/롤백 실행 결과 미검증 |
| Concurrency | `CON-001~004`: check-then-act, lost update, singleton 상태, async 컨텍스트 | 실제 경쟁 실행·다중 인스턴스 안전성 미검증 |
| Data Integrity | check-then-act에서 연결된 엔티티의 UNIQUE/PK 선언 일부 확인 | 운영 DB 제약 적용, FK/CHECK/NULL 정책, 충돌 처리 등을 포괄 검증하지 않음 |
| Idempotency | `IDEM-001~004`: 메시지 부수 효과, 멱등키, 재시도, 비즈니스 키 | 키의 원자적 저장·전달·실제 중복 방지 계약 미검증 |
| Tenant Isolation | 전용 생산 위험 규칙 없음 | 교차 Tenant 조회·변경 및 접근 권한 미검증 |
| Partial Failure | `REL-001`: DB와 외부 부수 효과 불일치 후보 | 실제 실패 주입·보상·outbox 실행 결과 미검증 |

`REVIEW.md`의 1순위 개선은 실제 코드와 테스트에 반영되어 있다. 조회/변경 저장소와 엔티티를 연결해 관련 제약만 확인하고, JVM 잠금만으로 DB 경쟁 경고를 숨기지 않는 개선이다. 2순위 개선도 반영되어 SEC-003/004에서 호출에 도달하는 모든 지원 경로의 검증 선행 여부와 값 일치를 확인한다. 미지원 제어 흐름은 보수적으로 경고를 유지한다. 3순위 타입 기반 부수 효과·클래스패스와 4순위 분석 완전성·선택형 CI 정책도 반영되었다. 5순위는 JUnit·JaCoCo 및 명시적 DB/assertion 검토를 연결하는 선택형 import로 반영되었다. 전체 위험 검증 완료와 구분해야 한다.

JEV 동작의 실제 범위:

- `--jev true`와 `TYPESAFE_API_KEY`가 필요하다.
- `IDEM-001`에만 고정된 중복 부수 효과 질문을 적용한다. 불확실한 모든 영역을 선별하는 공통 검토기는 아니다.
- 전체 소스 대신 후보 요약과 구조화된 증거를 전달한다.
- 검토 결과의 확률이 0.9 이상이면 해당 후보를 `CONFIRMED`/`HIGH` 신뢰도로 변경한다.
- JEV 실패 시 경고를 남기고 정적 결과를 유지한다. 의미 검증 완료를 필수 조건으로 삼지 않는다.

발견한 문제를 Agent가 수정하고 테스트·분석을 재실행하는 반복 절차도 없다. 현재는 사용자가 코드를 수정한 뒤 명령을 다시 실행해야 한다.

근거: [RuleRegistry.java](../../src/main/java/com/example/coderiskanalyzer/production/rule/RuleRegistry.java), [DatabaseUniquenessProtection.java](../../src/main/java/com/example/coderiskanalyzer/production/rule/concurrency/DatabaseUniquenessProtection.java), [CheckThenActRaceRuleTests.java](../../src/test/java/com/example/coderiskanalyzer/rule/concurrency/CheckThenActRaceRuleTests.java), [JevSemanticJudge.java](../../src/main/java/com/example/coderiskanalyzer/production/judge/jev/JevSemanticJudge.java)

## 7. PR 생성 및 최종 검증

**판정: 부분 구현 — 명시적으로 선택하는 회귀·서비스 증거·완료 게이트가 있으며 PR 제출은 없다.**

| 요청 항목 | 판정 | 실제 상태 |
|---|---|---|
| 전체 변경사항 정적 분석 | 부분 구현 | 전체 스캔 또는 `--files-file` 지정 가능. Git diff 수집과 최종 검증 자동 실행은 없음 |
| Test Adequacy 재검증 | 부분 구현 | `scan` 재실행 가능. 이전 단계 결과와 연결하는 절차는 없음 |
| Production Risk 재검증 | 부분 구현 | 동일하게 `scan` 재실행 가능 |
| Mutation Test 등 동적 검증 | 부분 구현 | 별도 `mutation` 명령의 Gradle/PIT 실행·XML 파싱·결과 보고 구현. 최종 절차의 실행 조건은 없음 |
| 최종 로컬 서비스 실행 검증 | 부분 구현 | fixture는 별도 JVM·loopback HTTP·독립 MySQL 조회·종료 검증을 구현. 다른 대상 프로젝트에는 실행 adapter가 필요 |
| JEV 최종 의미 검토 | 부분 구현 | `IDEM-001`만 지원. 전체 변경사항과 Test Adequacy 의미 검토는 없음 |
| Critical/High Risk 및 Test Gap 잔존 차단 | 부분 구현 | CONFIRMED 위험 게이트와 선택형 신규 MISSING/PARTIAL Gap 차단. 모든 잔여 위험/Gap의 차단은 아님 |
| 검증 완료 후 PR 제출 | 미구현 | PR 생성·제출 및 검증 상태와의 연계 없음 |

### 최종 로컬 서비스 실행 검증 규칙

관련 테스트·전체 빌드·최종 정적 분석을 마친 후, 마지막 구현 변경과 동일한 소스에서 빌드한 애플리케이션을 로컬 환경에서 실제로 실행한다. `@SpringBootTest`의 context 로딩이나 단순 기동 로그만으로 이 단계를 완료했다고 판단하지 않는다.

1. 프로젝트 문서의 실행 방법과 로컬 profile을 확인한다. 필요한 MySQL·캐시·브로커 등은 작업 전용 인스턴스나 격리된 테스트 자원을 준비하고, 실행 명령·소스 버전·환경·검증 시나리오를 기록한다. 인증정보는 코드·fixture·검증 보고서에 기록하지 않는다.
2. 패키징한 애플리케이션 또는 프로젝트가 지정한 실행 방식으로 별도 프로세스를 시작한다. 기동 실패·비정상 종료·준비 시간 초과를 실패로 처리한다. 준비 상태는 실제 제공하는 health endpoint, 연결 상태 또는 해당 프로젝트의 준비 신호로 확인하며 임의의 sleep만으로 성공을 판단하지 않는다.
3. 영향받는 기존 기능과 신규·수정 기능을 실제 진입점으로 실행한다. 웹 서비스는 HTTP 요청으로, CLI·배치·worker는 명령·작업·이벤트 입력으로 검증한다. 관련 정상·실패 경로와 필요 시 중복·재시도·병렬 시나리오를 포함하고 응답·종료 코드뿐 아니라 DB 최종 상태와 부수 효과도 확인한다.
4. 기동 상태, 대표 기존 기능의 회귀, 신규·수정 동작의 결과를 기대값과 비교한다. 확인한 요청·명령, 응답·상태, 로그의 필요한 부분과 PASS/FAIL을 기록한다. 외부 연동을 대체했다면 실제 연동까지 검증했다고 표현하지 않는다.
5. 이 작업에서 시작한 애플리케이션·컨테이너와 생성한 테스트 자원만 정리하고 정리 여부를 확인한다. 실패 시에도 정리한다. 검증 후 구현이 변경되면 영향을 받는 테스트·빌드·분석·로컬 실행 검증을 다시 수행한다.

환경이나 실행 가능한 진입점이 없어 수행하지 못하면 구체적인 원인과 미검증 범위를 남기고 최종 서비스 검증을 완료했다고 보고하지 않는다. 운영 배포나 운영 DB 변경을 이 단계에 포함하지 않는다.

현재 [spring-risk-sample](../../integration-fixtures/spring-risk-sample/README.md)은 `web-application-type=none`을 유지하고 opt-in JDK HTTP 진입점을 제공한다. MySQL 검증 스크립트는 도메인 테스트·Spring/DB·HTTP 통합 테스트와 별도 JVM의 실제 HTTP 응답·독립 DB 조회·실패 후 상태·종료를 확인한다.

### 실행 기준선·테스트 보호·완료 판정 연결

[regression_workflow.py](../../scripts/regression_workflow.py)의 `baseline → compare → complete`를 명시적으로 선택한다. 요구사항 schema와 CODE_CHANGE·승인 action을 검사한 뒤 대상 `cleanTest build --rerun-tasks`를 실행한다. 기존 기능 매핑·개별 테스트 결과·소스 및 빌드 입력 해시·환경·명령·JUnit/JAR 해시를 기록한다. 기존 결과 재사용과 실행 중 소스 변경을 성공으로 인정하지 않는다.

기존 테스트 누락·skip·실패는 FAILED다. 테스트·빌드 입력 변경에는 이전/이후 해시에 연결된 명세 변경 또는 잘못된 테스트 수정 근거가 필요하다. 정적 분석으로 assertion 동등성을 증명하는 대신 변경을 보수적으로 검토 대상으로 남긴다. 신규 기능에는 도메인 테스트와 필요한 MySQL 통합 테스트를 연결한다.

완료 시 재실행한 기준선 비교·최종 소스/JAR에 대한 서비스 증거·프로세스 종료·미해결 근거를 모아 COMPLETED/FAILED/NEEDS_REVIEW를 보고한다. fixture는 정적 HIGH Gap을 자동으로 미해결 근거에 넣는다. 일반 대상은 최종 scan·UNKNOWN·수동 검토 기록을 명시적으로 공급해야 한다. 영향 범위·DB 버전·테스트 수준·검토 기록은 호출자 근거이며 자동 증명이 아니다. SPEC_ONLY와 SPEC_CODE_REVIEW에는 이 실행 절차를 적용하지 않는다.

형식·명령·지원 제한: [회귀 검증 실행 절차](../requirements-analysis/regression-workflow.md). 기존 독립 scan·requirements 변경 명령에 자동 강제되는 통합 sandbox는 아니며, 다중 모듈 및 다른 서비스 실행 adapter는 아직 필요하다.

[미해결 근거 검토](../requirements-analysis/evidence-review.md)는 실제 테스트 부족·정적 의미 확인 한계·의도된 fixture 결함을 명시적으로 분류한다. 요구사항 원문·선택한 테스트 메서드의 assertion·PASS 실행·소스/JUnit/scan 해시를 대조하고 원본 staticStatus와 검토자 판단을 별도로 보존한다. 일반 프로젝트의 production 위험은 검토만으로 해소하지 않는다. 별도 승인된 fixture 정책에 명시된 결함만 재현 성공으로 인정한다.

완료 보고서의 verificationStatus, evidenceReviewStatus, targetRiskStatus를 분리했다. fixture의 현재 분류는 TEST_GAP 0, STATIC_LIMITATION 21, INTENTIONAL_DEFECT 5이며, 실행 PASSED와 위험 UNRESOLVED를 함께 보고한다. 기동 전 stop·bind 실패 테스트를 추가했고, 실제 assertion을 검토한 나머지 상태/분기 근거의 정적 PARTIAL은 보존한다. 검토 후 재실행으로 근거가 오래되는 것을 막기 위해 reviewed complete는 --verified-snapshot의 현재 소스·환경·JUnit·JAR 일치를 확인한다.

현재 품질 게이트의 범위와 남은 공백은 다음과 같다.

1. `scan --fail-on`은 기본적으로 **`CONFIRMED` finding만** 실패 대상으로 삼는다. 선택형 `fail.include-review`로 `REVIEW`도 차단할 수 있다. `fail.on-jev-failure`는 JEV 실패를 차단한다.
2. `TEST-001`은 모든 상태를 `REVIEW`로 출력한다. 높은 심각도의 `MISSING`/`PARTIAL` Test Gap은 기존 `--fail-on`만으로는 차단되지 않지만 `fail.new-test-gaps`로 신규 미제외 항목을 차단할 수 있다.
3. 분석 완전성 지표와 `fail.max-unknown-ratio`가 추가되었다. 이 게이트는 테스트 적정성 UNKNOWN 비율이며 모든 분석 제한을 차단하지 않는다.
4. 기본적으로 PIT 생존 변이는 성공 종료한다. 선택형 `fail.max-survived-mutations`는 사유 있는 검토 예외를 제외한 SURVIVED 개수로 차단한다. NO_COVERAGE·RUN_ERROR·MEMORY_ERROR·UNKNOWN도 같은 개수에 포함하며 예외는 SURVIVED에만 적용한다. TIMED_OUT·NON_VIABLE은 집계하지 않는다.
5. 변이와 Obligation 연결은 일부 mutator 및 유일한 후보에 한정한다. 모호한 결과는 `UNMAPPED`로 남고 적정성 상태를 자동으로 승격하지 않는다.
6. 위험·신규 Gap은 baseline·suppression 적용 후 JEV/실패 정책을 평가하고 UNKNOWN 비율은 원래 분석 결과를 사용한다. 따라서 보고된 잔여 항목만으로 제외된 위험까지 해소되었다고 볼 수 없다.

JSON/SARIF, baseline, suppression과 종료 코드 정책은 CI에서 활용할 기반이다. 4순위 설정·지표·변이 검토 예외는 [CI_POLICY.md](../guides/CI_POLICY.md)에 정리했다. 저장소에는 요청한 7단계를 연결해 PR을 제출하는 CI/Agent 정의가 확인되지 않았다.

근거: [MutationCommand.java](../../src/main/java/com/example/coderiskanalyzer/mutation/MutationCommand.java), [MutationObligationMatcher.java](../../src/main/java/com/example/coderiskanalyzer/mutation/MutationObligationMatcher.java), [MutationRunnerTests.java](../../src/test/java/com/example/coderiskanalyzer/mutation/MutationRunnerTests.java), [ScanCommandTests.java](../../src/test/java/com/example/coderiskanalyzer/cli/ScanCommandTests.java)

## 완료에 필요한 우선순위

1단계의 문서 해석과 핵심 Obligation 도출 자체는 외부 skill 등의 책임으로 유지한다. 구조화 입력·mode 제한·TX/CON/IDEM 연결과 문서/코드 JEV 경로는 구현했다. 자연어 계약의 정밀한 대조 및 전체 Agent 반복은 아래 실행 연계·의미 검토 작업에서 확장한다.

| 순서 | 작업 | 완료 기준 |
|---|---|---|
| 1 (완료) | 단계별 통과 정책 | 위험, Test Gap, UNKNOWN, 분석 실패, JEV 실패, 생존 변이의 처리 기준을 명시하고 선택적으로 차단 |
| 2 (적정성 완료, 위험 규칙 미확대) | 적정성과 위험의 JEV 검토 확대 | 불확실 항목을 선별해 질문·필요 증거를 제공하고 판정 근거와 미확인 사항 보존 |
| 3 | 프롬프트·테스트·구현·수정 실행 연계 | 프로젝트 규칙 우선, 예상 실패 확인, assertion 보호, 관련/전체 테스트 및 재분석 실행 |
| 4 | 실제 동작 증거와 누락 위험 영역 보완 | 권한/Tenant 전용 검증, 실제 DB 롤백·경쟁 실행·중복/재시도·부분 실패 테스트 연결 |
| 5 | 최종 검증과 PR 연계 | 최종 변경 상태에 대해 필수 검증 완료와 미해결 항목 처리를 확인한 뒤 PR 제출 |

## 검증 기록과 불확실성

- 이번 검토: `REVIEW.md`, 실행 코드, 관련 테스트 소스 대조 완료. 새 문서의 로컬 링크 대상 존재 여부 확인.
- 기존 기록: `REVIEW.md`에는 관련 회귀 테스트 16개와 전체 테스트 90개 및 `./gradlew build --no-daemon` PASS가 기록되어 있다. 이번 작업에서 테스트·빌드를 재실행한 결과는 아니다.
- 2순위 구현 후 검증: Java 25에서 SEC-003/004 관련 테스트 11개와 `./gradlew build --no-daemon` PASS (전체 테스트 95개, 실패·오류·skip 0개). 구현 범위와 한계는 [REVIEW.md](REVIEW.md)의 2순위 항목에 반영했다.
- 4순위 구현 후 검증: Java 25에서 ScanCommand·AnalyzerConfigLoader·MutationCommand 관련 테스트 29개 PASS. 최종 `./gradlew build --no-daemon` PASS (전체 테스트 110개, 실패·오류·skip 0개, 패키징 포함).
- PIT, 실제 DB, 병렬 실행, 외부 JEV API는 실행하지 않았다.
- 확인한 MutationRunner/MutationCommand 테스트는 가짜 프로세스 실행기 또는 대체 runner를 사용한다. 해당 단위 테스트만으로 실제 Gradle/PIT 연동 성공을 확정하지 않는다.
- 이 문서는 기능의 구현 범위를 평가한다. 분석 대상 프로젝트에 Critical/High Risk 또는 Test Gap이 없음을 증명하는 결과 보고서는 아니다.

- 5순위 구현 후 검증: Java 25에서 실행 증거 연결·Test Adequacy·ScanCommand·SARIF 관련 테스트 34개 PASS. 최종 `./gradlew build --no-daemon` PASS (전체 테스트 120개, 실패·오류·skip 0개, 패키징 포함). 실제 DB 테스트는 실행하지 않았으며 XML fixture 기반으로 import와 판정을 검증했다.

- 요구사항 실행 경로 구현 전 기준선: `./gradlew build --no-daemon` PASS, 기존 158개 테스트 결과 재사용(UP-TO-DATE). 기존 테스트를 약화하거나 제외하지 않았다.
- 이번 요구사항 경로 구현: 새 테스트 46개, 최종 전체 204개 PASS(실패·오류·skip 0). 관련 테스트 후 전체 build를 실행했고, 승인된 BUILD scope를 지정한 실제 CODE_CHANGE CLI에서도 `build --no-daemon --rerun-tasks` PASS를 확인했다.
- 최종 JAR의 SPEC_ONLY 문서 분석, scan/test/build/code JEV 요청의 실행 전 거부, 중복 JSON 키 거부, fixture 메서드의 CON-001 연결을 실제 CLI로 확인했다. 임시 프로젝트에서 승인된 테스트 교체와 미승인 후속 build의 전체 계획 차단도 검증했다.
- writer 동시성은 CyclicBarrier로 동시 진입해 동일 ABSENT 계획이 두 번 적용되지 않음을 검증했다. 변경 후 오래된 정적 근거의 JEV 사용 및 후속 도구 실패 시 적용 파일 기록 손실은 실패 재현 후 수정했다.
- 새 JEV 문서/코드 질문과 SDK 직렬화·확률 처리는 대체 transport로 검증했다. 이번 작업에서 외부 JEV API, 실제 MySQL 테스트와 PIT는 실행하지 않았다. DB/transaction 동작을 검증했다는 의미는 아니다.

- 회귀·서비스 검증 연계(2026-10-01): 변경 전 본체 204개와 기존 MySQL fixture 10개 PASS를 확인한 뒤 테스트 작성·구현을 분리했다. 실행 환경 전달 결함은 재현 테스트 실패 후 수정했다. 최종 본체 204개, runner 13개, MySQL fixture 15개 PASS(실패·오류·skip 0), 실제 runner CLI의 baseline/compare 전체 빌드 재실행과 비교 PASS.
- 별도 패키징 JVM에서 loopback HTTP 준비·기존 할인 정책·정상·중복·동시 요청·실패 후 rollback·재시도 및 독립 MySQL 조회 PASS. JVM과 이 실행의 전용 MySQL 컨테이너 종료 확인. SPEC_ONLY/SPEC_CODE_REVIEW 실행 전 거부 확인.
- 최종 workflow-completion은 NEEDS_REVIEW(종료 코드 3): 의도된 unsafe 위험과 정적 PARTIAL 근거를 보존했다. 서비스 기동·종료의 실행 assertion은 추가했지만 분석기가 의미를 자동 확정하지 못하므로 정적 상태를 임의 승격하지 않았다. 이번 작업에서 PIT와 외부 JEV API는 실행하지 않았다.
- 미해결 근거 분류·검토 연결(2026-10-01): 본체 204개, Python 검토/회귀 29개 PASS, 전용 MySQL fixture 15개·별도 서비스 검증 PASS. 실제 reviewed complete CLI와 fixture 정책 없는 일반 처리 경로를 확인했다. 정책이 있으면 실제 테스트 부족 5개가 차단되며, 없으면 의도된 결함 5개 근거도 차단된다. 검증 PASSED·검토 NEEDS_REVIEW·위험 UNRESOLVED를 분리했고 정적 판정은 보존했다. 잘못된 클래스의 JUnit·분석 진단 누락은 실패 재현 후 수정했다.
- Lifecycle 공백 보완(2026-10-01): 테스트 코드만 추가한 뒤 주소 생성자의 구현 부재를 컴파일 실패로 확인했다. 이어 별도 구현 단계에서 package-private 주소 주입을 추가해 실제 점유 포트의 bind 실패를 재현했다. 기동 전 반복 stop·이후 start·반복 bind 실패·미기동 상태·포트 해제 후 재시도·최종 포트 반환 테스트 2개 PASS. 전체 fixture 17개·본체 204개·Python 29개와 별도 JVM HTTP/MySQL 검증 PASS. 변경 전 15개 모두 유지되며 기대 테스트 수 갱신의 해시 기반 근거를 연결한 실제 complete CLI에서도 COMPLETED. 정적 bind 예외 COVERED, 검토 분류 TEST_GAP 0/STATIC_LIMITATION 21/INTENTIONAL_DEFECT 5와 위험 UNRESOLVED를 각각 보존했다.

## JEV 보정·실패 분류·수정 반복·최종 품질 게이트 (2026-10-03)

네 영역을 기존 CODE_CHANGE 실행기에 연결했다. [설정·실행 계약](../requirements-analysis/code-change-workflow.md)과 [JEV 보정](../requirements-analysis/model-routing.md)을 따른다.

- JEV는 버전이 있는 난이도·위험도 Score와 sufficiency Choice를 제공한다. 작업 의미와 generator 규칙을 분리하고, API 독립 반올림을 보수적으로 검증한다. 위험도 floor 이후 최종 Tier의 confidence를 계산하며 기존 0.8/0.85/0.9 기준을 유지한다. 실제 저장소 12개 고정 작업의 두 평가에서 수락된 18회 판단은 오판 0건, 불명확한 4회 판단은 차단됐다. 14개 알려진 보정 판단 중 12회, 6개 알려진 holdout 판단 모두 수락했다. 근거는 `build/jev-calibration-current/summary.json`이다.
- 실제 JUnit failure/error와 javac production 위치를 구조화한다. assertion·일부 production 런타임 오류·확정된 HIGH/CRITICAL 정적 위험만 수정 대상으로 삼는다. 환경·인증·잔액·근거·입력 변경은 자동 수정하지 않는다. credential이나 원본 실패 로그를 모델·artifact에 저장하지 않는다.
- `repair`는 수락한 테스트·빌드 계약을 고정하고 승인된 production 파일만 수정한다. 수정 반복, JEV/CLI 호출, 전체 시간과 명시적 fallback을 제한하며 세션 재개가 예산을 초기화하지 않는다. 검증·생성 이력은 해시 보호 사본으로 남긴다. 별도 서비스·review·complete 단계는 계속 필요하다.
- `qualityGates`를 켜면 최종 전체 테스트 실행의 JaCoCo와 새 PIT 실행을 목표 클래스별로 검사한다. 소스·테스트·정책·JAR/JUnit snapshot·XML/exec·mutation 응답·main/test 바이트코드가 같아야 review/complete를 통과한다. 누락·변조·살아남은/미커버 변이·미완료 실행을 통과시키지 않는다. `repair`에는 명시적 품질 설정이 필수다. 기존 수동 경로의 계약과 public API는 유지한다.

소규모 JEV 보정은 개발 모델 품질 평가를 대신하지 않는다. 개발 모델의 Golden Benchmark·Cost per Successful Task·Shadow·Canary·승격과 일반 서비스 adapter, 선택형 아키텍처·컨벤션 검증은 다음 구현 범위다.

### 최종 실제 연결 검증

`build/repair-workflow-smoke-summary.json`에 API·CLI·Gradle을 mock하지 않은 임시 Java 프로젝트 시험을 기록했다. JEV → Router → Codex 테스트 생성 후 실제 assertion 실패를 분류하고, 1회 구현 수정으로 `value > 0`을 `value >= 0`으로 고쳤다. 수락한 테스트 해시는 유지됐으며 최종 JUnit 4개, JaCoCo LINE/BRANCH 100%, PIT 3개 모두 KILLED였다.

검증된 최종 JAR를 별도 JVM의 HTTP 시험 harness에서 사용해 정상·중복·동시성·원자적 rollback과 프로세스 종료를 확인했다. 저장소의 일반 서비스 adapter 구현이나 MySQL 트랜잭션 검증을 의미하지 않는다. 정적 TEST-001 한계는 실제 요구사항·소스·assertion·JUnit 해시에 연결된 검토로 처리했고 원래 정적 상태를 변경하지 않았다. `review`·`complete`는 COMPLETED였다. coverage XML 변조는 완료를 차단했고, 원본 복구·실제 JUnit/JaCoCo/PIT 재실행·새 근거 검토 후 최종 세션은 다시 COMPLETED였다.

공식 Java 25 `bash ./gradlew build --no-daemon`은 PASS이며 Python 149개를 실행했다. 기존 Java 233개 결과는 UP-TO-DATE로 재사용했다. timeout 시 자식 소켓 해제의 race를 보강하고 기존 종료·취소 테스트도 통과했다.

복잡한 HTTP fixture의 단일 메서드에서는 정적 Obligation ID 중복을 발견했다. 현재는 EVIDENCE/NEEDS_REVIEW로 차단하고 자동 수정이나 완료에 사용하지 않는다. ID 생성·중복 원인 보강은 다음 수정 대상이다. 이 시험의 성공 범위는 순수 계산 클래스와 별도 서비스 harness로 선언된 검증이며, 모든 Spring 업무의 자동 완료를 보장하지 않는다.
