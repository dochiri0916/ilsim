# Build Convention

AI 코드 생성 환경에서 컨벤션 편차를 줄이기 위해, `Checkstyle`, `PMD`, `SpotBugs`와 커스텀 Validator, ArchUnit 검증을 묶은 Gradle 컨벤션 플러그인 프로젝트입니다.
여기에는 실행 가능한 코드와 핵심 요약만 두고, 설계 배경과 선택 기준은 블로그 글로 분리했습니다.

## 실험 범위

- `com.dochiri.lint-convention` 플러그인으로 정적 분석 도구를 `./gradlew check` 경로에 통합합니다.
- 레이어 의존성 규칙(`domain -> application/adapter` 금지, `application -> adapter` 금지)을 Validator로 강제합니다.
- 도메인/엔티티 분리, 엔티티 단수/테이블 복수 네이밍, 정적 팩토리 규칙을 자동 검증합니다.
- `validateClaudeConventions` 태스크로 패키지/네이밍, Domain record, JPA Entity, Mapper, Controller 의존성 규칙을 검증합니다.
- 도메인 전용 품질 게이트(`checkstyleDomain`, `pmdDomain`, `spotbugsDomain`)를 별도로 실행할 수 있습니다.
- `validateArchUnitArchitecture` 태스크로 컴파일된 클래스의 레이어 의존성을 ArchUnit으로 검증합니다.
- `jacocoTestReport`, `jacocoTestCoverageVerification`을 `check`에 연결해 전체/계층별 커버리지를 검증합니다.
- `validateChangedCodeCoverage` 태스크로 CI에서 Git diff 기반 변경 코드 커버리지를 검증할 수 있습니다.
- PIT 플러그인이 적용된 프로젝트는 `validatePitMutationGate`로 Domain/Application 변이 테스트 기준을 검증할 수 있습니다.
- `validateMigrationConventions` 태스크로 SQL migration의 `{target}_id` unique, 참조 컬럼 인덱스/FK, 기술 id 참조 금지를 검증합니다.

## 요구 사항

- JDK 21
- Gradle 9.0.0 이상

## 로컬 Maven 배포

이 프로젝트를 로컬 Maven 저장소에 먼저 배포합니다.

```bash
cd ~/programming/utility/build-convention
./gradlew publishToMavenLocal
```

현재 플러그인 좌표는 다음과 같습니다.

```text
id      = com.dochiri.lint-convention
version = 1.5.0
```

## 사용 프로젝트 설정

사용할 프로젝트의 `settings.gradle`에 `mavenLocal()`을 추가합니다.

```groovy
pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
        mavenCentral()
    }
}
```

사용할 프로젝트의 `build.gradle`에 플러그인을 적용합니다.

```groovy
plugins {
    id 'java'
    id 'com.dochiri.lint-convention' version '1.5.0'
}
```

Spring Boot 프로젝트라면 기존 플러그인과 함께 적용하면 됩니다.

```groovy
plugins {
    id 'java'
    id 'org.springframework.boot' version '3.5.0'
    id 'io.spring.dependency-management' version '1.1.7'
    id 'com.dochiri.lint-convention' version '1.5.0'
}
```

## 실행 방법

```bash
./gradlew check
```

`check`에는 기본적으로 다음 검증이 포함됩니다.

- Checkstyle, PMD, SpotBugs
- Domain 전용 `checkstyleDomain`, `pmdDomain`, `spotbugsDomain`
- `validateHexagonalArchitecture`
- `validateArchUnitArchitecture`
- `validateEntityNamingConvention`
- `validateDomainStaticFactoryConvention`
- `validateClaudeConventions`
- `validateMigrationConventions`
- `validateJavaVersionConvention`
- `jacocoTestReport`
- `jacocoTestCoverageVerification`

변경된 Production 코드 커버리지를 CI에서 강제하려면 기준 브랜치를 함께 넘깁니다.

```bash
./gradlew check -PchangedCoverageBaseRef=origin/main
```

이 옵션이 있으면 `validateChangedCodeCoverage`도 `check`에 포함됩니다.

개발 중 로컬 배포 없이 바로 연결하고 싶다면 사용 프로젝트의 `settings.gradle`에서 composite build를 사용할 수 있습니다.

```groovy
pluginManagement {
    includeBuild('~/programming/utility/build-convention')

    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
```

이 경우 `build.gradle`에서는 버전을 생략합니다.

```groovy
plugins {
    id 'java'
    id 'com.dochiri.lint-convention'
}
```

## 커버리지 기준 조정

기본값은 CLAUDE.md 기준을 따른다. 프로젝트에서 더 높은 기준을 지정할 수 있지만, 기본 기준보다 낮게 완화되지는 않는다.

```groovy
hexagonalConvention {
    domainLineCoverageMinimum = 0.98
    domainBranchCoverageMinimum = 0.95
    changedLineCoverageMinimum = 0.92
    changedBranchCoverageMinimum = 0.88
}
```

PIT는 `info.solidsoft.pitest` 플러그인이 적용된 프로젝트에서 설정된다. `check`에 묶고 싶다면 다음 옵션을 켠다.

```groovy
hexagonalConvention {
    enforcePitOnCheck = true
}
```

## 자세한 내용

- [AI 코드 컨벤션을 빌드에서 강제해 일관성 지키기](https://velog.io/@dochiri0916/AI-%EC%BD%94%EB%93%9C-%EC%BB%A8%EB%B2%A4%EC%85%98%EC%9D%84-%EB%B9%8C%EB%93%9C%EC%97%90%EC%84%9C-%EA%B0%95%EC%A0%9C%ED%95%B4-%EC%9D%BC%EA%B4%80%EC%84%B1-%EC%A7%80%ED%82%A4%EA%B8%B0)
