# Ilsim (일심) — 개발 플랫폼 구현 계획서

> Version: 0.1  
> Status: Draft  
> 목적: AI와 정적 분석을 활용한 전역 통합 개발 관리 플랫폼

## 1. 프로젝트 개요

### 1.1 목표

Ilsim은 여러 프로젝트의 개발 환경을 중앙에서 관리하고, AI Agent를 활용해 요구사항 분석부터 구현, 정적 분석, 테스트, 품질 검증까지 자동화하는 플랫폼이다. // mcp 서버로 만들거임.

**핵심 목표**

- Claude Code 기반 AI Agent 관리(Claude 계정 여러 개)
- 프로젝트별 아키텍처 및 코드 컨벤션 자동 분석
- 프로젝트별 개발 정책 자동 생성 및 적용
- AI 기반 요구사항 분석과 Spec 생성
- 구현 전 사용자 승인 절차
- 기존 기능의 회귀 버그 방지
- 정적 분석, 단위·통합·E2E 테스트 자동화
- JEV 기반 의미적 검증 및 모델 라우팅
- 회사 PC와 개인 PC 등 여러 실행 환경 통합
- 웹 UI 및 모바일을 통한 원격 관리

### 1.2 핵심 설계 원칙

1. 기존 기능의 동작을 의도 없이 변경하지 않는다.
2. 요구사항과 테스트 명세가 확정되기 전에는 구현하지 않는다.
3. 기존 테스트를 임의로 삭제하거나 약화하지 않는다.
4. 프로젝트별 아키텍처와 코드 컨벤션을 독립적으로 관리한다.
5. AI의 판단만으로 검증을 완료하지 않는다.
6. 정적 분석과 실제 테스트 실행을 함께 사용한다.
7. 검증 누락이나 실패를 정상 통과로 처리하지 않는다.
8. 승인되지 않은 변경은 자동 병합하지 않는다.
9. DB 등 외부 시스템의 변경 권한을 엄격히 제한한다.

**한계:** 모든 회귀 버그의 부재를 증명할 수는 없다. 따라서 식별된 요구사항의 추적성과 필수 검증 실행을 강제하고, 확인되지 않은 위험은 명시적으로 관리한다.

---

## 2. 기술 스택

### 2.1 Backend

| 항목 | 기술 |
|---|---|
| Language | Java 25 |
| Framework | Spring Boot 4.1.1 → 가장 최신 스테이블 버전 |
| Build | Gradle Kotlin DSL |
| Group | `com.dochiri` |
| Database | MySQL |
| ORM | Spring Data JPA |
| Migration | Flyway |
| Security | Spring Security |
| Validation | Jakarta Validation |
| MCP | Spring AI MCP Server |
| Architecture | Hexagonal + Modular Monolith |

### 2.2 Frontend

- React
- 반응형 웹 UI
- PWA 지원
- Web Push 알림
- SSE를 통한 실시간 작업 상태 표시

### 2.3 AI 및 자동화

- Claude Code CLI
- 공식 지원 범위 내 원격 Agent
- GitHub Spec Kit
- TypeSafe JEV
- Global Agent Skills
- MCP Server
- Local Agent Runner

---

## 3. 시스템 아키텍처

```text
Ilsim Platform
│
├── Web UI / Mobile PWA
│   ├── Dashboard
│   ├── Project Management
│   ├── Policy Management
│   ├── Agent Task Management
│   ├── Analysis Reports
│   └── Approval & Notifications
│
├── Spring Boot Server
│   ├── REST API
│   ├── MCP Server
│   ├── Project Registry
│   ├── Policy Registry
│   ├── Policy Discovery Engine
│   ├── Agent Orchestrator
│   ├── Model Router
│   ├── JEV Evaluator
│   ├── Validation Engine
│   ├── Quality Gate
│   └── Notification Service
│
├── Runner Infrastructure
│   ├── Company PC Runner
│   ├── Personal PC Runner
│   ├── Claude Local Adapter
│   ├── Remote Agent Adapters
│   └── Analysis Runner
│
└── Infrastructure
    ├── MySQL
    ├── Git Repositories
    ├── Task Queue
    └── Test Sandboxes
```

### 3.1 컴포넌트 책임

**Ilsim Server**

