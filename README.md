# Build Convention

AI 코드 생성 환경에서 컨벤션 편차를 줄이기 위해, `Checkstyle`, `PMD`, `SpotBugs`와 커스텀 Validator, ArchUnit 검증을 묶은 Gradle 컨벤션 플러그인 프로젝트입니다.
여기에는 실행 가능한 코드와 핵심 요약만 두고, 설계 배경과 선택 기준은 블로그 글로 분리했습니다.

이 플러그인은 아키텍처 적합성의 자동 검증 가능한 필요조건을 확인합니다. Aggregate의 실제 불변식, Ubiquitous Language, Context Map, 객체의 응집도처럼 의미적 설계가 올바르다는 충분조건까지 보증하지는 않습니다.

상세 규칙은 다음 문서에서 관리합니다.

- [Architecture Convention](docs/ARCHITECTURE.md)
- [Error Handling Convention](docs/ERROR_HANDLING.md)
- [Testing Convention](docs/TESTING.md)

## 주요 기능

- `io.github.dochiri0916.build-convention` 플러그인으로 정적 분석 도구를 `./gradlew check` 경로에 통합합니다.
- 레이어 의존성 규칙(`domain -> application/adapter` 금지, `application -> adapter` 금지)을 Validator로 강제합니다.
- 도메인/엔티티 분리, JPA Entity 접미사와 `@Table` 명시, 정적 팩토리 규칙을 자동 검증합니다.
- `validateArchitectureConventions` 태스크로 패키지/네이밍, Domain record와 불변 `final class` Aggregate, JPA Entity, Mapper, Controller 의존성, Spring 컴포넌트 등록 규칙을 검증합니다.
- 모든 architecture violation에는 안정적인 `[ARCH-...]` rule ID가 붙으며, 예외 계약과 Domain Aggregate 모델 규칙은 독립 Validator로 분리되어 있습니다.
- Application은 같은 Context의 Domain/Port와 `@Service`, `@Transactional`에만 의존하도록 source와 ArchUnit에서 검증합니다.
- Application import는 JDK, 같은 Context의 Domain/Port/Application 예외, 기술 독립적인 `global.exception` 공통 계약, `@Service`, `@Transactional`, `@RequiredArgsConstructor`의 명시적 허용 목록으로 검증합니다. 같은 Context의 다른 Application Service 구현체와 임의 외부 SDK도 거부합니다.
- Controller collaborator는 Inbound `*UseCase`, Application Service collaborator는 Outbound `*Port` 또는 같은 Context Domain `*Service`만 허용합니다.
- Repository Port의 mutation 파라미터와 반환 타입에서 Aggregate Root를 추론하여 정확히 하나의 Root와 연결하고, 같은 Context의 다른 Aggregate Root 직접 참조를 거부합니다.
- 다른 Context의 Domain/Application 타입과 식별자 VO 직접 참조를 막고, 공유 계약은 명시한 Published Language 패키지만 허용합니다.
- Repository Port의 생성과 변경은 `create`와 `update`로 분리하고, `save`·`upsert` 변경 계약은 거부합니다.
- ID를 가진 불변 Aggregate는 private final 상태, private constructor, 정적 factory와 ID 동등성을 사용합니다. `create`는 신규 상태를 만들고 `restore`(또는 호환 이름 `reconstitute`)는 영속 상태 복원에 사용합니다.
- Aggregate의 단순 ID/VO 집합은 private final 필드에 `List.copyOf`·`Set.copyOf`·`Map.copyOf`로 방어 복사해 직접 보관할 수 있습니다. raw scalar 원소, mutable 보관, 또는 독립적인 도메인 행위·불변식을 가진 컬렉션은 허용하지 않으며 후자는 First-class Collection으로 모델링합니다.
- `requireNonNull`은 불변 Aggregate의 private constructor에서 이미 생성된 구성요소를 구조적으로 보호할 때만 허용합니다. Value Object와 공개 Domain 행위의 불변식 실패는 Domain Exception 정적 factory로 표현해야 합니다.
- Domain/Application의 concrete 예외는 private 생성자와 의미 있는 static factory를 사용합니다. 공통 abstract `DomainException`·`ApplicationException` 기반 타입은 하위 예외를 위한 protected 생성자를 둘 수 있습니다.
- Context·계층별 concrete 예외는 기본적으로 하나만 허용하며, 타입별 분기가 필요한 Context는 `exceptionTypeSplitAllowlist`로 명시적으로 허용합니다.
- 공통 오류 처리는 `global.exception`에 둡니다. `ErrorCode`·`ErrorKind`·공통 예외 기반 타입은 Spring Web을 모르는 순수 계약으로 유지하고, `BusinessException`은 ErrorCode와 불변 extensions를 보관합니다.
- `GlobalExceptionHandler`는 Spring의 `ResponseEntityExceptionHandler`를 상속하고 `BusinessException`을 `ProblemDetail`로 직접 변환합니다. 별도 Exception Mapper Registry는 다중 프로토콜·API 버전 같은 실제 요구가 생기기 전에는 만들지 않습니다.
- extensions에는 비밀번호, 토큰, 원문 개인정보를 넣지 않습니다. API 응답에 복사할 값은 공개해도 안전한 진단 맥락으로 제한합니다.
- Java source는 JDK AST parser로 먼저 일괄 파싱하며 문법 오류를 fail-closed 처리합니다. package/import/type, superclass/interface, field와 record component annotation, constructor/method, Entity/Table 및 Transactional 선언과 줄바꿈된 fully-qualified 타입 참조를 AST로 판정합니다. FQCN 후보는 실제 Java 식별자 구간만 수집해 메서드 호출 체인을 타입 의존성으로 오인하지 않습니다. Java 테스트 annotation과 method도 AST로 찾고 `SourcePositions`로 원본 body를 추출해 phase 주석을 보존합니다. 메서드 body의 호출 패턴은 주석, 문자열, 문자 literal, Java text block의 brace를 안전하게 건너뛰는 공통 lexical scanner와 함께 분석합니다.
- Java 테스트 메서드의 한국어 `@DisplayName`, `// given`, `// when`, `// then` 구조, assertion 없는 테스트와 no-exception/verify-only 테스트를 검증합니다.
- 도메인 전용 품질 게이트(`checkstyleDomain`, `pmdDomain`, `spotbugsDomain`)를 별도로 실행할 수 있습니다.
- `validateArchUnitArchitecture` 태스크로 컴파일된 클래스의 레이어 의존성을 ArchUnit으로 검증합니다.
- `jacocoTestReport`, `jacocoTestCoverageVerification`을 `check`에 연결해 전체/계층별 커버리지를 검증합니다.
- `validateChangedCodeCoverage` 태스크로 commit, staged, working tree, untracked Java 변경의 커버리지를 `check`에서 검증합니다.
- PIT 플러그인이 적용된 프로젝트는 `validatePitMutationGate`로 Domain/Application 변이 테스트 기준을 검증할 수 있습니다.
- `validateMigrationConventions` 태스크로 누적 SQL migration의 `{target}_id` unique, 참조 컬럼 인덱스/FK, 기술 id 참조 금지를 검증합니다.
- MSA 모듈에서는 `enforceMsaWebAdapterBoundary`로 외부 공개 API와 내부 서비스 API의 Web Adapter 패키지 경계를 검증할 수 있습니다.
- 핵심 검증 태스크를 `enabled = false`로 끄거나, 품질 태스크를 `ignoreFailures = true`로 완화하거나, 필수 컨벤션 플래그를 `false`로 낮추면 `check`에서 실패합니다.
- 이 플러그인 저장소 자체의 `check`도 CodeNarc와 JaCoCo 전체 line 85%/branch 80% 기준을 실행합니다.

