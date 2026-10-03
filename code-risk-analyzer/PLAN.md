# AI Agent 개발 자동화 구현 계획

## 목표와 방향

요구사항부터 테스트 작성, 구현, 검증, 수정 반복까지 한 저장소에서 관리한다. Codex·Claude 전환 기능도 이 저장소에 통합하고 외부 `Downloads/ai-router` 경로에 의존하지 않는다.

기존 Java 분석기와 Python 실행기를 재사용한다. 기능은 내부에서 역할별로 구분하되, 초기부터 별도 저장소·패키지로 분리하거나 기존 구조를 일괄 재편하지 않는다.

설계 기준은 [중앙집중형 AI Agent 개발 자동화 워크플로우 설계 보고서](docs/중앙집중형%20AI%20Agent%20개발%20자동화%20워크플로우%20설계%20보고서.pdf)와 [현재 워크플로우 구현 현황](docs/status/WORKFLOW_IMPLEMENTATION_STATUS.md)이다. 현황 문서에 기록된 기능의 실제 동작은 구현 착수 시 관련 검증으로 확인한다.

## 재사용할 자산

| 자산 | 활용 방향 |
|---|---|
| 기존 요구사항 schema·프롬프트 생성기 | TaskSpec·PromptContract 연결의 출발점 |
| `scripts/code_change_workflow.py` | 단계 순서, 상태 저장, 근거 무효화 제어 |
| 회귀·근거 검토 실행기 | 기존 테스트 보호와 완료 판정 |
| Java 정적 분석·테스트 적정성·JEV·PIT 연계 | 검증 계층 |
| `ai-router-0.3`의 Codex·Claude adapter | CLI 호출, 모델 allowlist, 세션 이어가기 코드 통합 |

현재 ai-router는 provider를 지정해 CLI를 실행하는 수준이다. 사용량 기반 자동 선택·fallback, 실행 timeout·취소, 작업 디렉터리 지정, 품질 검증은 별도로 보완해야 한다.

## 내부 역할

- **Agent 호출:** provider·모델 선택, 독립 세션, timeout·취소, 구조화 결과 처리
- **워크플로우:** 입력 계약 검증, 단계 실행, 실패별 복귀, 반복·시간·비용 한도
- **검증:** 테스트·coverage·mutation·정적 위험·선택적 아키텍처·컨벤션·JEV
- **실행·근거 관리:** 격리 작업 디렉터리, 변경분 검토·적용, artifact·해시·상태 저장

첫 MVP는 단일 저장소·단일 Runner로 시작한다. Codex·Claude 호출에 Registry·Policy 기반 모델 선택, reasoning 전달, JEV의 Tier·위험도 판단과 제한된 생성 오류 escalation을 연결한다. 평가 결과에 따른 비용 최적화와 다중 계정 스케줄러는 후속으로 둔다.

## 모델 라우팅과 평가

JEV는 필요한 Tier와 위험도를 판단하고 실제 모델 선택은 Router가 Registry·Policy로 수행한다. 명시적 단계별 Tier 선택도 유지한다. 모델 이름·지원 effort·상태는 Registry 데이터, Tier별 선택과 fallback은 Policy 데이터로 관리한다. 현재 설정과 구현 범위는 [Capability Tier 기반 모델 라우팅](docs/requirements-analysis/model-routing.md)을 따른다.

- 현재 실행 경로에서는 ACTIVE profile만 선택한다. Tier 경로·지원 effort가 없으면 실행을 거부한다.
- 선택한 adapter·모델·reasoning·요구 Tier와 Registry·Policy 해시를 artifact에 기록한다. 파일이나 설정 변경은 이전 완료 근거를 무효화한다.
- CLI 정상 완료 후 응답 계약 오류에만 최대 세 번의 총 호출과 명시적 fallback을 적용한다. 인증·잔액·환경·timeout·입력·해시 오류는 escalation하지 않는다. JEV의 UNKNOWN·낮은 confidence는 실행을 차단한다.
- JUnit·빌드·정적 검증 실패를 수정·재검증하는 루프는 후속 범위이며 기존 실패 게이트를 유지한다.
- Golden Benchmark·Shadow·Canary와 자동 승격은 MVP 이후 범위다. 모델·effort·작업 종류·prompt/도구 버전 조합을 평가하며 승격 조건은 `QualityGatePassed AND (CostImproved OR LatencyImproved)`로 정의한다. 후보 실행은 별도 작업 공간에서 수행한다.

## 아키텍처·코드 컨벤션 on/off

두 검증은 독립적으로 켜고 끌 수 있도록 기존 `.code-risk.yml`에 설정을 추가한다. 아래는 제안 형식이며 아직 구현된 설정은 아니다.

```yaml
validators:
  architecture:
    enabled: false
  convention:
    enabled: false
```