- 프로젝트 및 정책 관리
- 작업 생성과 상태 관리
- 요구사항 및 Spec 승인
- AI Agent 실행 요청
- 검증 결과 저장 및 Quality Gate 판정

**Agent Runner**

- Claude Code 실행
- Git Worktree 생성
- 프로젝트 코드 탐색
- 로컬 분석 도구 실행
- 실행 로그 및 결과 전달

**Governance MCP**

- 공통 정책 조회
- JEV 평가
- 아키텍처·컨벤션 검사 요청
- 테스트 검증 요청
- 품질 검증 결과 제공

REST API와 MCP는 동일한 Application Service를 사용한다.

---

## 4. 핵심 도메인

| 도메인 | 책임 |
|---|---|
| Workspace | 회사·개인 환경 및 접근 권한 분리 |
| Project | 프로젝트 등록 및 설정 |
| ProjectModule | 모듈·경로별 기술 스택 관리 |
| Policy | 개발 정책 정의 |
| PolicyVersion | 정책 버전 관리 |
| PolicyBinding | 프로젝트와 정책 연결 |
| PolicyException | 정책 적용 예외 관리 |
| Runner | 실행 PC 및 환경 관리 |
| Agent | AI Agent 종류와 실행 기능 관리 |
| AgentTask | 개발 작업 및 상태 관리 |
| Specification | 요구사항과 구현 명세 |
| Approval | 사용자 승인 이력 |
| AnalysisRun | 정적 분석 및 테스트 실행 기록 |
| Finding | 발견된 문제·위험 |
| QualityGate | 검증 결과의 최종 판정 |
| Notification | 이벤트 및 알림 관리 |

### 4.1 프로젝트와 정책 관계

하나의 프로젝트에는 여러 정책이 적용될 수 있으며, 하나의 정책은 여러 프로젝트에서 재사용할 수 있다.

```text
Project
  └── ProjectModule
        └── PolicyBinding
              └── PolicyVersion
                    └── Policy
```

정책 적용 우선순위:

1. Global Mandatory Policy
2. Architecture / Framework Profile
3. Project Policy
4. Module / Path Policy

하위 정책은 허용된 항목만 재정의할 수 있으며, 필수 보안 및 회귀 검증 정책은 무단으로 완화하지 못한다.

---

## 5. 프로젝트 등록 및 정책 자동 발견

### 5.1 목표

기존 프로젝트를 등록하면 아키텍처와 코드 컨벤션을 자동 분석하고, 실행 가능한 검증 정책을 생성한다.

### 5.2 처리 과정

```text
Project Registration
        ↓
Technology Stack Detection
        ↓
Source / AST / Dependency Analysis
        ↓
Architecture & Convention Inference
        ↓
AI + JEV Policy Review
        ↓
Static Analysis Rule Generation
        ↓
Baseline Validation
        ↓
Policy Gap Analysis
        ↓
User Approval
        ↓
Policy Version & Project Binding
```

### 5.3 자동 발견 대상

**아키텍처**

- 패키지 및 모듈 구조
- Layered / Hexagonal / 기타 구조
- 계층 간 의존성
- 순환 참조
- Port / Adapter 사용 패턴
- 클래스 역할 및 책임
- 트랜잭션 경계

**코드 컨벤션**

- 클래스·메서드 네이밍
- DTO 네이밍
- Request / Response / Command / Result 구분
- 예외 처리 패턴
- 생성자 주입 여부
- 어노테이션 사용 규칙
- 테스트 코드 작성 패턴

### 5.4 자동 정책 생성

발견한 규칙은 가능한 경우 다음 도구의 실행 가능한 설정이나 코드로 변환한다.

- ArchUnit 규칙
- Checkstyle 설정
- PMD 규칙
- Semgrep 규칙
- Spotless 포맷 설정
- 테스트 정책

정책 생성 후 기존 프로젝트에 시험 적용하여 잘못된 규칙과 기존 위반 사항을 확인한다.

**중요:** 기존 코드의 관찰된 패턴이 반드시 올바른 설계는 아니다. AI가 추론한 규칙은 후보로 저장하고 사용자 승인 후 적용한다.

### 5.5 Baseline 관리

기존에 존재하던 문제와 신규 변경으로 발생한 문제를 구분한다.