## 요구 사항

- JDK 21
- Gradle 9.0.0 이상

## 플러그인 자체의 Groovy 품질 규칙

`config/codenarc/codenarc.xml`은 이 저장소의 Gradle 플러그인 구현 코드와 테스트 코드에 적용하는 CodeNarc 설정입니다. 플러그인을 사용하는 Java/Spring 프로젝트에 전달되는 Checkstyle·PMD·SpotBugs 규칙과는 별개입니다.

`./gradlew check`를 실행하면 `codenarcMain`, `codenarcTest`가 이 설정을 사용해 `build-convention` 내부의 Groovy 코드를 검사합니다.

## 설치

Gradle Plugin Portal에 공개된 버전은 사용하는 프로젝트의 `build.gradle`에서 바로 적용합니다.
별도의 저장소 clone이나 `mavenLocal()` 설정은 필요하지 않습니다.

```groovy
plugins {
    id 'java'
    id 'io.github.dochiri0916.build-convention' version '1.0.0'
}
```

플러그인 좌표는 다음과 같습니다.

```text
id      = io.github.dochiri0916.build-convention
version = 1.0.0
```

Spring Boot 프로젝트라면 기존 플러그인과 함께 적용하면 됩니다.

```groovy
plugins {
    id 'java'
    id 'org.springframework.boot' version '3.5.0'
    id 'io.spring.dependency-management' version '1.1.7'
    id 'io.github.dochiri0916.build-convention' version '1.0.0'
}
```