- 설정이 없을 때는 기존 실행 동작을 보존한다. 현재 제공하지 않는 검증을 자동으로 활성화하지 않는다.
- 비활성화하면 해당 검증과 관련 JEV 호출을 생략하고 결과에 `SKIP`과 사유를 기록한다.
- 활성화했지만 검증기나 필요한 도구가 없으면 통과로 처리하지 않고 실행 불가 상태를 명시한다.
- 테스트·트랜잭션·동시성·멱등성 등 다른 필수 검증에는 영향을 주지 않는다.
- 설정 변경 시 관련 검증 근거와 완료 판정을 무효화한다.

## 구현 순서

| 단계 | 작업 | 완료 기준 |
|---|---|---|
| 1. Router 통합 | 필요한 adapter·설정 코드를 가져오고 명시적 workspace, timeout·취소, 정상 완료·실패 판정, 구조화 결과 추가 | 양쪽 provider의 호출 계약을 테스트하고 작은 작업을 실제 실행 |
| 2. 선택 검증 설정 | 아키텍처·컨벤션 설정 파싱, 실행 분기, SKIP 기록 연결. 검증 규칙은 기존 자산부터 연결하고 부족한 규칙은 별도 범위로 구현 | 독립 on/off, 기본 동작 보존, 실행 불가 처리 확인 |
| 3. 입력·근거 계약 | 기존 요구사항 schema와 TaskSpec·PromptContract 연결. artifact에 소스·테스트·입력·설정·정책·도구·프롬프트 버전과 해시 기록 | 입력이나 코드가 바뀌면 오래된 결과의 재사용·완료 차단 |
| 3a. 최소 모델 라우팅 | Registry·Policy·단계별 Tier 연결, reasoning 전달, 선택 결과와 정책 해시 기록 | ACTIVE·지원 조합만 선택, 직접 선택 경로 보존, 정책 변경 시 과거 근거 차단 |
| 3b. JEV Capability·생성 오류 escalation | Tier·위험도 Choice 판단, 불확실 판단 차단, 명시적 fallback과 총 호출 한도 | JEV 실제 연결, 생성 오류별 재시도·중단, 키 미노출·입력 변경 차단 |
| 4. 자동 수정 루프 | 테스트 작성·구현 호출을 기존 단계 실행기에 연결. 실패별 복귀와 반복 한도 구현 | 작은 버그 하나를 재현 테스트 → 구현 → 검증 → 완료까지 처리 |
| 5. 최종 품질 게이트 | JUnit·JaCoCo·PIT·정적 분석·미해결 근거 통합 | 현재 코드에 연결된 필수 근거가 모두 유효할 때만 완료 |
| 6. 중앙 실행 연결 | 같은 저장소 안에 Control Plane과 Runner 연결 추가. 작업 등록·조회, lease·heartbeat, artifact 업로드 지원 | 중복 전달·Runner 중단·재시도 시 결과 중복 반영 방지 |
| 7. 성능 검증 확장 | SQL 횟수·fingerprint, N=1/10/50 시나리오부터 연결하고 이후 EXPLAIN 추가 | 반복 조회·쿼리 증가를 실제 실행 근거로 탐지 |

첫 MVP 범위는 1~5단계다. 중앙 UI, 다중 계정, JMH/JFR, 자동 PR 제출은 후속으로 둔다. commit·push·merge·배포는 이 계획만으로 자동 수행하지 않는다.

## 실행과 완료 원칙

1. Agent는 격리 작업 디렉터리에서 실행한다. 원본 프로젝트에는 변경 범위·현재 해시를 확인한 뒤 기존 작성 실행기로 적용한다.
2. 테스트 작성과 구현은 독립 세션으로 호출한다. 테스트 작성에 이후 구현안이나 생성 코드를 전달하지 않는다.
3. 기존 테스트 기준선을 먼저 확인한다. 새 테스트의 구현 전 실패는 예상 원인과 함께 기록하며, 컴파일 실패와 동작 재현 실패를 구분한다.
4. 신규 구현 전 mutation 결과를 최종 근거로 인정하지 않는다. 코드나 테스트가 바뀌면 기존 결과를 무효화하고 실행 가능한 최종 코드에서 다시 검증한다.
5. 재사용 키에는 소스뿐 아니라 테스트·설정·입력 artifact·정책·도구·프롬프트 버전을 포함한다.
6. 명확한 실패는 코드가 복귀 경로를 결정한다. 모호한 의미 판단만 JEV에 전달하고 낮은 confidence·근거 부족·반복 한도 초과는 검토 대기로 종료한다.
7. Agent/JEV 판단으로 필수 검증 실패를 통과시키거나 테스트를 약화하지 않는다.
8. 동일 workspace의 변경 적용은 직렬화한다. timeout·취소 시 자식 프로세스를 정리하고, 부분 적용 실패 시 적용 목록을 남겨 후속 단계를 차단한다.
9. 중앙 실행에서는 lease 만료 후 재실행과 오래된 Runner의 결과 제출을 함께 고려한다. 실행 attempt 식별과 조건부 상태 갱신으로 현재 작업의 결과만 수락한다.
10. Provider credential은 로컬 인증 또는 지정된 비밀 관리 경로에서만 사용하고 artifact·실행 상태·로그에 저장하지 않는다.