- 기존 위반 사항 기록
- 신규 위반 사항 탐지
- 정책 변경 전후 비교
- 신규 위반 차단
- 기존 위반의 단계적 개선 지원

---

## 6. 요구사항 분석 및 Spec 관리

### 6.1 도구

- GitHub Spec Kit
- Requirement Analyst Skill
- JEV
- AI Agent
- Spec Validator

### 6.2 워크플로우

```text
User Requirement
       ↓
Existing Project Analysis
       ↓
Missing Requirements Detection
       ↓
Clarification Questions
       ↓
User Decisions
       ↓
Specification Generation
       ↓
Spec Validation
       ↓
Summary Presentation
       ↓
User Approval
       ↓
Implementation
```

### 6.3 필수 기능

- 기존 프로젝트 코드 및 정책 분석
- 요구사항의 모호성 탐지
- 확인 사항과 결정 사항 분리
- 사용자 질의응답
- 예외 상황 분석
- 기존 기능 영향도 분석
- Acceptance Criteria 생성
- 구현 작업 목록 생성
- 최종 Summary 제공
- 사용자 승인 전 구현 금지

### 6.4 Spec 산출물

```text
specs/
└── feature-name/
    ├── requirements.md
    ├── decisions.md
    ├── design.md
    ├── impact-analysis.md
    ├── acceptance.md
    ├── test-spec.md
    ├── tasks.md
    └── summary.md
```

승인된 Spec은 버전 및 해시로 관리한다.

구현 중 승인 범위를 벗어나는 변경이 필요하면 작업을 중단하고 재승인을 요청한다.

---

## 7. AI Agent Orchestration

### 7.1 지원 대상

- Claude Code
- 향후 추가할 AI Coding Agent

### 7.2 실행 환경

| 모드 | 설명 |
|---|---|
| LOCAL | PC에 로그인된 CLI 실행 |
| REMOTE | 공식 원격 Agent 서비스 실행 |
| HYBRID | 로컬 분석과 원격 실행 조합 |

구독 계정으로 로그인한 로컬 CLI 실행을 우선 지원한다.

원격 Agent의 작업 생성·중단·재개·모델 지정은 공급자의 공식 인터페이스와 구독 사용 조건이 허용하는 범위에서만 지원한다.

### 7.3 Agent Adapter

```text
AgentExecutionPort
├── ClaudeLocalAdapter
└── ClaudeRemoteAdapter
```

각 Adapter는 지원 기능을 명시해야 한다.

- 작업 실행
- 실행 취소
- 세션 재개
- 모델 선택
- 진행 이벤트
- 사용자 승인 요청
- 결과 및 Diff 조회

지원되지 않는 기능은 비활성화한다.

### 7.4 Agent Task 상태

```text
CREATED
   ↓
ANALYZING
   ↓
CLARIFYING
   ↓
SPEC_REVIEW
   ↓
APPROVED
   ↓
IMPLEMENTING
   ↓
VALIDATING
   ↓
COMPLETED
```

추가 상태:

- `WAITING_FOR_USER`
- `BLOCKED`
- `FAILED`
- `CANCELLED`

Agent Task 상태는 Spring Boot에서 영속적으로 관리하며, 에이전트 프로세스의 임의 판단만으로 승인 상태가 변경되지 않도록 한다.

---

## 8. JEV 기반 평가 및 모델 라우팅

### 8.1 JEV 활용 영역

- 요구사항의 누락 가능성 평가
- Spec 모호성 및 모순 평가
- 코드 변경의 회귀 위험 평가
- 테스트 누락 가능성 평가
- 정적 분석 결과의 의미적 위험 평가
- Agent 작업 유형 및 복잡도 분류

### 8.2 모델 라우팅

작업 실행 전에 JEV를 통해 다음 정보를 평가한다.

- 작업 유형
- 난이도
- 변경 위험도
- 불확실성
- 필요한 추론 수준

이후 Policy Engine이 지원되는 Agent 모델 중 적합한 모델을 선택한다.

```text
User Request
    ↓
Context Collection
    ↓
JEV Evaluation
    ↓
Model Routing Policy
    ↓
Agent / Model Selection
    ↓
Execution
```

### 8.3 최적화 원칙

- 단순 작업은 경량 모델 우선
- 복잡하거나 위험한 변경은 고성능 모델 우선
- 검증 실패 시 상위 모델로 재시도
- 프로젝트별 최소 모델 수준 강제
- 불필요한 컨텍스트 전달 최소화
- 모델 선택과 검증 수준을 별도로 관리