bootstrap class가 없는 라이브러리/하위 모듈은 base package를 명시합니다.

```groovy
hexagonalConvention {
    basePackage = 'com.example.project'
}
```

## Plugin Portal 승인 전 또는 로컬 개발

Plugin Portal 최초 심사 전이거나 플러그인 자체를 개발하면서 테스트할 때는 로컬 Maven 저장소를 사용할 수 있습니다.

```bash
./gradlew publishToMavenLocal
```

사용 프로젝트의 `settings.gradle`에 `mavenLocal()`을 추가합니다.

```groovy
pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
        mavenCentral()
    }
}
```

그다음 일반 사용과 동일하게 버전을 지정합니다.

```groovy
plugins {
    id 'java'
    id 'io.github.dochiri0916.build-convention' version '1.0.0'
}
```

플러그인 소스를 수정하면서 즉시 검증하고 싶다면 composite build를 사용할 수 있습니다.

```groovy
pluginManagement {
    includeBuild('../build-convention')

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
    id 'io.github.dochiri0916.build-convention'
}
```

## Plugin Portal 배포

배포 전 전체 검증과 publication 설정 검증을 실행합니다.

```bash
./gradlew clean check
./gradlew publishPlugins --validate-only
```

실제 배포는 다음 명령으로 수행합니다.

```bash
./gradlew publishPlugins
```

배포 credential은 저장소에 커밋하지 않고 사용자 전역 Gradle 설정이나 CI Secret으로 관리합니다.

## 실행 방법

```bash
./gradlew check
```

플러그인 개발 저장소에서는 위 명령이 Groovy production/test source의 CodeNarc와 JaCoCo 85% line/80% branch 게이트도 함께 실행합니다. 대상 Java 프로젝트에 제공하는 계층별 커버리지 게이트와 별개로, Validator 구현 자체가 테스트되지 않은 채 배포되는 것을 막기 위한 self-check입니다.

`check`에는 기본적으로 다음 검증이 포함됩니다.

- Checkstyle, PMD, SpotBugs
- Domain 전용 `checkstyleDomain`, `pmdDomain`, `spotbugsDomain`
- `validateHexagonalArchitecture`
- `validateArchUnitArchitecture`
- `validateEntityNamingConvention`
- `validateDomainStaticFactoryConvention`
- `validateArchitectureConventions`
- `validateClaudeConventions` (이전 이름 호환 alias)
- `validateMigrationConventions`
- `validateJavaVersionConvention`
- `validateChangedCodeCoverage`
- `jacocoTestReport`
- `jacocoTestCoverageVerification`

`check`는 핵심 검증을 끄는 설정도 함께 검증합니다.
다음과 같은 설정은 컨벤션 위반을 숨기므로 허용하지 않습니다.

```bash
./gradlew check -x validateArchitectureConventions
./gradlew check -x test
```

```groovy
tasks.named('validateArchitectureConventions') {
    enabled = false
}

tasks.named('validateArchitectureConventions') {
    onlyIf { false }
}

tasks.withType(Pmd).configureEach {
    ignoreFailures = true
}

tasks.withType(com.github.spotbugs.snom.SpotBugsTask).configureEach {
    ignoreFailures = true
}

tasks.named('checkstyleMain') {
    source = files()
}

hexagonalConvention {
    enforceDomainEntitySeparation = false
    domainLineCoverageMinimum = 0.10
}
```

