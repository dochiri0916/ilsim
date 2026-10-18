# 전체 문서 목차

처음 사용한다면 [저장소 빠른 시작](../README.md#빠른-시작)을 본다. 상세 문서는 소유 프로젝트 안에서 관리한다. README는 안내와 링크를 담당하고 상세 설명은 해당 주제의 기준 문서에서 관리한다.

## 사용과 분석

| 주제 | 기준 문서 |
|---|---|
| 분석기 개요와 문서 진입점 | [Code Risk Analyzer](../code-risk-analyzer/README.md) |
| 분석기 명령과 종료 코드 | [분석기 참고서: 명령](../code-risk-analyzer/docs/reference/analysis.md#분석기-명령) |
| 분석 완전성·차단 정책·JEV 호출 상한 | [분석기 참고서: CI 정책](../code-risk-analyzer/docs/reference/analysis.md#분석-완전성과-ci-정책) |
| 외부 JAR·클래스 디렉터리·타입 판정 | [분석기 참고서: 클래스패스](../code-risk-analyzer/docs/reference/analysis.md#클래스패스와-타입-판정) |
| JUnit·JaCoCo·DB 시나리오 연결 | [분석기 참고서: 실행 증거](../code-risk-analyzer/docs/reference/analysis.md#실행-기반-테스트-증거) |
| 동시성 22개 규칙의 요구사항 | [원본 명세](../code-risk-analyzer/docs/reference/java-mysql-concurrency-spec.md) |
| 동시성 규칙의 구현 상태·우선순위·근거 | [자동 생성 진행표](../code-risk-analyzer/docs/reference/concurrency.md) |

## 소비 프로젝트 컨벤션

Build Convention의 모듈·플러그인 ID·검사·보고서 개요는 [프로젝트 README](../build-convention/README.md)에 있다. 작업별 읽기 순서는 [컨벤션 안내](../build-convention/docs/conventions/README.md)를 따른다.

| 분류 | 기준 문서 |
|---|---|
| 구조·공통 | [패키지와 요청 흐름](../build-convention/docs/conventions/package-structure.md), [공통 코드](../build-convention/docs/conventions/coding-conventions.md), [생활 체조](../build-convention/docs/conventions/object-calisthenics.md) |
| 계층 | [도메인](../build-convention/docs/conventions/domain-conventions.md), [애플리케이션](../build-convention/docs/conventions/application-conventions.md), [어댑터](../build-convention/docs/conventions/adapter-conventions.md) |
| Kotlin | [Kotlin 도메인·VO·일급 컬렉션](../build-convention/docs/conventions/kotlin-domain-conventions.md) |
| 예외·저장소 | [예외](../build-convention/docs/conventions/exception-conventions.md), [영속성](../build-convention/docs/conventions/persistence-conventions.md), [Querydsl](../build-convention/docs/conventions/querydsl-conventions.md), [DB 변경](../build-convention/docs/conventions/database-migration-conventions.md) |
| 메시징 | [BC 이벤트](../build-convention/docs/conventions/bounded-context-events.md), [Outbox/Inbox](../build-convention/docs/conventions/outbox-inbox-conventions.md) |
| 검증 | [테스트](../build-convention/docs/conventions/test-conventions.md), [검증 절차](../build-convention/docs/conventions/validation-conventions.md), [실행 의미와 근거](../build-convention/docs/conventions/runtime-verification.md) |
| 예시·적용 | [Java/Kotlin 템플릿 실행 조건](../build-convention/docs/templates/README.md), [적용 기록](../build-convention/docs/CONVENTION_ROLLOUT.md) |

[규칙 관리표](../build-convention/docs/conventions/coverage.json)와 [MySQL 검증 기록](../build-convention/docs/conventions/template-verification.json), [이전 PostgreSQL 검증 기록](../build-convention/docs/conventions/template-verification-postgresql.json)은 기계가 읽는 근거 자료다.

## 저장소 개발과 자료 관리

| 주제 | 문서 |
|---|---|
| 빌드·검증, Git 이력·원본 저장소·보존 브랜치 | [저장소 개발과 이력](repository.md) |
| AI 비용·JEV 우선 처리·모델/추론 강도 선택·지속 보정 요구사항 | [AI 비용 분석](ai-cost-analysis.md) |
| 저장소 작업 지침 | [루트 AGENTS](../AGENTS.md) |
| AI 세션 인수인계(진행 상태·로컬 실행 환경) | [세션 인수인계](handoff.md) |
| 플러그인 개발 지침 | [Build Convention AGENTS](../build-convention/AGENTS.md) |
| SARIF 스키마 출처·SHA-256 | [스키마 기록](../code-risk-analyzer/src/test/resources/schemas/README.md) |

컨벤션의 `conventions/`·`templates/`·`CONVENTION_ROLLOUT.md`는 소비 프로젝트의 규칙과 예시를 관리하며 문서 간 상대 경로를 유지한다. 스키마 출처는 관련 리소스 옆에 둔다. 동시성 진행표는 카탈로그를 수정한 뒤 `code-risk-analyzer`에서 `python3 scripts/render_concurrency_progress.py`로 갱신한다.