JEV 평가 결과는 판단 근거로 사용하며, 실제 모델 선택은 플랫폼 정책이 결정한다.

구독형 Agent의 내부 모델 호출을 직접 통제할 수 없는 경우 작업 단위 라우팅만 수행한다.

---

## 9. 정적 분석 시스템

### 9.1 분석 도구

| 도구 | 책임 |
|---|---|
| ArchUnit | 아키텍처·계층 의존성 |
| Checkstyle | 코드 컨벤션 |
| SpotBugs | 잠재적 버그 |
| PMD | 코드 품질·패턴 |
| Semgrep | 커스텀 정적 분석 |
| Spotless | 코드 포맷 |
| OWASP Dependency-Check | 의존성 취약점 |
| Gitleaks | 비밀정보 유출 |
| OpenRewrite | 승인된 코드 자동 변경 |

SonarQube는 필요 시 통합 품질 대시보드용으로 도입한다.

### 9.2 검증 방식

**Fast Validation**

- Git Diff 검사
- 아키텍처 규칙
- 코드 컨벤션
- 기본 정적 분석
- 영향받는 단위 테스트

**Full Validation**

- 전체 필수 정적 분석
- 단위·통합 테스트
- 코드 커버리지
- Mutation Testing
- API 계약 테스트
- E2E 테스트
- 보안 검사
- JEV 위험 평가

검사 결과는 가능한 경우 SARIF 등 공통 형태로 정규화한다.

분석기의 미실행·실패·타임아웃도 별도로 기록한다.

---

## 10. 테스트 명세 및 누락 검증

### 10.1 목표

요구사항을 기반으로 테스트 케이스를 자동 정의하고, 중요한 검증 조건이 누락되지 않도록 확인한다.

### 10.2 구성 요소

```text
Test Completeness Engine
├── Requirement Decomposer
├── Scenario Generator
├── Decision Table Generator
├── State Transition Analyzer
├── Test Specification Generator
├── Requirement-Test Traceability
├── Independent Test Auditor
├── JEV Gap Evaluator
└── Test Quality Gate
```

### 10.3 테스트 케이스 분류

- 정상 시나리오
- 예외 시나리오
- 경계값
- 상태 전이
- 동시성
- 멱등성
- 트랜잭션 및 롤백
- API 계약
- 인증·인가
- 기존 기능 회귀
- 성능 및 부하(필요한 프로젝트에 적용)

### 10.4 요구사항 추적성

```text
Requirement
    ↓
Acceptance Criteria
    ↓
Test Scenario
    ↓
Test Implementation
    ↓
Test Execution
    ↓
Validation Result
```

모든 승인된 요구사항에는 검증 조건과 테스트가 연결되어야 한다.

적용하지 않는 테스트 유형은 제외 사유를 기록한다.

### 10.5 테스트 품질 평가

| 도구 | 역할 |
|---|---|
| JUnit 5 | 단위·통합 테스트 |
| Mockito | 의존성 모의 |
| Testcontainers | 격리된 실제 DB·메시지 브로커 테스트 |
| WireMock | 외부 API 응답 모의 |
| JaCoCo | 코드 커버리지 |
| PIT | Mutation Testing |
| jqwik | Property-Based Testing |
| Pact / Spring Cloud Contract | API 호환성 |
| Playwright | 화면·E2E 테스트 |

기존 테스트 코드의 삭제·수정·assertion 약화는 별도 검증한다.

PIT와 JaCoCo 측정값을 사용해 테스트 품질을 평가하되, 높은 점수를 버그 부재의 증거로 취급하지 않는다.

---

## 11. 통합 테스트 및 화면 테스트

### 11.1 실행 환경

테스트는 운영 DB가 아닌 별도 격리 환경에서 실행한다.

```text
Test Runner
    ↓
Sandbox / Testcontainers
    ↓
Backend + Frontend Startup
    ↓
API / Integration Tests
    ↓
Playwright E2E Tests
    ↓
Visual Regression Tests
    ↓
Result Collection
```

### 11.2 Playwright

**Playwright MCP**

- AI Agent의 브라우저 탐색
- 화면 동작 확인
- 테스트 시나리오 발견