## 검증 계획

- Adapter: 명령 인자·workspace·세션 분리, CLI 실패, 오류 응답, 잘못된 JSON, timeout·취소·프로세스 정리
- 모델 라우팅: Tier·profile·effort 일치, 비활성 모델 거부, 양쪽 CLI reasoning 전달, Registry·Policy 변경·artifact 변조·생성 중 변경 차단, JEV 신뢰도·위험도 최소 기준, 생성 오류별 fallback·한도·scope 차단
- 선택 검증: 독립 on/off, 설정 생략 시 기존 동작, 잘못된 설정 거부, 활성 검증기 부재, SKIP 사유
- 워크플로우: 단계 순서 위반, 범위 밖 변경, 해시 충돌, 부분 실패, 오래된 근거, 반복 한도
- 완료 판정: 테스트·mutation·위험 검증 실패와 미해결 근거 차단, 최종 코드와 artifact 일치
- 중앙 실행: 중복 작업 전달, lease 만료, 재시도, 오래된 attempt의 결과 거부
- 실제 연결: 양쪽 provider 호출 확인과 작은 버그 수정 한 건의 전체 흐름 검증

관련 테스트부터 실행하고 최종 변경 후 저장소의 전체 검증을 수행한다. 현재 `check`에는 Python workflow 테스트가 연결되어 있으므로 최종 `./gradlew build --no-daemon` 결과도 확인한다.

## 다음 구현

사용자 승인 후 실제 코드 두 건의 JEV API 시험은 응답 계약 PASS, 작업 판단은 confidence 0.35/0.65로 NEEDS_REVIEW였다. 실제 작업별 기준 데이터와 검토 결과로 질문의 Tier 구분, context 범위와 confidence 기준을 먼저 보정한다. 두 관측값만으로 수락 기준을 변경하지 않는다. 시험 근거는 `build/jev-approved-api-test/summary.json`이다.

JUnit·빌드·정적 검증 실패를 구현 결함과 환경·입력·근거 오류로 구분하고, 수락한 테스트를 고정한 구현 수정·재검증 루프를 연결한다. 생성 응답 오류 escalation과 별도로 실제 검증 실패를 근거로 판단하며 총 반복·시간 한도와 오래된 결과 차단을 유지한다. 이후 최종 JaCoCo/PIT 근거를 완료 게이트에 통합한다.

## 구현 기록

- 로컬 Codex·Claude 호출 adapter와 `generate-tests`/`generate-implementation` 단계 연결을 추가했다. 실행 계약·제한·사용법은 [로컬 Agent 생성 연결](docs/requirements-analysis/agent-generation.md)에 기록한다.
- 생성기는 독립 세션과 임시 작업 디렉터리를 사용하고, 변경 적용은 기존 scope·해시 검증 및 작성 실행기를 거친다. 요구사항 시나리오별 테스트 연결과 최종 JUnit 결과의 PASS도 검사한다.
- 2026-10-03: Registry·Routing Policy와 명시적 단계별 Tier를 기존 생성 경로에 연결했다. 양쪽 CLI에 reasoning을 전달하고, 선택 결과·Registry/Policy 해시를 기록한다. JEV의 Capability 자동 판단과 평가 기반 자동 선택은 후속 범위다.
- 2026-10-03: JEV의 Tier·위험도 판단과 최대 세 번의 생성 오류 escalation을 추가했다. 불확실한 JEV 판단·인증·잔액·환경·timeout·scope·입력 변경은 실행이나 재시도를 차단한다. 합성 입력으로 실제 JEV `jev-1.13.0` → Router → Codex 연결과 낮은 신뢰도 차단을 검증했다. 검증 실패의 자동 수정과 평가 기반 비용 최적화는 후속이다.
- 실제 Codex의 경계 버그 재현·수정과 JUnit·Gradle·정적 검증을 확인했다. 2026-10-03에는 Codex `gpt-6.1-sol / medium`과 Claude `sonnet / medium`의 실제 구조화 응답 계약·선택 메타데이터를 확인했다. Claude 검증은 잔존 DeepSeek 환경변수를 제외한 호출에서 통과했다. Claude의 전체 버그 수정 흐름은 별도 검증 범위다.
- 선택형 아키텍처·컨벤션 설정, 자동 수정 반복, JaCoCo/PIT 통합 최종 게이트, 중앙 실행과 성능 검증은 아직 구현 완료 상태가 아니다.

이 문서는 구현 계획이며, 구현 기록에 명시하지 않은 신규 설정과 기능은 완료 상태가 아니다.