위와 같은 설정이 있으면 `Build convention verification must not be disabled` 오류로 실패합니다.
컨벤션 위반은 검증을 끄지 말고 코드를 리팩터링해서 해결해야 합니다.

테스트 코드에서 `@Disabled`나 JUnit Assumptions로 실패 테스트를 건너뛰는 것도 `validateArchitectureConventions`에서 실패합니다.

## MSA Web Adapter 경계 검증

MSA 프로젝트나 MSA 모듈에서는 외부 공개 API와 서비스 간 내부 API를 명시적으로 분리할 수 있습니다.

```groovy
hexagonalConvention {
    enforceMsaWebAdapterBoundary = true
}
```

이 옵션을 켜면 `validateArchitectureConventions`가 `adapter.in.web` 아래 패키지를 검사합니다.
MSA 모듈에서는 반드시 `external` 또는 `internal` 경계를 거쳐야 합니다.

```text
{context}.adapter.in.web.external
{context}.adapter.in.web.external.request
{context}.adapter.in.web.external.response
{context}.adapter.in.web.internal
{context}.adapter.in.web.internal.request
{context}.adapter.in.web.internal.response
```

MSA 모듈에서 허용되지 않는 예시는 다음과 같습니다.

```text
{context}.adapter.in.web
{context}.adapter.in.web.request
{context}.adapter.in.web.response
```

권장 경로 규칙은 다음과 같습니다.

```text
외부 공개 API: /api/**
내부 서비스 API: /internal/**
```

API Gateway와 Kubernetes Ingress는 `/api/**`만 외부로 노출하고, `/internal/**`은 클러스터 내부 서비스 간 호출 전용으로 둡니다.
Domain/Application 계층에는 `external`/`internal` 구분을 전파하지 않습니다.

MSA가 아닌 단일 애플리케이션이나 MSA 경계가 없는 모듈은 이 옵션을 켜지 않습니다.
기본값은 `false`이며, 이 경우 기존 패키지 구조를 사용합니다.

```text
{context}.adapter.in.web
{context}.adapter.in.web.request
{context}.adapter.in.web.response
```

변경된 Production 코드 커버리지는 `check`에서 자동으로 실행됩니다.
기준 ref는 `origin/main`, `origin/master`, `main`, `master` 순서로 자동 탐색합니다.
기준 ref를 찾지 못하거나 변경 파일이 JaCoCo XML에 없으면 검증에 실패합니다.
로컬에서는 commit된 변경뿐 아니라 staged, working tree, untracked Java 파일도 함께 검사합니다.
명시적으로 지정하려면 다음처럼 기준 브랜치를 넘깁니다.

```bash
./gradlew check -PchangedCoverageBaseRef=origin/main
```

## 외부 서비스 식별자 migration

같은 데이터베이스의 참조 컬럼은 index와 FK를 모두 요구합니다. 다른 서비스나 다른 데이터베이스의 식별자는 FK 대신 명시 metadata를 남기고 index만 요구합니다.

```sql
-- build-convention: external-reference deliveries.remote_member_id
create table deliveries (
    id bigint primary key,
    delivery_id varchar(32) not null unique,
    remote_member_id varchar(32) not null
);
create index idx_deliveries_remote_member_id on deliveries(remote_member_id);
```

서로 다른 migration 파일에 index와 FK가 나뉘어 있어도 파일명 순서의 누적 schema를 기준으로 검증합니다.

## 로컬에서 `check` 실행 강제

AI Agent나 개발자가 `check`를 실행하지 않고 작업을 끝내는 것을 막으려면 Git hook을 함께 사용합니다.
로컬에서는 `pre-push` hook으로 push 직전에 `./gradlew check`를 강제하는 방식을 권장합니다.

Git 저장소 루트에서 `.githooks/pre-push` 파일을 만듭니다. 현재 위치가 Git 저장소인지 먼저 확인합니다.

```bash
git rev-parse --show-toplevel
```