**Playwright Test**

- 재현 가능한 E2E 테스트
- 실제 UI 조작
- 브라우저별 테스트
- 스크린샷 비교
- Trace Viewer
- 콘솔 및 네트워크 오류 확인

MCP로 수행한 일회성 브라우저 조작만으로 검증 완료 처리하지 않는다. 필수 시나리오는 재실행 가능한 테스트로 저장한다.

### 11.3 기존 동작 비교

필요한 경우 변경 전후 버전에 동일한 입력을 전달하는 Differential Testing을 적용한다.

- API 응답 비교
- DB 상태 변화 비교
- 이벤트 발행 비교
- 주요 UI 동작 비교

승인된 Spec에 포함된 의도적 변경은 비교 정책에 반영한다.

---

## 12. Quality Gate

### 12.1 판정 결과

| 상태 | 의미 |
|---|---|
| PASS | 필수 검증 통과 |
| REVIEW | 사용자 검토 필요 |
| BLOCK | 정책 위반 또는 검증 실패 |
| UNKNOWN | 검증 미실행·불충분·분석 불가 |

### 12.2 필수 차단 조건

- 승인되지 않은 Spec 변경
- 승인 범위 밖 코드 변경
- 기존 테스트의 무단 삭제·약화
- 신규 치명적 정적 분석 위반
- 필수 테스트 실패
- 요구사항-테스트 연결 누락
- 필수 분석기 실행 실패
- 미해결 고위험 항목
- 승인되지 않은 DB 변경 시도

Quality Gate의 최종 차단은 Runner·Hook·CI 등 실제 실행 경계에서 강제한다.

JEV 점수만으로 자동 승인하지 않는다.

---

## 13. DB 접근 보안

### 13.1 정책

AI Agent가 기존 DB를 임의로 변경하지 못하도록 한다.

- 기본 DB 직접 접근 차단
- 허용된 조회만 수행
- INSERT / UPDATE / DELETE / DDL 차단
- 승인되지 않은 자격 증명 사용 금지
- 테스트 DB는 격리된 환경에서만 쓰기 허용

### 13.2 기존 DB 계정 제약

기존 DB 계정을 임의로 생성하거나 권한을 변경할 수 없는 환경도 지원해야 한다.

이를 위해 다음 방식을 검토한다.

- Read-only DB Gateway
- SQL 파싱 및 허용 목록 검사
- DB 직접 연결 네트워크 차단
- 자격 증명 격리
- 샌드박스 실행

**주의:** 기존 계정이 쓰기 권한을 갖고 있고 에이전트가 해당 자격 증명에 접근할 수 있다면 Gateway만으로 완전한 차단을 보장할 수 없다. 네트워크와 프로세스 권한을 함께 격리해야 한다.

---

## 14. 다중 PC 및 원격 접근

### 14.1 Runner 구조

```text
Central Control Plane
├── Company Workspace
│   └── Company PC Runner
└── Personal Workspace
    └── Personal PC Runner
```

프로젝트별 실행 Runner를 지정한다.

Runner는 자신의 인증 정보로 중앙 서버에 연결하고, 허가된 작업만 가져와 실행한다.

### 14.2 보안 정책

- 회사와 개인 Workspace 분리
- 프로젝트별 Runner 접근 제한
- 회사 소스 코드 외부 전송 기본 제한
- 회사 정책에 따른 AI·JEV 연동 허용
- Runner별 인증 및 권한 관리
- Runner가 오프라인이면 작업 대기

### 14.3 원격 웹 접근

초기:

- 로컬 Spring Boot 서버
- Tailscale 등으로 개인 기기에서 접속

확장:

- Cloud Control Plane
- Local Agent Runner
- 공식 Remote Agent
- 중앙 작업 상태 및 알림 관리

웹 서버와 Agent Runner는 물리적으로 다른 환경에서 실행될 수 있도록 설계한다.

---

## 15. Web UI 및 모바일

### 15.1 웹 화면

