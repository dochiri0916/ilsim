# Ilsim — 추가 설계 사항

## 1. Agent Harness 도입

### 목적

AI Agent가 프로젝트를 매번 처음부터 분석하지 않고, 기존에 검증된 프로젝트 정보와 개발 규칙을 재사용하도록 한다.

### 주요 기능

- `AGENTS.md` 기반 프로젝트 개발 지침 제공
- Agent와 Skill의 역할 분리
- 작업에 필요한 문서와 코드만 선택적으로 제공
- 프로젝트별 지식 및 작업 이력 관리
- 검증된 경험을 Project Memory로 저장
- 반복적으로 발생하는 문제를 정책 후보로 생성
- 사용자 승인 후 개발 정책으로 승격

### 설계 원칙

- 검증되지 않은 AI의 추측은 Memory에 저장하지 않는다.
- Memory마다 출처, Git Commit, 검증 결과를 기록한다.
- 프로젝트 변경 시 기존 Memory의 유효성을 재검증한다.
- 회사와 개인 프로젝트의 Memory를 격리한다.
- 자동 생성된 정책은 사용자 승인 후 적용한다.

---

## 2. Project Knowledge & Memory Engine

### 목적

프로젝트의 아키텍처, 코드 컨벤션, 설계 결정, 과거 문제 해결 경험을 중앙에서 관리한다.

### 구성 요소

```text
Project Knowledge & Memory
├── Document Registry
│   ├── Architecture
│   ├── Conventions
│   ├── Specifications
│   └── Decisions
│
├── Context Retrieval
│   ├── Source Index
│   ├── Document Search
│   └── JEV Reranker
│
├── Project Memory
│   ├── Current State
│   ├── Verified Learnings
│   ├── Known Pitfalls
│   └── Agent Handoffs
│
└── Memory Lifecycle
    ├── Candidate
    ├── Verification
    ├── Approval
    ├── Active
    └── Superseded
```

### 주요 기능

**Document Registry**
- 프로젝트별 문서 등록 및 버전 관리
- 아키텍처·컨벤션·요구사항·설계 결정 관리
- 문서와 프로젝트·모듈 간 연결

**Context Retrieval**
- 요구사항과 관련된 코드·문서 검색
- JEV 기반 관련성 평가
- 필요한 컨텍스트만 AI Agent에 제공
- 필수 정책 및 보안 규칙은 검색 점수와 무관하게 포함

**Project Memory**
- 검증된 문제 해결 경험 저장
- 기존 아키텍처 및 주요 설계 결정 기록
- 반복되는 장애·실수 패턴 관리
- Agent 작업 간 컨텍스트 전달

**Memory Lifecycle**
- 검증되지 않은 정보는 Candidate로 등록
- 코드·테스트·문서 등의 근거로 검증
- 승인된 정보만 Active로 전환
- 오래되거나 잘못된 정보는 Superseded 처리

---

## 3. JEV 역할 확장

### 목적

JEV를 AI Agent의 의사결정 보조 엔진으로 사용하여 모델 선택, 컨텍스트 최적화, 테스트 품질 및 코드 변경 위험을 평가한다.

| 기능 | 책임 |
|---|---|
| Model Routing | 작업에 적합한 모델 및 추론 수준 선택 지원 |
| Context Selection | 필요한 문서·코드·Memory의 관련성 평가 |
| Test Risk Evaluation | 테스트 시나리오 누락 및 검증 부족 가능성 평가 |
| Change Risk Evaluation | 기존 기능의 회귀 위험 평가 |
| Memory Evaluation | 기억 후보의 관련성·재사용 가치·검토 필요성 평가 |

### 3.1 Model Routing

```text
User Requirement
       ↓
Task Classification
       ↓
JEV Evaluation
       ↓
Model Routing Policy
       ↓
Agent / Model Selection
```

- 작업 유형 및 복잡도 평가
- 변경 위험도 분석
- 모델 및 추론 수준 추천
- 프로젝트별 최소 모델 수준 준수
- 모델 선택 이력과 검증 결과 기록

최종 모델 선택은 JEV가 아닌 Policy Engine에서 결정한다.

### 3.2 Context Selection

```text
User Requirement
       ↓
Document / Code / Memory Search
       ↓
JEV Relevance Evaluation
       ↓
Context Selection
       ↓
AI Agent
```

- 관련 문서 및 코드 탐색
- 프로젝트별 정책과 Memory 검색
- 문서의 관련성 및 모순 가능성 평가
- 불필요한 컨텍스트 제거
- 필수 아키텍처·보안 정책은 항상 포함

### 3.3 Test Risk Evaluation

- 요구사항 대비 테스트 시나리오 누락 가능성 평가
- 정상·예외·경계 조건 검증 부족 탐지
- 기존 기능의 회귀 테스트 부족 가능성 평가
- Acceptance Criteria와 테스트 간 관련성 평가
- 검증이 약한 assertion 식별 지원

JEV 단독으로 테스트의 완전성을 보장하지 않는다.

**검증 도구 연계**

- Test Designer Agent
- Independent Test Auditor
- Requirement-Test Traceability
- JaCoCo
- PIT
- JUnit / Playwright

### 3.4 Change Risk Evaluation