`fatal: not in a git directory`가 나오면 아직 Git 저장소가 아니거나 저장소 밖에서 실행한 것입니다. 기존 저장소라면 저장소 루트로 이동하고, 새 프로젝트라면 `git init` 후 다시 진행합니다.

```bash
mkdir -p .githooks
cat > .githooks/pre-push <<'EOF'
#!/bin/sh
set -eu

if [ ! -x ./gradlew ]; then
  echo "ERROR: ./gradlew not found or not executable"
  exit 1
fi

if ! find . \( -name 'build.gradle' -o -name 'build.gradle.kts' \) -print | xargs grep -q "io.github.dochiri0916.build-convention"; then
  echo "ERROR: io.github.dochiri0916.build-convention plugin is not applied"
  exit 1
fi

./gradlew check
EOF
```

실행 권한을 부여하고 Git hook 경로를 설정합니다.

```bash
chmod +x .githooks/pre-push
git config core.hooksPath .githooks
```

이제 `git push` 시점마다 `check`가 실행되고, 실패하면 push가 중단됩니다.

개인 로컬에서만 간단히 쓰려면 `.git/hooks/pre-push`에 직접 넣을 수도 있습니다.

```bash
mkdir -p .git/hooks
cat > .git/hooks/pre-push <<'EOF'
#!/bin/sh
set -eu

if ! find . \( -name 'build.gradle' -o -name 'build.gradle.kts' \) -print | xargs grep -q "io.github.dochiri0916.build-convention"; then
  echo "ERROR: io.github.dochiri0916.build-convention plugin is not applied"
  exit 1
fi

./gradlew check
EOF
chmod +x .git/hooks/pre-push
```

단, `.git/hooks`는 Git에 커밋되지 않으므로 팀 공통 강제에는 `.githooks`와 `core.hooksPath` 방식을 사용합니다.

로컬 hook은 push 시점에만 동작합니다. 최종 방어선은 CI에서 `./gradlew check`를 필수로 실행하는 것입니다.

GitHub Actions 예시는 다음과 같습니다.

```yaml
name: build

on:
  pull_request:
  push:
    branches: [ main ]

jobs:
  check:
    runs-on: ubuntu-latest

    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 21

      - uses: gradle/actions/setup-gradle@v4

      - name: Verify build convention plugin
        run: |
          if ! find . \( -name 'build.gradle' -o -name 'build.gradle.kts' \) -print | xargs grep -q "io.github.dochiri0916.build-convention"; then
            echo "io.github.dochiri0916.build-convention plugin is not applied"
            exit 1
          fi

      - run: ./gradlew check -PchangedCoverageBaseRef=origin/main
```

## 커버리지 기준 조정

기본값은 플러그인에 정의된 기준을 따릅니다. 프로젝트에서 더 높은 기준을 지정할 수 있지만, 기본 기준보다 낮게 완화되지는 않습니다.

```groovy
hexagonalConvention {
    domainLineCoverageMinimum = 0.98
    domainBranchCoverageMinimum = 0.95
    inboundAdapterLineCoverageMinimum = 0.85
    inboundAdapterBranchCoverageMinimum = 0.75
    changedLineCoverageMinimum = 0.92
    changedBranchCoverageMinimum = 0.88
}
```

PIT는 `info.solidsoft.pitest` 플러그인이 적용된 프로젝트에서 자동으로 `check`에 포함됩니다.
PIT 플러그인을 적용하지 않은 프로젝트에서 강제로 실패시키고 싶다면 다음 옵션을 켭니다.

```groovy
hexagonalConvention {
    enforcePitOnCheck = true
}
```

## 자세한 내용

- [AI 코드 컨벤션을 빌드에서 강제해 일관성 지키기](https://velog.io/@dochiri0916/AI-%EC%BD%94%EB%93%9C-%EC%BB%A8%EB%B2%A4%EC%85%98%EC%9D%84-%EB%B9%8C%EB%93%9C%EC%97%90%EC%84%9C-%EA%B0%95%EC%A0%9C%ED%95%B4-%EC%9D%BC%EA%B4%80%EC%84%B1-%EC%A7%80%ED%82%A4%EA%B8%B0)