| 화면 | 기능 |
|---|---|
| Dashboard | 전체 프로젝트 품질 및 상태 |
| Projects | 프로젝트·모듈 등록 및 관리 |
| Runners | 실행 PC 연결 및 상태 |
| Policies | 정책 목록·버전·프로젝트 연결 |
| Discovery | 자동 발견된 정책 후보 |
| Policy Review | 규칙 승인·거부·수정 |
| Agent Tasks | AI 작업 생성·상태 확인 |
| Spec Review | 요구사항·결정 사항·명세 승인 |
| Analysis | 정적 분석 결과 |
| Tests | 단위·통합·E2E 테스트 결과 |
| History | 변경 및 실행 이력 |
| Settings | Agent·모델·알림 설정 |

### 15.2 모바일 PWA

iPhone Safari에서 웹사이트를 홈 화면에 추가해 사용하는 형태로 구현한다.

제공 기능:

- 작업 요청
- Agent 상태 확인
- Spec Summary 확인
- 사용자 승인·거절
- 에이전트 질문 답변
- 분석·테스트 결과 확인

### 15.3 알림

- Spec 승인 요청
- Agent 질문 발생
- 작업 완료
- 테스트 실패
- 정적 분석 차단
- Runner 연결 해제

기본 알림은 Web Push로 구현하고, 필요하면 Telegram 등의 채널을 추가한다.

알림을 안정적으로 운영하려면 서버가 외부에서 접근 가능한 환경에 상시 실행되어 있어야 한다.

---

## 16. 개발 단계

### Phase 0 — 기본 설계

- [ ] 전체 요구사항 명세 작성
- [ ] 핵심 도메인 정의
- [ ] 아키텍처 및 모듈 경계 정의
- [ ] Agent Task 상태 머신 설계
- [ ] 프로젝트·정책·Runner 관계 설계
- [ ] 보안·실행 권한 정책 정의
- [ ] MVP 범위 승인

**산출물:** `architecture.md`, `domain-model.md`, `mvp-spec.md`

### Phase 1 — 핵심 백엔드

- [ ] Spring Boot 프로젝트 기본 설정
- [ ] MySQL / Flyway 구성
- [ ] 인증·인가 기본 구조
- [ ] Workspace 및 Project 관리
- [ ] Runner Registry
- [ ] Policy Registry
- [ ] Project Policy Binding
- [ ] 최소 REST API 및 관리 화면

**완료 조건:** 프로젝트, Runner, 정책을 등록하고 연결할 수 있다.

### Phase 2 — Agent 실행 및 Spec

- [ ] Claude Code Local Adapter
- [ ] Git Worktree 기반 작업 격리
- [ ] Agent Task 상태 관리
- [ ] GitHub Spec Kit 연동
- [ ] Requirement Analyst Skill
- [ ] Spec Summary 생성
- [ ] 사용자 승인 Gate
- [ ] 작업 로그 스트리밍

**완료 조건:** 요구사항 입력부터 Spec 승인, 격리된 구현 작업까지 진행할 수 있다.

### Phase 3 — 자동 정책 발견

- [ ] 프로젝트 구조 스캐너
- [ ] AST 및 의존성 분석
- [ ] 아키텍처 후보 추론
- [ ] 코드 컨벤션 추론
- [ ] 정책 자동 생성
- [ ] 생성 규칙 시험 실행
- [ ] Policy Gap Analysis
- [ ] 정책 승인 UI
- [ ] Baseline 기록

**완료 조건:** 기존 프로젝트를 분석하고 승인된 검증 정책을 자동 등록할 수 있다.

### Phase 4 — 정적 분석 및 테스트

- [ ] ArchUnit
- [ ] Checkstyle
- [ ] SpotBugs
- [ ] PMD
- [ ] Semgrep
- [ ] JUnit 실행
- [ ] JaCoCo
- [ ] PIT
- [ ] Testcontainers
- [ ] Test Specification Generator
- [ ] Test Completeness Auditor
- [ ] Quality Gate

**완료 조건:** 코드 변경 후 필수 분석과 테스트를 실행하고 정책 위반을 차단한다.

### Phase 5 — MCP 및 JEV

- [ ] Spring AI MCP Server
- [ ] 정책 조회 MCP Tool
- [ ] 분석 요청 MCP Tool
- [ ] JEV Client Adapter
- [ ] 요구사항·Spec 평가
- [ ] 코드 변경 위험 평가
- [ ] 테스트 누락 위험 평가
- [ ] JEV Model Router
- [ ] 모델 선택 이력 및 평가 결과 저장