- 변경된 코드의 기존 기능 영향 가능성 평가
- 승인된 Spec과 실제 변경 사항의 의미적 차이 평가
- 불필요한 코드 변경 가능성 탐지
- 테스트 검증 약화 가능성 탐지
- 고위험 변경의 추가 검증 필요성 평가

JEV 평가 결과는 위험 판단을 보조하며 실제 변경 안전성은 정적 분석과 테스트로 검증한다.

### 3.5 Memory Evaluation

- 새로운 Memory 후보의 관련성 평가
- 기존 Memory와 중복·충돌 여부 평가
- 재사용 가치 및 중요도 분류
- 추가 검증이 필요한 내용 식별
- Policy 승격 후보 추천

JEV는 Memory 저장 또는 정책 승격을 단독으로 결정하지 않는다.

---

## 4. Memory 기반 정책 자동 개선

### 목적

과거에 발생한 문제와 검증된 해결 경험을 재사용하여 동일한 실수가 반복되는 것을 방지한다.

### 워크플로우

```text
Agent Task Completed
        ↓
Verified Learning Extraction
        ↓
Memory Candidate Registration
        ↓
Evidence Verification
        ↓
Project Memory Registration
        ↓
Recurring Pattern Detection
        ↓
Policy Candidate Generation
        ↓
User Approval
        ↓
Policy Registry
        ↓
Future Quality Gate
```

### 예시

**발생 문제**

인증 기능 수정 이후 기존 쿠키 처리 로직에서 회귀 버그 발생.

**처리 과정**

1. Agent가 원인 분석
2. 회귀 테스트로 문제 재현 및 수정 검증
3. 검증된 원인과 해결 방법을 Memory 후보로 등록
4. 근거 검증 후 Project Memory에 저장
5. 관련 위험 패턴이 반복되면 Policy 후보 생성
6. 사용자 승인 후 인증 관련 회귀 테스트 정책에 반영
7. 이후 인증 기능 변경 시 필수 검증 실행

### 정책 자동 개선 원칙

- 코드 변경만으로 기존 정책을 자동 완화하지 않는다.
- 반복된 패턴이 항상 올바른 설계라는 가정을 하지 않는다.
- 정책 후보에는 근거와 적용 범위를 기록한다.
- 정책 변경 시 영향을 받는 프로젝트를 분석한다.
- 정책은 버전 관리하고 프로젝트별 승인된 버전을 유지한다.

---

## 5. 통합 워크플로우 변경

```text
Project Selection
        ↓
Project Policy & Memory Loading
        ↓
Context Retrieval
        ↓
JEV Context Evaluation
        ↓
JEV Model Routing
        ↓
GitHub Spec Kit
        ↓
Requirement Analysis & Clarification
        ↓
Specification Generation
        ↓
Test Specification & Gap Analysis
        ↓
User Approval
        ↓
AI Agent Implementation
        ↓
Static Analysis & Regression Testing
        ↓
Quality Gate
        ↓
Verified Learning Extraction
        ↓
Project Memory Update
        ↓
Policy Improvement Proposal
```

### 컴포넌트별 책임

| 컴포넌트 | 책임 |
|---|---|
| AI Agent | 요구사항 분석·설계·코드 생성·문제 해결 |
| GitHub Spec Kit | 명세 작성 및 개발 워크플로우 |
| JEV | 분류·선택·관련성 및 위험 평가 |
| Context Retriever | 프로젝트 정보 검색 및 컨텍스트 구성 |
| Project Memory | 검증된 프로젝트 지식 관리 |
| Policy Engine | 정책 적용 및 최종 판정 |
| Static Analyzer | 코드·아키텍처·컨벤션 검사 |
| Test Runner | 단위·통합·E2E 테스트 실행 |
| Quality Gate | 필수 검증 통과 여부 판단 |

---

## 6. 추가 개발 항목

### Project Knowledge & Memory

- [ ] Document Registry 구현
- [ ] 프로젝트별 문서 인덱싱
- [ ] 코드 및 문서 검색
- [ ] Context Retriever 구현
- [ ] Project Memory 데이터 모델 설계
- [ ] Memory 근거·버전·유효성 관리
- [ ] Memory Lifecycle 구현
- [ ] Workspace별 Memory 접근 제한

### JEV 확장

- [ ] Model Routing
- [ ] Context Relevance Evaluation
- [ ] Test Risk Evaluation
- [ ] Change Risk Evaluation
- [ ] Memory Evaluation
- [ ] JEV 평가 이력 저장
- [ ] 모델 라우팅 품질 측정

### 정책 자동 개선

- [ ] 검증된 Learning 추출
- [ ] 중복 Memory 탐지
- [ ] 정책 승격 후보 생성
- [ ] 정책 변경 영향 분석
- [ ] 사용자 승인 절차
- [ ] Policy Registry 연동
- [ ] 정책 적용 이후 검증 결과 추적

---

## 7. 최종 설계 원칙

**AI Agent는 생성과 실행, JEV는 판단 보조, 정적 분석과 테스트는 검증, Policy Engine은 최종 통제를 담당한다.**

Project Memory는 검증된 지식을 축적하고, 이를 프로젝트별 정책 및 개발 프로세스에 반영한다.

이를 통해 Ilsim은 단순히 개발 작업을 자동화하는 시스템을 넘어, **프로젝트에 대한 이해를 지속적으로 유지하고 검증된 경험을 활용해 개발 품질을 개선하는 중앙 관리 플랫폼**을 지향한다.