# Build Convention

AI 코드 생성 환경에서 컨벤션 편차를 줄이기 위해, `Checkstyle`, `PMD`, `SpotBugs`와 커스텀 Validator, ArchUnit 검증을 묶은 Gradle 컨벤션 플러그인 프로젝트입니다.
여기에는 실행 가능한 코드와 핵심 요약만 두고, 설계 배경과 선택 기준은 블로그 글로 분리했습니다.

## 실험 범위

- `com.dochiri.lint-convention` 플러그인으로 정적 분석 도구를 `./gradlew check` 경로에 통합합니다.
- 레이어 의존성 규칙(`domain -> application/infrastructure/presentation` 금지, `application -> infrastructure/presentation` 금지)을 Validator로 강제합니다.
- 도메인/엔티티 분리, 엔티티 단수/테이블 복수 네이밍, 정적 팩토리 규칙을 자동 검증합니다.
- 도메인 전용 품질 게이트(`checkstyleDomain`, `pmdDomain`, `spotbugsDomain`)를 별도로 실행할 수 있습니다.
- `samples/consumer-test`에서 ArchUnit 테스트로 의존성 규칙을 이중 검증합니다.

## 빠른 실행

```bash
./gradlew check
./gradlew checkstyleDomain pmdDomain spotbugsDomain
./gradlew -p samples/consumer-test clean check
```

## 자세한 내용

- [AI 코드 컨벤션을 빌드에서 강제해 일관성 지키기](docs/velog/AI%20코드%20컨벤션을%20빌드에서%20강제해%20일관성%20지키기.md)
