# Code Risk Analyzer

Java 21 이상에서 실행하며 Java/Spring/JPA의 코드 위험과 테스트 적정성을 정적으로 분석한다. 대상 프로젝트에 분석기 의존성이나 Build Convention 플러그인을 추가할 필요가 없다.

빌드·실행은 저장소 루트의 [빠른 시작](../README.md#빠른-시작), 전체 탐색은 [문서 목차](../docs/README.md)를 본다.

| 분류 | 문서 | 내용 |
|---|---|---|
| 참고 | [분석기 참고서](docs/reference/analysis.md) | 명령과 종료 코드, CI 정책, 클래스패스, 실행 증거 |
| 참고 | [동시성 명세](docs/reference/java-mysql-concurrency-spec.md) | Java·Kotlin/JVM + MySQL 동시성 22개 규칙의 전체 요구사항 |
| 참고 | [동시성 구현 진행표](docs/reference/concurrency.md) | 기존 실행 규칙 대응, 우선순위·상태·구현 및 테스트 근거 |

동시성 구현 진행표는 [배포 카탈로그](src/main/resources/concurrency/specification.json)에서 생성한다. 명세 요구사항, 구현 진행 상태, 대상 프로젝트의 실제 분석 결과를 각각 확인한다.

SARIF 테스트용 스키마의 출처와 해시는 [스키마 기록](src/test/resources/schemas/README.md)에 있다. 저장소 자체의 빌드·검증 명령은 [개발 문서](../docs/repository.md#빌드와-검증)를 따른다.