**완료 조건:** AI Agent에서 공통 정책과 JEV 평가 기능을 호출할 수 있다.

MCP 기본 연결은 필요한 시점에 앞당겨 구현할 수 있다.

### Phase 6 — 통합·화면 테스트

- [ ] 격리된 애플리케이션 실행 환경
- [ ] WireMock 기반 외부 API 테스트
- [ ] API 계약 테스트
- [ ] Playwright MCP
- [ ] Playwright Test
- [ ] E2E 테스트 자동 생성
- [ ] Visual Regression
- [ ] 테스트 실행 리포트
- [ ] 변경 전후 동작 비교

**완료 조건:** 승인된 기능을 실제 애플리케이션 환경에서 실행하고 화면까지 검증할 수 있다.

### Phase 7 — 원격 관리 및 모바일

- [ ] Runner 다중 PC 지원
- [ ] 회사·개인 Workspace 권한 분리
- [ ] 외부 웹 접근
- [ ] 반응형 Web UI
- [ ] PWA
- [ ] Web Push
- [ ] Agent 작업 알림
- [ ] 모바일 Spec 승인

**완료 조건:** 외부 PC와 iPhone에서 작업을 관리하고 알림을 받을 수 있다.

### Phase 8 — Remote Agent 및 운영 최적화

- [ ] 공식 Remote Agent Adapter
- [ ] Local / Remote 실행 선택
- [ ] JEV 모델 라우팅 최적화
- [ ] Agent별 성공률 분석
- [ ] 검증 시간 및 재시도 분석
- [ ] 실행 이력 및 감사 로그 강화
- [ ] 정책 자동 재분석 및 변경 승인
- [ ] 운영 모니터링

---

## 17. 초기 MVP 범위

처음부터 모든 기능을 구현하지 않는다.

**첫 번째 목표는 아래 흐름을 완성하는 것이다.**

```text
프로젝트 등록
    ↓
Runner 연결
    ↓
프로젝트 정책 연결
    ↓
사용자 요구사항 입력
    ↓
Claude Code 실행
    ↓
요구사항 분석 및 질문
    ↓
Spec Summary 생성
    ↓
사용자 승인
    ↓
코드 구현
    ↓
정적 분석 + 테스트
    ↓
결과 확인
```

MVP에서는 다음을 우선 사용한다.

- Spring Boot + MySQL + Flyway
- Spring Security
- Claude Code Local Runner
- GitHub Spec Kit
- ArchUnit
- Checkstyle
- JUnit
- 간단한 웹 UI
- 사용자 승인 Gate

JEV, 고급 정책 자동 추론, PIT, Playwright, 클라우드 원격 실행은 핵심 워크플로우를 완성한 이후 점진적으로 연결한다.

---

## 18. 향후 확정을 요구하는 설계 사항

- [ ] 중앙 서버를 로컬 전용으로 시작할지, 초기부터 원격 서버로 구성할지
- [ ] Angular와 React 중 프런트엔드 선택
- [ ] 회사 프로젝트의 외부 AI/JEV 전송 허용 범위
- [ ] Agent Runner 샌드박스 방식
- [ ] 프로젝트 정책을 DB 중심으로 관리할지 Git 파일과 동기화할지
- [ ] Spec Kit 실행 및 Skill 구성 방식
- [ ] 모델 라우팅 시 구독 한도·품질·처리 시간 우선순위
- [ ] 자동 테스트 환경의 격리 및 DB 안전 정책
- [ ] Remote Agent별 공식 지원 기능
- [ ] 정책 변경과 구현 결과에 대한 최종 승인 기준

---

## 19. 최종 지향점

Ilsim의 최종 사용자 경험은 다음과 같다.

**프로젝트 등록 → 아키텍처·컨벤션 자동 발견 → 정책 승인 → 요구사항 입력 → AI 분석 및 질의응답 → Spec 승인 → 최적 Agent/모델 선택 → 구현 → 정적 분석 → 통합·화면 테스트 → 회귀 검증 → 결과 알림**

궁극적으로 사용자는 프로젝트별 개발 환경과 정책을 매번 수동으로 구성하지 않고도, 하나의 플랫폼에서 여러 AI Agent와 검증 도구를 일관되게 관리할 수 있어야 한다.

**Ilsim — AI 기반 통합 개발 관리 및 품질 검증 플랫폼**